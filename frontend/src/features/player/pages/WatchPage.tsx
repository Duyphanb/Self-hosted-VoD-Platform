import {
  Component,
  lazy,
  type ReactNode,
  Suspense,
  useCallback,
  useEffect,
  useMemo,
  useRef
} from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { secondaryButtonClass } from '../../../components/buttonStyles';
import { ErrorState, LoadingState } from '../../../components/StateViews';
import { ApiRequestError, AuthenticationSupersededError } from '../../../lib/api/client';
import { isCanonicalUuid } from '../../../lib/uuid';
import { NotFoundPage } from '../../../pages/NotFoundPage';
import { useApiClient, useAuth } from '../../auth/AuthContext';
import { videoAvailability } from '../../movies/movieAvailability';
import { getWatchedTitle, movieKeys, type MovieSummary } from '../../movies/moviesApi';
import { fetchPlaybackInfo, type PlaybackInfo } from '../playbackApi';
import { usePlaybackInfo } from '../playbackQueries';

// hls.js is large; the player loads only when a movie is about to play.
const HlsPlayer = lazy(() => import('../HlsPlayer').then((module) => ({ default: module.HlsPlayer })));

type UnavailableLabel = 'Processing' | 'Not available';

type WatchPageState =
  | { kind: 'notFound' }
  | { kind: 'loading' }
  | { kind: 'error'; error: unknown }
  | { kind: 'notReady'; movie: MovieSummary; label: UnavailableLabel }
  | { kind: 'ready'; movie: MovieSummary; playbackInfo: PlaybackInfo };

/** Whether each query has answered since this visit started, as opposed to holding an older cached answer. */
interface FetchedOnThisVisit {
  movie: boolean;
  playback: boolean;
}

interface QueryResult<T> {
  data: T | undefined;
  error: unknown;
  isError: boolean;
}

/**
 * The watch page (Issue 6.7): the movie's title, the authenticated player at the resume position, and the
 * description. It maps the playback API per ADR-011 D7: 404 EPISODE_NOT_FOUND shows the not-found state and
 * 409 VIDEO_NOT_READY shows the viewer-facing availability label, never a failure code or message. Any
 * other error shows the error state with Retry.
 */
export function WatchPage() {
  const episodeId = (useParams().episodeId ?? '').toLowerCase();
  // Subscribing to the session re-renders the page when the session changes, even for the same viewer:
  // the cleared queries run again and the new identity gets a new player.
  useAuth();
  const apiClient = useApiClient();
  const movie = useQuery({
    queryKey: movieKeys.watchedTitle(episodeId),
    queryFn: ({ signal }) => getWatchedTitle(apiClient, episodeId, signal),
    enabled: isCanonicalUuid(episodeId),
    refetchOnMount: 'always'
  });
  const playback = usePlaybackInfo(episodeId);
  const pageRef = useRef<HTMLDivElement>(null);
  // Whether this visit already used its one automatic recheck of a 409; each new movie ID is a new visit.
  const recheckUsed = useRef(false);
  const state = watchPageState(episodeId, movie, playback, {
    movie: movie.isFetchedAfterMount,
    playback: playback.isFetchedAfterMount
  });

  // The player's own requests belong to the viewer it was mounted for: a new identity gets a new player.
  const currentIdentity = apiClient.getAuthenticationSnapshot();
  const identityKey = currentIdentity ? `${currentIdentity.userId}:${currentIdentity.generation}` : 'signed-out';
  // eslint-disable-next-line react-hooks/exhaustive-deps -- the snapshot is captured once per identity.
  const playerIdentity = useMemo(() => apiClient.getAuthenticationSnapshot(), [apiClient, identityKey]);
  const reloadPlaybackInfo = useCallback(() => {
    if (!playerIdentity) {
      return Promise.reject(new AuthenticationSupersededError());
    }
    return fetchPlaybackInfo(apiClient, episodeId, undefined, playerIdentity);
  }, [apiClient, episodeId, playerIdentity]);

  // Route change: focus moves from the link that opened this page to the page itself.
  useEffect(() => {
    pageRef.current?.focus({ preventScroll: true });
  }, [episodeId]);

  // A new visit, also a return to a movie opened earlier, gets its own recheck. This effect runs before
  // the recheck effect below, in the same commit.
  useEffect(() => {
    recheckUsed.current = false;
  }, [episodeId]);

  // A 409 that raced the end of encoding: the movie already reports READY, so ask once more.
  const notReadyButMovieReady = state.kind === 'notReady' && state.movie.videoStatus === 'READY';
  useEffect(() => {
    if (notReadyButMovieReady && !recheckUsed.current) {
      recheckUsed.current = true;
      void playback.refetch();
    }
  }, [notReadyButMovieReady, playback]);

  const retry = () => {
    // The Retry button disappears while loading; keep keyboard focus on the stable page region.
    pageRef.current?.focus({ preventScroll: true });
    if (movie.isError) {
      void movie.refetch();
    }
    if (playback.isError) {
      void playback.refetch();
    }
  };

  const checkAgain = () => {
    pageRef.current?.focus({ preventScroll: true });
    void movie.refetch();
    void playback.refetch();
  };

  const retrying = (movie.isError && movie.isFetching) || (playback.isError && playback.isFetching);
  const announcement = state.kind === 'notReady' ? state.label : '';

  let content: ReactNode;
  if (state.kind === 'notFound') {
    content = (
      <NotFoundPage
        description="The movie could not be found."
        link={{ to: '/titles', label: 'Back to movies' }}
        title="Movie not found"
      />
    );
  } else if (state.kind === 'loading') {
    content = (
      <>
        <BackLink to="/titles">Back to movies</BackLink>
        <h1 className="text-3xl font-semibold text-white">Watch</h1>
        <LoadingState label="Loading movie…" />
      </>
    );
  } else if (state.kind === 'error') {
    content = (
      <>
        <BackLink to="/titles">Back to movies</BackLink>
        <ErrorState
          error={state.error}
          headingLevel={1}
          isRetrying={retrying}
          onRetry={retry}
          title="Movie could not be loaded"
        />
      </>
    );
  } else if (state.kind === 'notReady') {
    content = (
      <>
        <BackLink to={`/titles/${state.movie.id}`}>Back to movie details</BackLink>
        <MovieTitle movie={state.movie} />
        {/* Only a playback request started by the page or the viewer disables the button, never a reconnect. */}
        <UnavailableNotice
          checking={playback.isFetching}
          label={state.label}
          onCheckAgain={checkAgain}
        />
        <MovieDescription movie={state.movie} />
      </>
    );
  } else {
    content = (
      <>
        <BackLink to={`/titles/${state.movie.id}`}>Back to movie details</BackLink>
        <MovieTitle movie={state.movie} />
        <PlayerLoadBoundary>
          <Suspense fallback={<LoadingState label="Loading player…" />}>
            <HlsPlayer
              key={`${episodeId}:${identityKey}`}
              episodeId={episodeId}
              playbackInfo={state.playbackInfo}
              reloadPlaybackInfo={reloadPlaybackInfo}
              title={state.movie.title}
            />
          </Suspense>
        </PlayerLoadBoundary>
        <MovieDescription movie={state.movie} />
      </>
    );
  }

  return (
    <div
      aria-label="Watch movie"
      className="grid min-w-0 grid-cols-1 gap-6 focus:outline-none focus-visible:ring-2 focus-visible:ring-sky-400"
      ref={pageRef}
      role="region"
      tabIndex={-1}
    >
      {/* Present from the first render, so an availability result is announced when it arrives. */}
      <p className="sr-only" role="status">{announcement}</p>
      {content}
    </div>
  );
}

/**
 * The page state from the route and both queries. A query whose session was replaced is treated as still
 * loading: the cache is cleared and the queries run again for the new identity. Once the movie and its
 * playback info are both known, only a not-found answer takes the player away: a failed background refetch
 * must not stop playback that works. The player mounts only with playback info fetched on this visit: a
 * cached answer of an earlier visit holds an older resume position, and the player keeps the position it
 * mounted with.
 */
function watchPageState(
  episodeId: string,
  movie: QueryResult<MovieSummary>,
  playback: QueryResult<PlaybackInfo>,
  fetchedOnThisVisit: FetchedOnThisVisit
): WatchPageState {
  if (!isCanonicalUuid(episodeId)) {
    return { kind: 'notFound' };
  }
  const movieError = realError(movie);
  const playbackError = realError(playback);
  if (isMovieNotFound(movieError) || isMovieNotFound(playbackError)) {
    return { kind: 'notFound' };
  }
  if (movie.data && playback.data && !playback.isError && fetchedOnThisVisit.playback) {
    return { kind: 'ready', movie: movie.data, playbackInfo: playback.data };
  }
  if (movieError) {
    return { kind: 'error', error: movieError };
  }
  if (isVideoNotReady(playbackError)) {
    // The label comes from a movie answer fetched on this visit, never from an older cached one. A later
    // refresh, for example after a reconnect, keeps the label until its answer arrives.
    return movie.data && fetchedOnThisVisit.movie
      ? { kind: 'notReady', movie: movie.data, label: unavailableLabel(movie.data) }
      : { kind: 'loading' };
  }
  if (playbackError) {
    return { kind: 'error', error: playbackError };
  }
  return { kind: 'loading' };
}

function realError(query: QueryResult<unknown>): unknown {
  if (!query.isError || query.error instanceof AuthenticationSupersededError) {
    return null;
  }
  return query.error;
}

function isMovieNotFound(error: unknown): boolean {
  return error instanceof ApiRequestError && error.status === 404 && error.apiError?.code === 'EPISODE_NOT_FOUND';
}

function isVideoNotReady(error: unknown): boolean {
  return error instanceof ApiRequestError && error.status === 409 && error.apiError?.code === 'VIDEO_NOT_READY';
}

/** The viewer-facing label of a movie without a playable asset (ADR-011 D7, D18). */
function unavailableLabel(movie: MovieSummary): UnavailableLabel {
  return videoAvailability(movie.videoStatus).label === 'Processing' ? 'Processing' : 'Not available';
}

function MovieTitle({ movie }: { movie: MovieSummary }) {
  return <h1 className="break-words text-3xl font-semibold text-white [overflow-wrap:anywhere]">{movie.title}</h1>;
}

function MovieDescription({ movie }: { movie: MovieSummary }) {
  return (
    <section aria-labelledby="watch-description-heading" className="min-w-0">
      <h2 className="text-lg font-semibold text-white" id="watch-description-heading">Description</h2>
      <p className="mt-2 whitespace-pre-line break-words text-slate-300">{movie.description}</p>
    </section>
  );
}

interface UnavailableNoticeProps {
  label: UnavailableLabel;
  checking: boolean;
  onCheckAgain(): void;
}

function UnavailableNotice({ label, checking, onCheckAgain }: UnavailableNoticeProps) {
  const message = label === 'Processing'
    ? 'This movie is still being processed. Check back later.'
    : 'This movie cannot be watched right now.';
  return (
    <section
      aria-labelledby="watch-availability-heading"
      className="rounded-lg border border-slate-700 bg-slate-900/60 p-6"
    >
      <h2 className="text-lg font-semibold text-white" id="watch-availability-heading">{label}</h2>
      <p className="mt-2 text-sm text-slate-300">{message}</p>
      <button className={`${secondaryButtonClass} mt-4`} disabled={checking} onClick={onCheckAgain} type="button">
        {checking ? 'Checking…' : 'Check again'}
      </button>
    </section>
  );
}

function BackLink({ to, children }: { to: string; children: ReactNode }) {
  return <Link className={`${secondaryButtonClass} justify-self-start`} to={to}>{children}</Link>;
}

interface PlayerLoadBoundaryState {
  failed: boolean;
}

/**
 * The player's code could not be loaded, for example in a tab opened before a deployment replaced the
 * player chunk. The page stays; reloading it fetches the current code.
 */
class PlayerLoadBoundary extends Component<{ children: ReactNode }, PlayerLoadBoundaryState> {
  state: PlayerLoadBoundaryState = { failed: false };

  static getDerivedStateFromError(): PlayerLoadBoundaryState {
    return { failed: true };
  }

  render() {
    if (!this.state.failed) {
      return this.props.children;
    }
    return (
      <div className="rounded-lg border border-rose-500/50 bg-rose-500/10 p-6" role="alert">
        <p className="text-sm text-rose-100">The player could not be loaded.</p>
        <button
          className={`${secondaryButtonClass} mt-4`}
          onClick={() => window.location.reload()}
          type="button"
        >
          Reload page
        </button>
      </div>
    );
  }
}
