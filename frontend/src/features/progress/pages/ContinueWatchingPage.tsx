import { type FocusEvent, type ReactNode, type RefObject, useEffect, useRef } from 'react';
import { Link } from 'react-router-dom';
import { secondaryButtonClass } from '../../../components/buttonStyles';
import { EmptyState, ErrorState, LoadingState } from '../../../components/StateViews';
import { AuthenticationSupersededError } from '../../../lib/api/client';
import { useAuth } from '../../auth/AuthContext';
import { movieGridClass, MovieGridSkeleton } from '../../movies/components/MovieGrid';
import type { ContinueWatchingItem } from '../continueWatchingApi';
import { useContinueWatching } from '../continueWatchingQueries';
import { ContinueWatchingCard } from '../components/ContinueWatchingCard';

const NOTICE_CLASS = 'flex flex-wrap items-center justify-between gap-3 rounded-md border border-amber-600/60 '
  + 'bg-amber-950/50 p-3';

/**
 * Continue Watching (Issue 6.8): the viewer's unfinished movies, most recently watched first, each
 * linking to the watch page, which resumes at the saved position. The API returns at most 50 as one
 * array (ADR-011 D11), so the page has no pagination and says so.
 */
export function ContinueWatchingPage() {
  // Subscribing to the session re-renders the page when it changes, even for the same viewer: the
  // cleared query then runs again under the new session.
  useAuth();
  const headingRef = useRef<HTMLHeadingElement>(null);
  const listRef = useRef<HTMLUListElement>(null);
  // The path of the card link that last had focus.
  const focusedCardPath = useRef<string | null>(null);
  const { data, error, isError, isFetching, fetchStatus, refetch } = useContinueWatching();
  // A request cancelled by a sign-in change is not an error: it runs again under the new session.
  const failed = isError && !(error instanceof AuthenticationSupersededError);
  // TanStack Query does not start a request while the browser is offline; it starts once the connection is back.
  const offline = !data && !failed && fetchStatus === 'paused';

  // A refresh can remove the card that has focus, for example a movie finished on another device. Focus then
  // moves to the page heading instead of being lost.
  useEffect(() => {
    const path = focusedCardPath.current;
    if (path === null || document.activeElement !== document.body) {
      return;
    }
    focusedCardPath.current = null;
    if (!cardPaths(listRef.current).includes(path)) {
      headingRef.current?.focus({ preventScroll: true });
    }
  }, [data]);

  const rememberFocusedCard = (event: FocusEvent<HTMLUListElement>) => {
    focusedCardPath.current = event.target.getAttribute('href');
  };

  // The viewer moved focus out of the list, or to nothing, while the card is still shown: a later refresh
  // must not take focus. A card that a refresh removes is no longer connected, so it stays remembered.
  const forgetCardLeftByViewer = (event: FocusEvent<HTMLUListElement>) => {
    const leftTheList = !event.currentTarget.contains(event.relatedTarget as Node | null);
    if (leftTheList && event.target.isConnected) {
      focusedCardPath.current = null;
    }
  };

  // Retry swaps its button for the loading state or the list, so focus moves to the page heading.
  const retry = () => {
    headingRef.current?.focus({ preventScroll: true });
    void refetch();
  };

  let content: ReactNode;
  if (!data && failed) {
    content = (
      <ErrorState
        error={error}
        isRetrying={isFetching}
        onRetry={retry}
        title="Continue watching could not be loaded"
      />
    );
  } else if (offline) {
    content = <EmptyState description="The list loads when the connection is back." title="You are offline" />;
  } else if (!data) {
    content = (
      <LoadingState label="Loading continue watching…">
        <MovieGridSkeleton />
      </LoadingState>
    );
  } else if (data.length === 0) {
    content = (
      <EmptyState description="Movies you start watching will appear here." title="Nothing to continue yet">
        <Link className={secondaryButtonClass} to="/titles">Browse movies</Link>
      </EmptyState>
    );
  } else {
    content = (
      <ContinueWatchingGrid
        items={data}
        listRef={listRef}
        onBlur={forgetCardLeftByViewer}
        onFocus={rememberFocusedCard}
      />
    );
  }

  return (
    <section aria-labelledby="continue-watching-heading" className="grid gap-6">
      <div className="grid gap-2">
        <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
          <h1
            className="text-3xl font-semibold text-white focus:outline-none"
            id="continue-watching-heading"
            ref={headingRef}
            tabIndex={-1}
          >
            Continue watching
          </h1>
          {/* Present from the first render, so the count or the offline state is announced when it changes. */}
          <p aria-atomic="true" aria-live="polite" className="text-sm text-slate-400">
            {statusText(data, offline)}
          </p>
        </div>
        <p className="text-sm text-slate-300">
          Your 50 most recently watched movies that you have not finished, newest first.
        </p>
      </div>
      {data && failed ? <RefreshFailedNotice isRetrying={isFetching} onRetry={retry} /> : null}
      {content}
    </section>
  );
}

interface ContinueWatchingGridProps {
  items: ContinueWatchingItem[];
  listRef: RefObject<HTMLUListElement>;
  onFocus(event: FocusEvent<HTMLUListElement>): void;
  onBlur(event: FocusEvent<HTMLUListElement>): void;
}

function ContinueWatchingGrid({ items, listRef, onFocus, onBlur }: ContinueWatchingGridProps) {
  return (
    <ul className={movieGridClass} onBlur={onBlur} onFocus={onFocus} ref={listRef}>
      {items.map((item) => (
        <li className="min-w-0" key={item.movie.id}>
          <ContinueWatchingCard item={item} />
        </li>
      ))}
    </ul>
  );
}

interface RefreshFailedNoticeProps {
  isRetrying: boolean;
  onRetry(): void;
}

/** A refresh failed while a list was already shown: the list stays, and the viewer can try again. */
function RefreshFailedNotice({ isRetrying, onRetry }: RefreshFailedNoticeProps) {
  return (
    <div className={NOTICE_CLASS} role="alert">
      <p className="text-sm text-amber-200">The list could not be refreshed and may be out of date.</p>
      <button className={secondaryButtonClass} disabled={isRetrying} onClick={onRetry} type="button">
        {isRetrying ? 'Retrying…' : 'Retry'}
      </button>
    </div>
  );
}

function statusText(items: ContinueWatchingItem[] | undefined, offline: boolean): string {
  if (items) {
    return items.length === 1 ? '1 movie' : `${items.length} movies`;
  }
  return offline ? 'Offline' : '';
}

function cardPaths(list: HTMLUListElement | null): Array<string | null> {
  return Array.from(list?.querySelectorAll('a') ?? [], (link) => link.getAttribute('href'));
}
