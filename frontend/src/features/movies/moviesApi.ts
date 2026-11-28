import type { ApiClient } from '../../lib/api/client';
import type { PageMeta } from '../../lib/api/types';
import { isCanonicalUuid } from '../../lib/uuid';

export const MOVIE_STATUSES = ['DRAFT', 'PUBLISHED', 'ARCHIVED'] as const;
export const VIDEO_ASSET_STATUSES = ['UPLOADED', 'QUEUED', 'PROCESSING', 'READY', 'FAILED'] as const;
export type MovieStatus = typeof MOVIE_STATUSES[number];
export type VideoAssetStatus = typeof VIDEO_ASSET_STATUSES[number];
export const CONTENT_TYPES = ['MOVIE', 'TV_SEASON', 'OVA', 'ONA', 'SPECIAL'] as const;
export type MovieSort = 'latest' | 'title';
/** Vietnamese film classification; null means not classified. */
export const MATURITY_RATINGS = ['P', 'K', 'T13', 'T16', 'T18'] as const;
export type MaturityRating = typeof MATURITY_RATINGS[number];
export const CREDIT_ROLES = ['ACTOR', 'DIRECTOR'] as const;
export type CreditRole = typeof CREDIT_ROLES[number];

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
  contentType: typeof CONTENT_TYPES[number] | UnknownEnumValue;
  /** The admin's optional label for a part, such as "Season 2"; null when none. */
  partLabel: string | null;
  /** Playback availability; READY can remain while a replacement upload is processed. Null when no asset exists. */
  videoStatus: VideoAssetStatus | UnknownEnumValue | null;
}

export interface MovieCredit {
  personId: string;
  name: string;
  role: CreditRole | UnknownEnumValue;
}

export interface PlaybackProgress {
  episodeId: string;
  currentSeconds: number;
  durationSeconds: number | null;
  finished: boolean;
  lastPlayedAt: string | null;
}

export interface EpisodeSummary {
  id: string;
  titleId: string;
  ordinal: number;
  displayLabel: string;
  name: string | null;
  videoStatus: MovieSummary['videoStatus'];
  progress: PlaybackProgress | null;
}

export interface MovieDetail extends MovieSummary {
  episode: EpisodeSummary | null;
  maturityRating: MaturityRating | UnknownEnumValue | null;
  /** Credits in the display order the API returns. */
  people: MovieCredit[];
  /** The caller's own rating, 1-5; null until ratings exist or when the caller has not rated. */
  userRating: number | null;
  inWatchlist: boolean;
  /** Null when the API omits it: the caller has no saved progress for the movie. */
  playbackProgress: PlaybackProgress | null;
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
  list: (query: MovieListQuery) => [...movieKeys.all, 'list', query] as const,
  detail: (movieId: string) => [...movieKeys.all, 'detail', movieId] as const,
  adminDetail: (titleId: string) => [...movieKeys.all, 'admin-detail', titleId] as const,
  watchedTitle: (episodeId: string) => [...movieKeys.all, 'watch', episodeId] as const
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
  const response = await client.request<unknown>(`/titles?${search.toString()}`, {
    authenticated: true,
    signal
  });
  return decodeMoviePage(response);
}

/** Viewer title detail requires a UUID and published visibility. */
export async function getMovie(client: ApiClient, movieId: string, signal?: AbortSignal): Promise<MovieDetail> {
  const response = await client.request<unknown>(`/titles/${encodeURIComponent(movieId)}`, {
    authenticated: true,
    signal
  });
  const movie = decodeMovieDetail(response);
  if (!movie) {
    throw new InvalidMovieResponseError('Server returned an invalid movie');
  }
  return movie;
}

export class InvalidMovieResponseError extends Error {
  constructor(message = 'Server returned an invalid movie page') {
    super(message);
    this.name = 'InvalidMovieResponseError';
  }
}

export function decodeMoviePage(value: unknown): MoviePage {
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
export function decodeMovieSummary(value: unknown): MovieSummary | null {
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
    || typeof value.contentType !== 'string'
    || !(value.partLabel === null || typeof value.partLabel === 'string')
    || !nonNegativeInteger(value.episodeCount) || !nonNegativeInteger(value.playableEpisodeCount)
    || value.playableEpisodeCount > value.episodeCount
    || typeof value.availability !== 'string') {
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
    contentType: knownOrUnknown(CONTENT_TYPES, value.contentType),
    partLabel: value.partLabel as string | null,
    videoStatus: statusFromAvailability(value.availability)
  };
}

function statusFromAvailability(value: string): MovieSummary['videoStatus'] {
  switch (value) {
    case 'PLAYABLE': return 'READY';
    case 'PROCESSING': return 'PROCESSING';
    case 'UNAVAILABLE': return null;
    default: return UNKNOWN_ENUM_VALUE;
  }
}

export function decodeMovieDetail(value: unknown): MovieDetail | null {
  const summary = decodeMovieSummary(value);
  if (!summary
    || !isRecord(value)
    || !(value.maturityRating === null || typeof value.maturityRating === 'string')
    || !Array.isArray(value.people)
    || !(value.userRating === null || isRating(value.userRating))
    || typeof value.inWatchlist !== 'boolean') {
    return null;
  }
  const people = value.people.map(decodeMovieCredit);
  const episode = value.episode === undefined ? null : decodeEpisodeSummary(value.episode);
  if (people.some((credit) => credit === null)
    || (summary.contentType === 'MOVIE' && !episode) || (episode && episode.titleId !== summary.id)) {
    return null;
  }
  return {
    ...summary,
    episode,
    videoStatus: episode ? episode.videoStatus : summary.videoStatus,
    maturityRating: value.maturityRating === null ? null : knownOrUnknown(MATURITY_RATINGS, value.maturityRating),
    people: people as MovieCredit[],
    userRating: value.userRating,
    inWatchlist: value.inWatchlist,
    playbackProgress: episode?.progress ?? null
  };
}

export function decodeEpisodeSummary(value: unknown): EpisodeSummary | null {
  if (!isRecord(value) || !isCanonicalUuid(value.id) || !isCanonicalUuid(value.titleId)
    || !nonNegativeInteger(value.ordinal) || value.ordinal < 1 || typeof value.displayLabel !== 'string'
    || !(value.name === null || typeof value.name === 'string')
    || !(value.videoStatus === null || typeof value.videoStatus === 'string')) {
    return null;
  }
  const progress = value.progress === null ? null : decodePlaybackProgress(value.progress);
  if (progress === undefined || (progress && progress.episodeId !== value.id)) {
    return null;
  }
  return {
    id: value.id,
    titleId: value.titleId,
    ordinal: value.ordinal,
    displayLabel: value.displayLabel,
    name: value.name,
    videoStatus: value.videoStatus === null ? null : knownOrUnknown(VIDEO_ASSET_STATUSES, value.videoStatus),
    progress
  };
}

/** The title of a visible episode, for the watch page (`GET /episodes/{episodeId}`). */
export async function getWatchedTitle(
  client: ApiClient,
  episodeId: string,
  signal?: AbortSignal
): Promise<MovieSummary> {
  const value = await client.request<unknown>(`/episodes/${encodeURIComponent(episodeId)}`, {
    authenticated: true, signal
  });
  const episode = isRecord(value) ? decodeEpisodeSummary(value.episode) : null;
  const title = isRecord(value) ? decodeMovieSummary(value.title) : null;
  if (!title || episode?.id !== episodeId || episode.titleId !== title.id) {
    throw new InvalidMovieResponseError('Server returned an invalid episode');
  }
  return { ...title, videoStatus: episode.videoStatus };
}

function decodeMovieCredit(value: unknown): MovieCredit | null {
  if (!isRecord(value)
    || !isCanonicalUuid(value.personId)
    || typeof value.name !== 'string'
    || typeof value.role !== 'string') {
    return null;
  }
  return { personId: value.personId, name: value.name, role: knownOrUnknown(CREDIT_ROLES, value.role) };
}

// Undefined marks an invalid value, because null is a valid "no progress".
export function decodePlaybackProgress(value: unknown): PlaybackProgress | undefined {
  if (!isRecord(value)
    || !isCanonicalUuid(value.episodeId)
    || !nonNegativeInteger(value.currentSeconds)
    || !(value.durationSeconds === undefined || value.durationSeconds === null
      || nonNegativeInteger(value.durationSeconds))
    || typeof value.finished !== 'boolean'
    || !(value.lastPlayedAt === undefined || value.lastPlayedAt === null || typeof value.lastPlayedAt === 'string')) {
    return undefined;
  }
  return {
    episodeId: value.episodeId,
    currentSeconds: value.currentSeconds,
    durationSeconds: (value.durationSeconds ?? null) as number | null,
    finished: value.finished,
    lastPlayedAt: (value.lastPlayedAt ?? null) as string | null
  };
}

function isRating(value: unknown): value is number {
  return Number.isInteger(value) && (value as number) >= 1 && (value as number) <= 5;
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
