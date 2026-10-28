import { parseListPage } from '../../lib/pagination';
import type { AdminMovieListQuery } from './adminMoviesApi';
import { MOVIE_PAGE_SIZE, MOVIE_STATUSES, type MovieSort, type MovieStatus } from './moviesApi';
import { parseMovieSort } from './movieListParams';

export interface AdminMovieListParams {
  status: MovieStatus | null;
  page: number;
  sort: MovieSort;
}

export function parseAdminMovieListParams(search: URLSearchParams): AdminMovieListParams {
  return {
    status: MOVIE_STATUSES.find((status) => status === search.get('status')) ?? null,
    page: parseListPage(search.get('page')),
    sort: parseMovieSort(search.get('sort'))
  };
}

export function adminMovieListSearchParams(params: AdminMovieListParams): URLSearchParams {
  const search = new URLSearchParams();
  if (params.status !== null) search.set('status', params.status);
  if (params.page !== 1) search.set('page', String(params.page));
  if (params.sort !== 'latest') search.set('sort', params.sort);
  return search;
}

export function toAdminMovieListQuery(params: AdminMovieListParams): AdminMovieListQuery {
  return { status: params.status, page: params.page - 1, size: MOVIE_PAGE_SIZE, sort: params.sort };
}

/** Clear only rejected filters; leave unrelated validation errors visible instead of looping. */
export function clearRejectedAdminFilters(params: AdminMovieListParams, fields: readonly string[]): AdminMovieListParams | null {
  const unnamed = fields.length === 0;
  const clearStatus = params.status !== null && (unnamed || fields.includes('status'));
  const clearSort = params.sort !== 'latest' && (unnamed || fields.includes('sort'));
  if (!clearStatus && !clearSort) return null;
  return { status: clearStatus ? null : params.status, sort: clearSort ? 'latest' : params.sort, page: 1 };
}
