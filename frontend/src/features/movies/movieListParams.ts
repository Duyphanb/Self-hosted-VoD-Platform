import {
  MOVIE_PAGE_SIZE,
  MOVIE_SORTS,
  type MovieListQuery,
  type MovieSort
} from './moviesApi';

export const GENRE_MAX_LENGTH = 120;
export const MIN_RELEASE_YEAR = 1888;
export const MAX_RELEASE_YEAR = 2100;
/** The API accepts 0-based pages up to 10000, so the 1-based URL page stops at 10001. */
export const MAX_LIST_PAGE = 10_001;

/** Movie list state as it appears in the URL; `page` is 1-based. */
export interface MovieListParams {
  genre: string | null;
  year: number | null;
  sort: MovieSort;
  page: number;
}

export type MovieListFilters = Pick<MovieListParams, 'genre' | 'year' | 'sort'>;

export const DEFAULT_MOVIE_LIST_PARAMS: MovieListParams = {
  genre: null,
  year: null,
  sort: 'latest',
  page: 1
};

/** Reads `/movies?genre=&year=&sort=&page=`; a missing or invalid value falls back to its default. */
export function parseMovieListParams(search: URLSearchParams): MovieListParams {
  return {
    genre: parseGenre(search.get('genre')),
    year: parseReleaseYear(search.get('year')),
    sort: parseMovieSort(search.get('sort')),
    page: parseListPage(search.get('page'))
  };
}

/** Writes the URL form of `params`, leaving out default values. */
export function movieListSearchParams(params: MovieListParams): URLSearchParams {
  const search = new URLSearchParams();
  if (params.genre !== null) {
    search.set('genre', params.genre);
  }
  if (params.year !== null) {
    search.set('year', String(params.year));
  }
  if (params.sort !== DEFAULT_MOVIE_LIST_PARAMS.sort) {
    search.set('sort', params.sort);
  }
  if (params.page !== DEFAULT_MOVIE_LIST_PARAMS.page) {
    search.set('page', String(params.page));
  }
  return search;
}

/** Converts the 1-based URL page into the 0-based API page. */
export function toMovieListQuery(params: MovieListParams): MovieListQuery {
  return {
    page: params.page - 1,
    size: MOVIE_PAGE_SIZE,
    genre: params.genre,
    year: params.year,
    sort: params.sort
  };
}

export function hasActiveFilters(params: Pick<MovieListParams, 'genre' | 'year'>): boolean {
  return params.genre !== null || params.year !== null;
}

/** Trimmed genre of 1-120 characters, otherwise null (no genre filter). */
export function parseGenre(value: string | null): string | null {
  const genre = value?.trim() ?? '';
  return genre.length >= 1 && genre.length <= GENRE_MAX_LENGTH ? genre : null;
}

/** Four-digit year from 1888 to 2100, otherwise null (no year filter). */
export function parseReleaseYear(value: string | null): number | null {
  const year = value?.trim() ?? '';
  if (!/^\d{4}$/.test(year)) {
    return null;
  }
  const parsed = Number(year);
  return parsed >= MIN_RELEASE_YEAR && parsed <= MAX_RELEASE_YEAR ? parsed : null;
}

export function parseMovieSort(value: string | null): MovieSort {
  return MOVIE_SORTS.find((sort) => sort === value) ?? DEFAULT_MOVIE_LIST_PARAMS.sort;
}

/** Whole page number from 1; larger values are clamped to the last page the API accepts. */
function parseListPage(value: string | null): number {
  if (value === null || !/^\d+$/.test(value)) {
    return DEFAULT_MOVIE_LIST_PARAMS.page;
  }
  const page = Number(value);
  if (page < 1) {
    return DEFAULT_MOVIE_LIST_PARAMS.page;
  }
  return Math.min(page, MAX_LIST_PAGE);
}
