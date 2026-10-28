import { type ReactNode, useRef } from 'react';
import { Link, useParams } from 'react-router-dom';
import { secondaryButtonClass } from '../../../components/buttonStyles';
import { ErrorState, LoadingState } from '../../../components/StateViews';
import { ApiRequestError, AuthenticationSupersededError } from '../../../lib/api/client';
import { isCanonicalUuid } from '../../../lib/uuid';
import { NotFoundPage } from '../../../pages/NotFoundPage';
import { MovieDetails } from '../components/MovieDetails';
import { useMovieDetail } from '../movieQueries';

export function MovieDetailPage() {
  const movieId = (useParams().movieId ?? '').toLowerCase();
  const { data, error, isError, isFetching, refetch } = useMovieDetail(movieId);
  const pageRef = useRef<HTMLDivElement>(null);
  const failed = isError && !(error instanceof AuthenticationSupersededError);
  const notFound = !isCanonicalUuid(movieId)
    || (failed && error instanceof ApiRequestError && (error.status === 400 || error.status === 404));

  const retry = () => {
    // The Retry button can disappear while loading. Keep keyboard focus on the stable page region.
    pageRef.current?.focus({ preventScroll: true });
    void refetch();
  };

  let content: ReactNode;
  if (notFound) {
    content = (
      <NotFoundPage
        description="The movie could not be found."
        link={{ to: '/movies', label: 'Back to movies' }}
        title="Movie not found"
      />
    );
  } else {
    content = (
      <>
        <Link className={`${secondaryButtonClass} justify-self-start`} to="/movies">Back to movies</Link>
        {failed ? (
          <ErrorState
            error={error}
            headingLevel={1}
            isRetrying={isFetching}
            onRetry={retry}
            title="Movie could not be loaded"
          />
        ) : data ? (
          <MovieDetails movie={data} />
        ) : (
          <>
            <h1 className="text-3xl font-semibold text-white">Movie details</h1>
            <LoadingState label="Loading movie…"><MovieDetailSkeleton /></LoadingState>
          </>
        )}
      </>
    );
  }

  return (
    <div aria-label="Movie details" className="grid gap-6 focus:outline-none" ref={pageRef} role="region" tabIndex={-1}>
      {content}
    </div>
  );
}

function MovieDetailSkeleton() {
  return (
    <div className="grid gap-6 motion-safe:animate-pulse md:grid-cols-3">
      <div className="aspect-[2/3] w-3/5 rounded-lg bg-slate-800 md:w-full" />
      <div className="space-y-5 md:col-span-2">
        <div className="h-9 w-3/4 rounded bg-slate-800" />
        <div className="h-5 w-1/2 rounded bg-slate-800" />
        <div className="h-9 w-28 rounded bg-slate-800" />
        <div className="h-32 rounded bg-slate-800" />
      </div>
    </div>
  );
}
