import { type ReactNode, useCallback, useMemo, useRef } from 'react';
import { Navigate, useLocation, useSearchParams } from 'react-router-dom';
import { secondaryButtonClass } from '../../../components/buttonStyles';
import { Pagination } from '../../../components/Pagination';
import { EmptyState, ErrorState, LoadingState } from '../../../components/StateViews';
import { ApiRequestError, AuthenticationSupersededError } from '../../../lib/api/client';
import { MovieFilters } from '../components/MovieFilters';
import { MovieGrid, MovieGridSkeleton } from '../components/MovieGrid';
import {
  hasActiveFilters,
  type MovieListFilters,
  movieListSearchParams,
  parseMovieListParams,
  toMovieListQuery
} from '../movieListParams';
import { useMovieList } from '../movieQueries';

// History state of the navigation that clears filters the API rejected with 400.
const FILTERS_REJECTED_STATE = { movieFiltersRejected: true } as const;

/** Published movies. The URL holds genre, year, sort and the 1-based page, so reload keeps them. */
export function MovieListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const location = useLocation();
  const headingRef = useRef<HTMLHeadingElement>(null);
  const params = useMemo(() => parseMovieListParams(searchParams), [searchParams]);
  const {
    data: queryData,
    error,
    isError,
    isFetching,
    isPlaceholderData,
    refetch
  } = useMovieList(toMovieListQuery(params));
  // Previous movies may stay on screen while the next page loads, but a previous empty result
  // would describe the old filters, so the skeleton shows instead.
  const data = isPlaceholderData && queryData?.items.length === 0 ? undefined : queryData;

  // A filter change starts again at page 1 and replaces the history entry.
  const changeFilters = useCallback((filters: MovieListFilters) => {
    setSearchParams(movieListSearchParams({ ...filters, page: 1 }), { replace: true });
  }, [setSearchParams]);

  // A page change adds a history entry and brings the list heading into view.
  const changePage = (page: number) => {
    setSearchParams(movieListSearchParams({ ...params, page }));
    const heading = headingRef.current;
    heading?.focus({ preventScroll: true });
    heading?.scrollIntoView({ block: 'start' });
  };

  const clearFilters = () => changeFilters({ genre: null, year: null, sort: params.sort });

  // A request cancelled by a sign-in change is not an error: the page is replaced or refetches.
  const failed = isError && !(error instanceof AuthenticationSupersededError);
  const filtersRejected = failed
    && error instanceof ApiRequestError
    && error.status === 400
    && hasActiveFilters(params);
  const resultCount = data && !failed && !isPlaceholderData
    ? movieCountText(data.page.totalElements)
    : '';

  let content: ReactNode;
  if (filtersRejected) {
    const search = movieListSearchParams({ ...params, genre: null, year: null, page: 1 });
    content = <Navigate replace state={FILTERS_REJECTED_STATE} to={{ search: `?${search.toString()}` }} />;
  } else if (failed) {
    content = (
      <ErrorState
        error={error}
        isRetrying={isFetching}
        onRetry={() => void refetch()}
        title="Movies could not be loaded"
      />
    );
  } else if (!data) {
    content = (
      <LoadingState label="Loading movies…">
        <MovieGridSkeleton />
      </LoadingState>
    );
  } else if (data.items.length > 0) {
    content = (
      <div
        aria-busy={isPlaceholderData}
        className={`grid gap-8 transition-opacity motion-reduce:transition-none ${isPlaceholderData ? 'opacity-60' : ''}`}
      >
        <MovieGrid movies={data.items} />
        <Pagination onPageChange={changePage} page={params.page} totalPages={data.page.totalPages} />
      </div>
    );
  } else if (data.page.totalElements > 0) {
    const lastPage = data.page.totalPages;
    content = (
      <EmptyState description={`This list ends at page ${lastPage}.`} title="This page doesn't exist">
        <button
          className={secondaryButtonClass}
          onClick={() => setSearchParams(
            movieListSearchParams({ ...params, page: lastPage }),
            { replace: true }
          )}
          type="button"
        >
          Go to last page
        </button>
      </EmptyState>
    );
  } else if (hasActiveFilters(params)) {
    content = (
      <EmptyState description="Try another genre or year." title="No movies match these filters">
        <button className={secondaryButtonClass} onClick={clearFilters} type="button">
          Clear filters
        </button>
      </EmptyState>
    );
  } else {
    content = <EmptyState description="Published movies will appear here." title="No movies yet" />;
  }

  return (
    <section aria-labelledby="movie-list-heading" className="grid gap-6">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <h1
          className="text-3xl font-semibold text-white focus:outline-none"
          id="movie-list-heading"
          ref={headingRef}
          tabIndex={-1}
        >
          Movies
        </h1>
        <p aria-atomic="true" aria-live="polite" className="text-sm text-slate-400">
          {resultCount}
        </p>
      </div>
      {isFiltersRejectedState(location.state) ? (
        <p
          className="rounded-md border border-amber-600/60 bg-amber-950/50 p-3 text-sm text-amber-200"
          role="alert"
        >
          The server did not accept those filters, so they were cleared.
        </p>
      ) : null}
      <MovieFilters filters={params} onFiltersChange={changeFilters} />
      {content}
    </section>
  );
}

function movieCountText(count: number): string {
  return count === 1 ? '1 movie' : `${count} movies`;
}

function isFiltersRejectedState(state: unknown): boolean {
  return typeof state === 'object'
    && state !== null
    && (state as Record<string, unknown>).movieFiltersRejected === true;
}
