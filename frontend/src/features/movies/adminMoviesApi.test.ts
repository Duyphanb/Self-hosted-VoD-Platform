import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiClient, ApiRequestError } from '../../lib/api/client';
import {
  archiveMovie, createMovie, createPerson, listAdminGenres, listAdminMovies, searchPeople, updateMovie,
  type UpsertMovieRequest
} from './adminMoviesApi';
import { InvalidMovieResponseError, type MovieDetail } from './moviesApi';

const movieId = '6f1c0f55-2d4a-4b64-9a43-1d0f3c7b8e21';
const personId = '3fcf25c2-f096-4462-ae63-4ba4702a9078';
const entry = { id: personId, name: 'Đất Rừng', slug: 'dat-rung' };
const page = { page: 0, size: 20, totalElements: 1, totalPages: 1 };
const payload: UpsertMovieRequest = {
  title: 'Movie', slug: 'movie', description: 'Description', releaseYear: null, maturityRating: null,
  genres: ['Drama'], people: [{ personId, role: 'DIRECTOR' }], status: 'DRAFT'
};
const movie: MovieDetail = {
  ...payload, id: movieId, people: [{ personId, role: 'DIRECTOR', name: 'Director' }],
  posterUrl: null, averageRating: null, videoStatus: null, userRating: null, inWatchlist: false, playbackProgress: null
};
const fetchMock = vi.fn<typeof fetch>();

describe('admin movie API', () => {
  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });
  afterEach(() => { vi.unstubAllGlobals(); });

  it('lists every status without a filter and authenticates the request with its cancellation signal', async () => {
    fetchMock.mockResolvedValue(json({ items: [movie], page }));
    const signal = new AbortController().signal;
    const result = await listAdminMovies(client(), { page: 0, size: 20, status: null, sort: 'latest' }, signal);
    expect(result.items[0].status).toBe('DRAFT');
    expect(path()).toBe('/api/v1/admin/movies');
    expect(Object.fromEntries(url().searchParams)).toEqual({ page: '0', size: '20', sort: 'latest' });
    expect(fetchMock.mock.calls[0][1]?.signal).toBe(signal);
    expect(new Headers(fetchMock.mock.calls[0][1]?.headers).get('Authorization')).toBe('Bearer access-token');
  });

  it('sends the admin status and page filters and decodes unknown enum values safely', async () => {
    fetchMock.mockResolvedValue(json({ items: [{ ...movie, status: 'SCHEDULED', videoStatus: 'FUTURE' }], page }));
    const result = await listAdminMovies(client(), { page: 2, size: 20, status: 'ARCHIVED', sort: 'title' });
    expect(Object.fromEntries(url().searchParams)).toEqual({ page: '2', size: '20', sort: 'title', status: 'ARCHIVED' });
    expect(result.items[0]).toMatchObject({ status: 'UNKNOWN', videoStatus: 'UNKNOWN' });
  });

  it.each(['POST', 'PUT'] as const)('sends the full movie replacement with %s and decodes the detail response', async (method) => {
    fetchMock.mockResolvedValue(json(movie, method === 'POST' ? 201 : 200));
    const result = method === 'POST'
      ? await createMovie(client(), payload)
      : await updateMovie(client(), movieId.toUpperCase(), payload);
    expect(result).toEqual(movie);
    expect(path()).toBe(method === 'POST' ? '/api/v1/movies' : `/api/v1/movies/${movieId}`);
    expect(fetchMock.mock.calls[0][1]?.method).toBe(method);
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual(payload);
    expect(new Headers(fetchMock.mock.calls[0][1]?.headers).get('Content-Type')).toBe('application/json');
  });

  it('archives with DELETE and accepts the empty 204 response', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await expect(archiveMovie(client(), movieId.toUpperCase())).resolves.toBeUndefined();
    expect(path()).toBe(`/api/v1/movies/${movieId}`);
    expect(fetchMock.mock.calls[0][1]?.method).toBe('DELETE');
    expect(fetchMock.mock.calls[0][1]?.body).toBeUndefined();
  });

  it('rejects invalid mutation IDs without calling the server', async () => {
    await expect(archiveMovie(client(), '../movies')).rejects.toThrow('valid movie ID');
    await expect(updateMovie(client(), 'not-a-uuid', payload)).rejects.toThrow('valid movie ID');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('loads genres from the unpaged array endpoint', async () => {
    fetchMock.mockResolvedValue(json([entry]));
    await expect(listAdminGenres(client())).resolves.toEqual([entry]);
    expect(path()).toBe('/api/v1/admin/genres');
    expect(url().search).toBe('');
  });

  it('encodes a people search and preserves duplicate names as distinct IDs', async () => {
    const other = { ...entry, id: movieId, slug: 'dat-rung-2' };
    fetchMock.mockResolvedValue(json({ items: [entry, other], page: { ...page, totalElements: 2 } }));
    await expect(searchPeople(client(), 'Đất & Rừng', 1)).resolves.toMatchObject({ items: [entry, other] });
    expect(path()).toBe('/api/v1/admin/people');
    expect(Object.fromEntries(url().searchParams)).toEqual({ q: 'Đất & Rừng', page: '1', size: '20' });
  });

  it('creates a person explicitly and uses the returned canonical identity', async () => {
    fetchMock.mockResolvedValue(json(entry, 201));
    await expect(createPerson(client(), 'Đất Rừng')).resolves.toEqual(entry);
    expect(path()).toBe('/api/v1/admin/people');
    expect(fetchMock.mock.calls[0][1]?.method).toBe('POST');
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({ name: 'Đất Rừng' });
  });

  it.each([
    ['genre envelope instead of array', () => listAdminGenres(client()), { items: [entry] }],
    ['genre with invalid id', () => listAdminGenres(client()), [{ ...entry, id: 'invalid' }]],
    ['person with wrong name type', () => searchPeople(client(), 'x'), { items: [{ ...entry, name: 5 }], page }],
    ['people without pagination', () => searchPeople(client(), 'x'), { items: [entry] }],
    ['people with negative pagination', () => searchPeople(client(), 'x'), { items: [entry], page: { ...page, page: -1 } }],
    ['create with malformed detail', () => createMovie(client(), payload), { ...movie, people: null }],
    ['person create without slug', () => createPerson(client(), 'Name'), { id: personId, name: 'Name' }]
  ] as const)('rejects %s', async (_label, request, response) => {
    fetchMock.mockResolvedValue(json(response));
    await expect(request()).rejects.toBeInstanceOf(InvalidMovieResponseError);
  });

  it('preserves forbidden responses for page-level handling', async () => {
    fetchMock.mockResolvedValue(json({ timestamp: '2026-10-28T00:00:00Z', status: 403, code: 'FORBIDDEN', message: 'Access denied' }, 403));
    await expect(listAdminMovies(client(), { page: 0, size: 20, status: null, sort: 'latest' }))
      .rejects.toBeInstanceOf(ApiRequestError);
  });
});

function client(): ApiClient {
  return new ApiClient('/api/v1', {
    getAuthenticationSnapshot: () => ({ userId: personId, accessToken: 'access-token', generation: 0 }),
    refreshAccessToken: vi.fn(), onAuthenticationFailure: vi.fn()
  });
}
function url(): URL { return new URL(String(fetchMock.mock.calls[0][0]), 'http://localhost'); }
function path(): string { return url().pathname; }
function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
}
