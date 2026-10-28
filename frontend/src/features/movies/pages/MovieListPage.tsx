import { type ReactNode, useMemo, useRef, useState } from 'react';
import { Navigate, useLocation, useSearchParams } from 'react-router-dom';
import { secondaryButtonClass } from '../../../components/buttonStyles';
import { Pagination } from '../../../components/Pagination';
import { EmptyState, ErrorState, LoadingState } from '../../../components/StateViews';
import { ApiRequestError, AuthenticationSupersededError } from '../../../lib/api/client';
import type { PageMeta } from '../../../lib/api/types';
import { MovieFilters } from '../components/MovieFilters';
import { MovieGrid, MovieGridSkeleton } from '../components/MovieGrid';
import {
  clearRejectedFilters,
  hasActiveFilters,
  type MovieListFilters,
  type MovieListParams,
  movieListSearchParams,
  parseMovieListParams,
  toMovieListQuery
} from '../movieListParams';
import { useMovieList } from '../movieQueries';

/**
 * Notice that the API rejected the filters, kept in component state rather than history state so
 * Back, Forward or a reload never show it again. `entryKey` is null until the navigation that clears
 * the filters lands, then the key of the history entry it created; any other entry drops the notice.
 */
type FiltersRejectedNotice = { entryKey: string | null } | null;

/** Published movies. The URL holds genre, year, sort and the 1-based page, so reload keeps them. */
export function MovieListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const location = useLocation();
  const headingRef = useRef<HTMLHeadingElement>(null);
  const params = useMemo(() => parseMovieListParams(searchParams), [searchParams]);
  const { data, error, isError, isFetching, isPlaceholderData, refetch } = useMovieList(toMovieListQuery(params));
  const [notice, setNotice] = useState<FiltersRejectedNotice>(null);
  const [noticeLocationKey, setNoticeLocationKey] = useState(location.key);
  // Changing the key remounts the filter form, dropping every typed value and error with it.
  const [filtersFormKey, setFiltersFormKey] = useState(0);

  // Adjusting state during render (https://react.dev/learn/you-might-not-need-an-effect).
  if (location.key !== noticeLocationKey) {
    setNoticeLocationKey(location.key);
    setNotice(notice?.entryKey === null ? { entryKey: location.key } : null);
  }

  // A request cancelled by a sign-in change is not an error. AuthProvider runs it again once the
  // new session is in place, and a different user remounts this page.
  const failed = isError && !(error instanceof AuthenticationSupersededError);
  const paramsAfterRejection = failed ? filtersAfterRejection(params, error) : null;
  if (paramsAfterRejection && notice?.entryKey !== null) {
    setNotice({ entryKey: null });
  }

  const focusListHeading = () => {
    const heading = headingRef.current;
    heading?.focus({ preventScroll: true });
    heading?.scrollIntoView({ block: 'start' });
  };

  // A filter change starts again at page 1 and replaces the history entry.
  const changeFilters = (filters: MovieListFilters) => {
    setSearchParams(movieListSearchParams({ ...filters, page: 1 }), { replace: true });
  };

  // A page change adds a history entry and brings the list heading into view.
  const changePage = (page: number) => {
    setSearchParams(movieListSearchParams({ ...params, page }));
    focusListHeading();
  };

  // Replaces the entry instead of adding one: Back should not return to a page that does not exist.
  const goToLastPage = (lastPage: number) => {
    setSearchParams(movieListSearchParams({ ...params, page: lastPage }), { replace: true });
    focusListHeading();
  };

  // Clears what the toolbar shows as well, including a value typed but never applied. The button
  // disappears with the empty result, so focus moves to the list heading.
  const clearFiltersFromEmptyResult = () => {
    changeFilters({ genre: null, year: null, sort: params.sort });
    setFiltersFormKey((key) => key + 1);
    focusListHeading();
  };

  // Retry swaps the error for the loading state, so focus moves to the list heading.
  const retry = () => {
    focusListHeading();
    void refetch();
  };

  let content: ReactNode;
  if (paramsAfterRejection) {
    content = <Navigate replace to={{ search: `?${movieListSearchParams(paramsAfterRejection).toString()}` }} />;
  } else if (failed) {
    content = (
      <ErrorState
        error={error}
        isRetrying={isFetching}
        onRetry={retry}
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
        <button className={secondaryButtonClass} onClick={() => goToLastPage(lastPage)} type="button">
          Go to last page
        </button>
      </EmptyState>
    );
  } else if (hasActiveFilters(params)) {
    content = (
      <EmptyState description="Try another genre or year." title="No movies match these filters">
        <button className={secondaryButtonClass} onClick={clearFiltersFromEmptyResult} type="button">
          Clear filters
        </button>
      </EmptyState>
    );
  } else {
    content = <EmptyState description="Published movies will appear here." title="No movies yet" />;
  }

  const resultSummary = data && !failed && !isPlaceholderData
    ? resultSummaryText(data.page, data.items.length)
    : '';

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
          {resultSummary}
        </p>
      </div>
      {notice !== null && notice.entryKey === location.key ? (
        <p
          className="rounded-md border border-amber-600/60 bg-amber-950/50 p-3 text-sm text-amber-200"
          role="alert"
        >
          The server did not accept those filters, so they were cleared.
        </p>
      ) : null}
      <MovieFilters filters={params} key={filtersFormKey} onFiltersChange={changeFilters} />
      {content}
    </section>
  );
}

/** The URL state that clears the filters a 400 rejected, or null when the error must be shown. */
function filtersAfterRejection(params: MovieListParams, error: unknown): MovieListParams | null {
  if (!(error instanceof ApiRequestError) || error.status !== 400) {
    return null;
  }
  const rejectedFields = (error.apiError?.fieldErrors ?? []).map((fieldError) => fieldError.field);
  return clearRejectedFilters(params, rejectedFields);
}

/** "N movies, page X of Y" for a loaded page; nothing for a page beyond the end of the list. */
function resultSummaryText(page: PageMeta, itemCount: number): string {
  if (itemCount === 0) {
    return page.totalElements === 0 ? '0 movies' : '';
  }
  const movies = page.totalElements === 1 ? '1 movie' : `${page.totalElements} movies`;
  return `${movies}, page ${page.page + 1} of ${page.totalPages}`;
}
