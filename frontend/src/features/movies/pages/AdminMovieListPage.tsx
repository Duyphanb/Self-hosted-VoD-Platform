import { type ReactNode, useEffect, useMemo, useRef, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { primaryButtonClass, secondaryButtonClass } from '../../../components/buttonStyles';
import { Pagination } from '../../../components/Pagination';
import { EmptyState, ErrorState, LoadingState } from '../../../components/StateViews';
import { ApiRequestError, AuthenticationSupersededError } from '../../../lib/api/client';
import { ForbiddenPage } from '../../../pages/AccessPages';
import { adminMovieListSearchParams, clearRejectedAdminFilters, parseAdminMovieListParams, toAdminMovieListQuery } from '../adminMovieListParams';
import { useAdminMovies } from '../adminMovieQueries';
import { ArchiveMovieDialog } from '../components/ArchiveMovieDialog';
import { MovieStatusBadge, VideoStatusBadge } from '../components/MovieStatusBadges';
import { type MovieStatus, type MovieSummary } from '../moviesApi';

type Notice = { text: string; entryKey: string | null } | null;
const STATUS_FILTERS: ReadonlyArray<{ label: string; value: MovieStatus | null }> = [
  { label: 'All', value: null }, { label: 'Draft', value: 'DRAFT' },
  { label: 'Published', value: 'PUBLISHED' }, { label: 'Archived', value: 'ARCHIVED' }
];

export function AdminMovieListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const params = useMemo(() => parseAdminMovieListParams(searchParams), [searchParams]);
  const query = useAdminMovies(toAdminMovieListQuery(params));
  const location = useLocation();
  const navigate = useNavigate();
  const headingRef = useRef<HTMLHeadingElement>(null);
  const incomingMessage = savedMessage(location.state);
  const [notice, setNotice] = useState<Notice>(() => incomingMessage ? { text: incomingMessage, entryKey: null } : null);
  const [statusText, setStatusText] = useState('');
  const [noticeLocationKey, setNoticeLocationKey] = useState(location.key);
  const [selected, setSelected] = useState<{ movie: MovieSummary; trigger: HTMLElement } | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const returnTo = `${location.pathname}${location.search}`;
  const failed = query.isError && !(query.error instanceof AuthenticationSupersededError);
  const rejected = failed && query.error instanceof ApiRequestError && query.error.status === 400
    ? clearRejectedAdminFilters(params, query.error.apiError?.fieldErrors?.map(({ field }) => field) ?? [])
    : null;

  if (location.key !== noticeLocationKey) {
    setNoticeLocationKey(location.key);
    setNotice(notice?.entryKey === null ? { ...notice, entryKey: location.key } : null);
    setStatusText('');
  }

  // The region is present before its text changes. Clearing first also announces repeated messages.
  useEffect(() => {
    const timer = window.setTimeout(() => setStatusText(notice?.text ?? ''), 0);
    return () => window.clearTimeout(timer);
  }, [notice]);

  // Consume navigation feedback so Back/Forward or reloading cannot replay it.
  useEffect(() => {
    if (incomingMessage) {
      headingRef.current?.focus({ preventScroll: true });
      navigate({ pathname: location.pathname, search: location.search, hash: location.hash }, { replace: true, state: null });
    }
  }, [incomingMessage, location.hash, location.pathname, location.search, navigate]);

  if (forbidden || (failed && query.error instanceof ApiRequestError && query.error.status === 403)) {
    return <ForbiddenPage />;
  }

  const focusHeading = () => headingRef.current?.focus({ preventScroll: true });
  const changePage = (page: number, replace = false) => {
    setSearchParams(adminMovieListSearchParams({ ...params, page }), { replace });
    focusHeading();
  };
  const changeStatus = (status: MovieStatus | null) => {
    setSearchParams(adminMovieListSearchParams({ ...params, status, page: 1 }), { replace: true });
  };

  let content: ReactNode;
  if (rejected) {
    content = <Navigate replace to={{ search: `?${adminMovieListSearchParams(rejected).toString()}` }} />;
  } else if (failed) {
    content = <ErrorState error={query.error} isRetrying={query.isFetching} onRetry={() => { focusHeading(); void query.refetch(); }} title="Movies could not be loaded" />;
  } else if (!query.data) {
    content = <LoadingState label="Loading admin movies…" />;
  } else if (query.data.items.length > 0) {
    content = (
      <div aria-busy={query.isPlaceholderData} className={`grid gap-6 ${query.isPlaceholderData ? 'opacity-60' : ''}`}>
        <table className="block w-full border-separate border-spacing-0 text-left text-sm md:table">
          <caption className="sr-only">Movies available for administration</caption>
          <thead className="sr-only md:not-sr-only md:table-header-group">
            <tr>{['Title', 'Year', 'Genres', 'Status', 'Video', 'Actions'].map((label) => <th className="border-b border-slate-700 p-3" key={label} scope="col">{label}</th>)}</tr>
          </thead>
          <tbody className="grid gap-4 md:table-row-group">
            {query.data.items.map((movie) => (
              <tr className="grid gap-3 rounded-lg border border-slate-700 bg-slate-900 p-4 md:table-row" key={movie.id}>
                <td className="min-w-0 break-words md:p-3"><Link className="font-semibold text-sky-300 underline" state={{ returnTo }} to={`/admin/movies/${movie.id}/edit`}>{movie.title}</Link></td>
                <td className="md:p-3"><span className="text-slate-400 md:hidden">Year: </span>{movie.releaseYear ?? 'Not specified'}</td>
                <td className="break-words md:p-3"><span className="text-slate-400 md:hidden">Genres: </span>{movie.genres.join(' · ') || 'No genres listed'}</td>
                <td className="md:p-3"><span className="text-slate-400 md:hidden">Status: </span><MovieStatusBadge status={movie.status} /></td>
                <td className="md:p-3"><span className="text-slate-400 md:hidden">Video: </span><VideoStatusBadge status={movie.videoStatus} /></td>
                <td className="md:p-3">
                  <div className="flex flex-wrap items-center gap-3">
                    <Link aria-label={`View ${movie.title}`} className="text-sky-300 underline" to={`/movies/${movie.id}`}>View</Link>
                    <Link aria-label={`Edit ${movie.title}`} className="text-sky-300 underline" state={{ returnTo }} to={`/admin/movies/${movie.id}/edit`}>Edit</Link>
                    {movie.status !== 'ARCHIVED' ? (
                      <button
                        aria-label={`Archive ${movie.title}`}
                        className="text-rose-300 underline disabled:opacity-50"
                        disabled={query.isPlaceholderData}
                        onClick={(event) => {
                          const trigger = event.currentTarget;
                          setSelected((current) => current ?? { movie, trigger });
                        }}
                        type="button"
                      >Archive</button>
                    ) : null}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <Pagination onPageChange={changePage} page={params.page} totalPages={query.data.page.totalPages} />
      </div>
    );
  } else if (query.data.page.totalElements > 0) {
    content = <EmptyState description={`This list ends at page ${query.data.page.totalPages}.`} title="This page doesn't exist"><button className={secondaryButtonClass} onClick={() => changePage(query.data.page.totalPages, true)} type="button">Go to last page</button></EmptyState>;
  } else if (params.status !== null) {
    content = <EmptyState description="Choose another status or create a movie." title="No movies match this status"><button className={secondaryButtonClass} onClick={() => { changeStatus(null); focusHeading(); }} type="button">Show all movies</button></EmptyState>;
  } else {
    content = <EmptyState description="Create a movie to get started." title="No movies yet" />;
  }

  return (
    <section aria-labelledby="admin-movies-heading" className="grid gap-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="text-3xl font-semibold text-white focus:outline-none" id="admin-movies-heading" ref={headingRef} tabIndex={-1}>Movies</h1>
        <Link className={primaryButtonClass} state={{ returnTo }} to="/admin/movies/new">New movie</Link>
      </div>
      <p aria-atomic="true" aria-label="Movie updates" className={notice ? 'rounded-md border border-emerald-700 bg-emerald-950/50 p-3 text-sm text-emerald-200' : 'sr-only'} role="status">{statusText}</p>
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div aria-label="Movie status" className="flex flex-wrap gap-2" role="group">
          {STATUS_FILTERS.map(({ label, value }) => <button aria-pressed={params.status === value} className={secondaryButtonClass} key={label} onClick={() => changeStatus(value)} type="button">{label}</button>)}
        </div>
        <label className="text-sm text-slate-300">Sort by{' '}<select className="rounded-md border border-slate-600 bg-slate-900 p-2 text-white" onChange={(event) => setSearchParams(adminMovieListSearchParams({ ...params, sort: event.target.value === 'title' ? 'title' : 'latest', page: 1 }), { replace: true })} value={params.sort}><option value="latest">Latest</option><option value="title">Title</option></select></label>
      </div>
      {content}
      {selected ? <ArchiveMovieDialog key={selected.movie.id} movie={selected.movie} onArchived={(message) => { setStatusText(''); setNotice({ text: message, entryKey: location.key }); setSelected(null); }} onClose={() => setSelected(null)} onForbidden={() => { setSelected(null); setForbidden(true); }} returnFocusTo={selected.trigger} /> : null}
    </section>
  );
}

function savedMessage(state: unknown): string | null {
  if (typeof state !== 'object' || state === null || !('message' in state)) return null;
  return state.message === 'Movie created' || state.message === 'Changes saved' ? state.message : null;
}
