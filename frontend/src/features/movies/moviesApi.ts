import type { ApiClient } from '../../lib/api/client';
import type { PageMeta } from '../../lib/api/types';
import { isCanonicalUuid } from '../../lib/uuid';

export const MOVIE_STATUSES = ['DRAFT', 'PUBLISHED', 'ARCHIVED'] as const;
export const VIDEO_ASSET_STATUSES = ['UPLOADED', 'QUEUED', 'PROCESSING', 'READY', 'FAILED'] as const;
export type MovieStatus = typeof MOVIE_STATUSES[number];
export type VideoAssetStatus = typeof VIDEO_ASSET_STATUSES[number];
export type MovieSort = 'latest' | 'title';

/**
 * Decoded in place of an enum value this client does not know, such as a status added to the API
 * later. It is part of the response types, so a switch or `Record` over a status must handle it.
 */
export const UNKNOWN_ENUM_VALUE = 'UNKNOWN';
export type UnknownEnumValue = typeof UNKNOWN_ENUM_VALUE;

export const MOVIE_SORTS: readonly MovieSort[] = ['latest', 'title'];

/** Contract default for `size`; the list pages always request this many movies. */
export const MOVIE_PAGE_SIZE = 20;

export interface MovieSummary {
  /** Canonical lower-case UUID; the decoder rejects anything else, so it is safe in a route path. */
  id: string;
  title: string;
  slug: string;
  description: string;
  releaseYear: number | null;
  /** Always null until a poster delivery path exists; render `PosterArt` instead. */
  posterUrl: string | null;
  genres: string[];
  averageRating: number | null;
  status: MovieStatus | UnknownEnumValue;
  /** Null while the movie has no video asset, which is always the case until upload exists. */
  videoStatus: VideoAssetStatus | UnknownEnumValue | null;
}

export interface MoviePage {
  items: MovieSummary[];
  page: PageMeta;
}

/** Query of `GET /api/v1/movies`; `page` is the 0-based API page. */
export interface MovieListQuery {
  page: number;
  size: number;
  genre: string | null;
  year: number | null;
  sort: MovieSort;
}

/** Every movie query key starts with `movieKeys.all`, so invalidating it refreshes all movie views. */
export const movieKeys = {
  all: ['movies'] as const,
  list: (query: MovieListQuery) => [...movieKeys.all, 'list', query] as const
};

export async function listMovies(
  client: ApiClient,
  query: MovieListQuery,
  signal?: AbortSignal
): Promise<MoviePage> {
  const search = new URLSearchParams({
    page: String(query.page),
    size: String(query.size),
    sort: query.sort
  });
  if (query.genre !== null) {
    search.set('genre', query.genre);
  }
  if (query.year !== null) {
    search.set('year', String(query.year));
  }
  const response = await client.request<unknown>(`/movies?${search.toString()}`, {
    authenticated: true,
    signal
  });
  return decodeMoviePage(response);
}

export class InvalidMovieResponseError extends Error {
  constructor() {
    super('Server returned an invalid movie page');
    this.name = 'InvalidMovieResponseError';
  }
}

function decodeMoviePage(value: unknown): MoviePage {
  if (!isRecord(value) || !Array.isArray(value.items)) {
    throw new InvalidMovieResponseError();
  }
  const page = decodePageMeta(value.page);
  const items = value.items.map(decodeMovieSummary);
  if (!page || items.some((item) => item === null)) {
    throw new InvalidMovieResponseError();
  }
  return { items: items as MovieSummary[], page };
}

function decodePageMeta(value: unknown): PageMeta | null {
  if (!isRecord(value)
    || !nonNegativeInteger(value.page)
    || !nonNegativeInteger(value.size)
    || !nonNegativeInteger(value.totalElements)
    || !nonNegativeInteger(value.totalPages)) {
    return null;
  }
  return {
    page: value.page,
    size: value.size,
    totalElements: value.totalElements,
    totalPages: value.totalPages
  };
}

// Checks the shape of every contract field. An unknown status string decodes to UNKNOWN_ENUM_VALUE.
function decodeMovieSummary(value: unknown): MovieSummary | null {
  if (!isRecord(value)
    || !isCanonicalUuid(value.id)
    || typeof value.title !== 'string'
    || typeof value.slug !== 'string'
    || typeof value.description !== 'string'
    || !(value.releaseYear === null || Number.isInteger(value.releaseYear))
    || !(value.posterUrl === null || typeof value.posterUrl === 'string')
    || !Array.isArray(value.genres)
    || !value.genres.every((genre) => typeof genre === 'string')
    || !(value.averageRating === null || typeof value.averageRating === 'number')
    || typeof value.status !== 'string'
    || !(value.videoStatus === null || typeof value.videoStatus === 'string')) {
    return null;
  }
  return {
    id: value.id,
    title: value.title,
    slug: value.slug,
    description: value.description,
    releaseYear: value.releaseYear as number | null,
    posterUrl: value.posterUrl,
    genres: [...value.genres] as string[],
    averageRating: value.averageRating,
    status: knownOrUnknown(MOVIE_STATUSES, value.status),
    videoStatus: value.videoStatus === null ? null : knownOrUnknown(VIDEO_ASSET_STATUSES, value.videoStatus)
  };
}

function knownOrUnknown<T extends string>(known: readonly T[], value: string): T | UnknownEnumValue {
  return known.find((candidate) => candidate === value) ?? UNKNOWN_ENUM_VALUE;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function nonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}
