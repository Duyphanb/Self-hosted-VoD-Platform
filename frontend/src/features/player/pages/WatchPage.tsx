import { lazy, type ReactNode, Suspense, useCallback, useRef } from 'react';
import { Link, useParams } from 'react-router-dom';
import { secondaryButtonClass } from '../../../components/buttonStyles';
import { ErrorState, LoadingState } from '../../../components/StateViews';
import { ApiRequestError, AuthenticationSupersededError } from '../../../lib/api/client';
import { isCanonicalUuid } from '../../../lib/uuid';
import { NotFoundPage } from '../../../pages/NotFoundPage';
import { useApiClient } from '../../auth/AuthContext';
import { videoAvailability } from '../../movies/movieAvailability';
import { useMovieDetail } from '../../movies/movieQueries';
import type { MovieDetail } from '../../movies/moviesApi';
import { fetchPlaybackInfo, type PlaybackInfo } from '../playbackApi';
import { usePlaybackInfo } from '../playbackQueries';

// hls.js is large; the player loads only when a movie is about to play.
const HlsPlayer = lazy(() => import('../HlsPlayer').then((module) => ({ default: module.HlsPlayer })));

type UnavailableLabel = 'Processing' | 'Not available';

type WatchPageState =
  | { kind: 'notFound' }
  | { kind: 'loading' }
  | { kind: 'error'; error: unknown }
  | { kind: 'notReady'; movie: MovieDetail; label: UnavailableLabel }
  | { kind: 'ready'; movie: MovieDetail; playbackInfo: PlaybackInfo };

interface QueryResult<T> {
  data: T | undefined;
  error: unknown;
  isError: boolean;
}

/**
 * The watch page (Issue 6.7): the movie's title, the authenticated player at the resume position, and the
 * description. It maps the playback API per ADR-011 D7: 404 MOVIE_NOT_FOUND shows the not-found state and
 * 409 VIDEO_NOT_READY shows the viewer-facing availability label, never a failure code or message.
 */
export function WatchPage() {
  const movieId = (useParams().movieId ?? '').toLowerCase();
  const apiClient = useApiClient();
  const movie = useMovieDetail(movieId);
  const playback = usePlaybackInfo(movieId);
  const pageRef = useRef<HTMLDivElement>(null);
  // The player asks again on its own when its asset is no longer current; the page's answer stays as it is.
  const reloadPlaybackInfo = useCallback(() => fetchPlaybackInfo(apiClient, movieId), [apiClient, movieId]);
  const state = watchPageState(movieId, movie, playback);

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

  let content: ReactNode;
  if (state.kind === 'notFound') {
    content = (
      <NotFoundPage
        description="The movie could not be found."
        link={{ to: '/movies', label: 'Back to movies' }}
        title="Movie not found"
      />
    );
  } else if (state.kind === 'loading') {
    content = (
      <>
        <BackLink to="/movies">Back to movies</BackLink>
        <h1 className="text-3xl font-semibold text-white">Watch</h1>
        <LoadingState label="Loading movie…" />
      </>
    );
  } else if (state.kind === 'error') {
    content = (
      <>
        <BackLink to="/movies">Back to movies</BackLink>
        <ErrorState
          error={state.error}
          headingLevel={1}
          isRetrying={movie.isFetching || playback.isFetching}
          onRetry={retry}
          title="Movie could not be loaded"
        />
      </>
    );
  } else if (state.kind === 'notReady') {
    content = (
      <>
        <BackLink to={`/movies/${state.movie.id}`}>Back to movie details</BackLink>
        <h1 className="text-3xl font-semibold text-white">{state.movie.title}</h1>
        <UnavailableNotice label={state.label} />
      </>
    );
  } else {
    content = (
      <>
        <BackLink to={`/movies/${state.movie.id}`}>Back to movie details</BackLink>
        <h1 className="text-3xl font-semibold text-white">{state.movie.title}</h1>
        <Suspense fallback={<LoadingState label="Loading player…" />}>
          <HlsPlayer
            key={movieId}
            movieId={movieId}
            playbackInfo={state.playbackInfo}
            reloadPlaybackInfo={reloadPlaybackInfo}
            title={state.movie.title}
          />
        </Suspense>
        <section aria-labelledby="watch-description-heading">
          <h2 className="text-lg font-semibold text-white" id="watch-description-heading">Description</h2>
          <p className="mt-2 whitespace-pre-line text-slate-300">{state.movie.description}</p>
        </section>
      </>
    );
  }

  return (
    <div aria-label="Watch movie" className="grid gap-6 focus:outline-none" ref={pageRef} role="region" tabIndex={-1}>
      {content}
    </div>
  );
}

/**
 * The page state from the route and both queries. A query whose session was replaced is treated as still
 * loading: the cache is cleared and the queries run again for the new identity.
 */
function watchPageState(
  movieId: string,
  movie: QueryResult<MovieDetail>,
  playback: QueryResult<PlaybackInfo>
): WatchPageState {
  if (!isCanonicalUuid(movieId)) {
    return { kind: 'notFound' };
  }
  const movieError = realError(movie);
  const playbackError = realError(playback);
  if (isNotFound(movieError) || isNotFound(playbackError)) {
    return { kind: 'notFound' };
  }
  if (movieError) {
    return { kind: 'error', error: movieError };
  }
  if (isNotReady(playbackError)) {
    return movie.data
      ? { kind: 'notReady', movie: movie.data, label: unavailableLabel(movie.data) }
      : { kind: 'loading' };
  }
  if (playbackError) {
    return { kind: 'error', error: playbackError };
  }
  if (movie.data && playback.data) {
    return { kind: 'ready', movie: movie.data, playbackInfo: playback.data };
  }
  return { kind: 'loading' };
}

function realError(query: QueryResult<unknown>): unknown {
  if (!query.isError || query.error instanceof AuthenticationSupersededError) {
    return null;
  }
  return query.error;
}

function isNotFound(error: unknown): boolean {
  return error instanceof ApiRequestError && (error.status === 400 || error.status === 404);
}

function isNotReady(error: unknown): boolean {
  return error instanceof ApiRequestError && error.status === 409;
}

/** The viewer-facing label of a movie without a playable asset (ADR-011 D7, D18). */
function unavailableLabel(movie: MovieDetail): UnavailableLabel {
  return videoAvailability(movie.videoStatus).label === 'Processing' ? 'Processing' : 'Not available';
}

function UnavailableNotice({ label }: { label: UnavailableLabel }) {
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
    </section>
  );
}

function BackLink({ to, children }: { to: string; children: ReactNode }) {
  return <Link className={`${secondaryButtonClass} justify-self-start`} to={to}>{children}</Link>;
}
