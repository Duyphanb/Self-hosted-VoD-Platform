import type { ApiClient } from '../../lib/api/client';
import type { PageMeta } from '../../lib/api/types';
import { isCanonicalUuid } from '../../lib/uuid';
import {
  decodeMovieDetail, decodeMoviePage, InvalidMovieResponseError,
  type CreditRole, type MaturityRating, type MovieDetail, type MoviePage, type MovieSort, type MovieStatus
} from './moviesApi';

export interface AdminMovieListQuery {
  page: number;
  size: number;
  status: MovieStatus | null;
  sort: MovieSort;
}

export interface CatalogEntry { id: string; name: string; slug: string }
export type Genre = CatalogEntry;
export type Person = CatalogEntry;
export interface PersonPage { items: Person[]; page: PageMeta }

export interface UpsertMovieRequest {
  title: string;
  slug: string;
  description: string;
  releaseYear: number | null;
  maturityRating: MaturityRating | null;
  genres: string[];
  people: { personId: string; role: CreditRole }[];
  status: MovieStatus;
}

export async function listAdminMovies(client: ApiClient, query: AdminMovieListQuery, signal?: AbortSignal): Promise<MoviePage> {
  const search = new URLSearchParams({ page: String(query.page), size: String(query.size), sort: query.sort });
  if (query.status !== null) search.set('status', query.status);
  return decodeMoviePage(await client.request<unknown>(`/admin/movies?${search}`, { authenticated: true, signal }));
}

export async function createMovie(client: ApiClient, payload: UpsertMovieRequest, signal?: AbortSignal): Promise<MovieDetail> {
  return requireMovie(await client.request<unknown>('/movies', { authenticated: true, method: 'POST', json: payload, signal }));
}

export async function updateMovie(client: ApiClient, movieId: string, payload: UpsertMovieRequest, signal?: AbortSignal): Promise<MovieDetail> {
  return requireMovie(await client.request<unknown>(moviePath(movieId), { authenticated: true, method: 'PUT', json: payload, signal }));
}

export async function archiveMovie(client: ApiClient, movieId: string, signal?: AbortSignal): Promise<void> {
  await client.request<void>(moviePath(movieId), { authenticated: true, method: 'DELETE', signal });
}

export async function listAdminGenres(client: ApiClient, signal?: AbortSignal): Promise<Genre[]> {
  const response = await client.request<unknown>('/admin/genres', { authenticated: true, signal });
  if (!Array.isArray(response)) throw new InvalidMovieResponseError('Server returned invalid genres');
  return response.map(requireCatalogEntry);
}

export async function searchPeople(client: ApiClient, q: string, page = 0, signal?: AbortSignal): Promise<PersonPage> {
  const search = new URLSearchParams({ page: String(page), size: '20' });
  if (q !== '') search.set('q', q);
  const response = await client.request<unknown>(`/admin/people?${search}`, { authenticated: true, signal });
  if (!isRecord(response) || !Array.isArray(response.items)) throw new InvalidMovieResponseError('Server returned invalid people');
  return { items: response.items.map(requireCatalogEntry), page: requirePageMeta(response.page) };
}

export async function createPerson(client: ApiClient, name: string, signal?: AbortSignal): Promise<Person> {
  return requireCatalogEntry(await client.request<unknown>('/admin/people', {
    authenticated: true, method: 'POST', json: { name }, signal
  }));
}

function moviePath(movieId: string): string {
  const canonicalId = movieId.toLowerCase();
  if (!isCanonicalUuid(canonicalId)) throw new Error('A valid movie ID is required');
  return `/movies/${canonicalId}`;
}

function requireMovie(value: unknown): MovieDetail {
  const movie = decodeMovieDetail(value);
  if (!movie) throw new InvalidMovieResponseError('Server returned an invalid movie');
  return movie;
}

function requireCatalogEntry(value: unknown): CatalogEntry {
  if (!isRecord(value) || !isCanonicalUuid(value.id) || typeof value.name !== 'string' || typeof value.slug !== 'string') {
    throw new InvalidMovieResponseError('Server returned an invalid genre or person');
  }
  return { id: value.id, name: value.name, slug: value.slug };
}

function requirePageMeta(value: unknown): PageMeta {
  if (!isRecord(value) || !nonNegativeInteger(value.page) || !nonNegativeInteger(value.size)
    || !nonNegativeInteger(value.totalElements) || !nonNegativeInteger(value.totalPages)) {
    throw new InvalidMovieResponseError('Server returned invalid pagination');
  }
  return { page: value.page, size: value.size, totalElements: value.totalElements, totalPages: value.totalPages };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function nonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}
