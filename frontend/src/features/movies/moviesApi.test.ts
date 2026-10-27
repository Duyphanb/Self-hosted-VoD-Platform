import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiClient, type AuthenticationSnapshot } from '../../lib/api/client';
import { InvalidMovieResponseError, listMovies, movieKeys, type MovieSummary } from './moviesApi';

const movie: MovieSummary = {
  id: '6f1c0f55-2d4a-4b64-9a43-1d0f3c7b8e21',
  title: 'The Long Night',
  slug: 'the-long-night',
  description: 'A storm traps a crew at sea.',
  releaseYear: 2019,
  posterUrl: null,
  genres: ['Drama', 'Thriller'],
  averageRating: null,
  status: 'PUBLISHED',
  videoStatus: null
};

const pageMeta = { page: 0, size: 20, totalElements: 1, totalPages: 1 };

describe('listMovies', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('sends an authenticated GET with every query parameter', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ items: [movie], page: pageMeta }));

    const result = await listMovies(apiClient(), {
      page: 2,
      size: 20,
      genre: 'Sci-Fi & Fantasy',
      year: 1999,
      sort: 'title'
    });

    const [input, init] = fetchMock.mock.calls[0];
    const url = new URL(String(input), 'http://localhost');
    expect(url.pathname).toBe('/api/v1/movies');
    expect(Object.fromEntries(url.searchParams)).toEqual({
      page: '2',
      size: '20',
      genre: 'Sci-Fi & Fantasy',
      year: '1999',
      sort: 'title'
    });
    expect(init?.method).toBeUndefined();
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer access-token');
    expect(result).toEqual({ items: [movie], page: pageMeta });
  });

  it('leaves out genre and year when they are not filtered', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ items: [], page: { ...pageMeta, totalElements: 0, totalPages: 0 } }));

    await listMovies(apiClient(), { page: 0, size: 20, genre: null, year: null, sort: 'latest' });

    const url = new URL(String(fetchMock.mock.calls[0][0]), 'http://localhost');
    expect(Object.fromEntries(url.searchParams)).toEqual({ page: '0', size: '20', sort: 'latest' });
  });

  it.each([
    ['a missing page', { items: [movie] }],
    ['items that are not an array', { items: null, page: pageMeta }],
    ['genres that are not strings', { items: [{ ...movie, genres: [1] }], page: pageMeta }],
    ['a missing title', { items: [{ ...movie, title: undefined }], page: pageMeta }],
    ['a fractional release year', { items: [{ ...movie, releaseYear: 1999.5 }], page: pageMeta }],
    ['a negative total', { items: [], page: { ...pageMeta, totalElements: -1 } }]
  ])('rejects a response with %s', async (_label, body) => {
    fetchMock.mockResolvedValue(jsonResponse(body));

    await expect(listMovies(apiClient(), {
      page: 0,
      size: 20,
      genre: null,
      year: null,
      sort: 'latest'
    })).rejects.toBeInstanceOf(InvalidMovieResponseError);
  });

  it('accepts an unknown video status so views can show it as unknown', async () => {
    fetchMock.mockResolvedValue(jsonResponse({
      items: [{ ...movie, videoStatus: 'TRANSCODING_V2' }],
      page: pageMeta
    }));

    const result = await listMovies(apiClient(), {
      page: 0,
      size: 20,
      genre: null,
      year: null,
      sort: 'latest'
    });

    expect(result.items[0].videoStatus).toBe('TRANSCODING_V2');
  });
});

describe('movieKeys', () => {
  it('nests every list key under the shared movies key', () => {
    const query = { page: 1, size: 20, genre: 'Drama', year: null, sort: 'latest' as const };
    expect(movieKeys.list(query)).toEqual(['movies', 'list', query]);
    expect(movieKeys.list(query).slice(0, 1)).toEqual(movieKeys.all);
  });
});

function apiClient(): ApiClient {
  return new ApiClient('/api/v1', {
    getAuthenticationSnapshot: (): AuthenticationSnapshot => ({
      userId: 'user-1',
      accessToken: 'access-token',
      generation: 0
    }),
    refreshAccessToken: vi.fn(),
    onAuthenticationFailure: vi.fn()
  });
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' }
  });
}
