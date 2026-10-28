import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import contract from '../../../../docs/architecture/API-CONTRACT.yaml?raw';
import { ApiClient, type AuthenticationSnapshot } from '../../lib/api/client';
import {
  InvalidMovieResponseError,
  listMovies,
  movieKeys,
  type MovieSummary,
  UNKNOWN_ENUM_VALUE
} from './moviesApi';

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

  it.each(rejectedResponses())('rejects a response with %s', async (_label, body) => {
    fetchMock.mockResolvedValue(jsonResponse(body));

    await expect(listMovies(apiClient(), {
      page: 0,
      size: 20,
      genre: null,
      year: null,
      sort: 'latest'
    })).rejects.toBeInstanceOf(InvalidMovieResponseError);
  });

  it('has a wrong-type value for every required field of the contract', () => {
    expect(Object.keys(WRONG_SUMMARY_VALUES).sort()).toEqual([...contractRequired('MovieSummary')].sort());
    expect(contractRequired('PageMeta')).toEqual(['page', 'size', 'totalElements', 'totalPages']);
  });

  it('accepts every nullable field as null', async () => {
    const nullable = { ...movie, releaseYear: null, posterUrl: null, averageRating: null, videoStatus: null };
    fetchMock.mockResolvedValue(jsonResponse({ items: [nullable], page: pageMeta }));

    const result = await listMovies(apiClient(), { page: 0, size: 20, genre: null, year: null, sort: 'latest' });

    expect(result.items).toEqual([nullable]);
  });

  it('decodes unknown status values to the UNKNOWN sentinel and keeps known ones', async () => {
    fetchMock.mockResolvedValue(jsonResponse({
      items: [
        { ...movie, status: 'SCHEDULED', videoStatus: 'TRANSCODING_V2' },
        { ...movie, status: 'ARCHIVED', videoStatus: 'READY' }
      ],
      page: { ...pageMeta, totalElements: 2 }
    }));

    const result = await listMovies(apiClient(), {
      page: 0,
      size: 20,
      genre: null,
      year: null,
      sort: 'latest'
    });

    expect(UNKNOWN_ENUM_VALUE).toBe('UNKNOWN');
    expect(result.items.map(({ status, videoStatus }) => [status, videoStatus])).toEqual([
      ['UNKNOWN', 'UNKNOWN'],
      ['ARCHIVED', 'READY']
    ]);
  });
});

describe('movieKeys', () => {
  it('nests every list key under the shared movies key', () => {
    const query = { page: 1, size: 20, genre: 'Drama', year: null, sort: 'latest' as const };
    expect(movieKeys.list(query)).toEqual(['movies', 'list', query]);
    expect(movieKeys.list(query).slice(0, 1)).toEqual(movieKeys.all);
  });
});

// A value of the wrong type for each required MovieSummary field of API-CONTRACT.yaml.
const WRONG_SUMMARY_VALUES: Record<keyof MovieSummary, unknown> = {
  id: 42,
  title: null,
  slug: 7,
  description: ['text'],
  releaseYear: '2019',
  posterUrl: 1,
  genres: 'Drama',
  averageRating: '4.5',
  status: 1,
  videoStatus: false
};

function rejectedResponses(): Array<[string, unknown]> {
  const rows: Array<[string, unknown]> = [
    ['a body that is not an object', [movie]],
    ['a missing page', { items: [movie] }],
    ['items that are not an array', { items: null, page: pageMeta }],
    ['an item that is not an object', { items: ['movie'], page: pageMeta }],
    ['genres that are not strings', { items: [{ ...movie, genres: [1] }], page: pageMeta }],
    ['a fractional release year', { items: [{ ...movie, releaseYear: 1999.5 }], page: pageMeta }]
  ];
  for (const field of contractRequired('MovieSummary')) {
    const { [field]: _omitted, ...withoutField } = movie as unknown as Record<string, unknown>;
    rows.push([`MovieSummary.${field} missing`, { items: [withoutField], page: pageMeta }]);
    rows.push([
      `MovieSummary.${field} of the wrong type`,
      { items: [{ ...movie, [field]: WRONG_SUMMARY_VALUES[field as keyof MovieSummary] }], page: pageMeta }
    ]);
  }
  for (const id of ['not-a-uuid', '../admin', '6F1C0F55-2D4A-4B64-9A43-1D0F3C7B8E21', `${movie.id}/edit`, `${movie.id}?x`, '']) {
    rows.push([`a non-canonical id ${JSON.stringify(id)}`, { items: [{ ...movie, id }], page: pageMeta }]);
  }
  for (const field of contractRequired('PageMeta')) {
    const { [field]: _omitted, ...withoutField } = pageMeta as Record<string, number>;
    rows.push([`PageMeta.${field} missing`, { items: [], page: withoutField }]);
    for (const value of [-1, 1.5, '1', null]) {
      rows.push([`PageMeta.${field} = ${JSON.stringify(value)}`, { items: [], page: { ...pageMeta, [field]: value } }]);
    }
  }
  return rows;
}

/** The `required` list of a schema in the frozen contract. */
function contractRequired(schema: string): string[] {
  const pattern = new RegExp(String.raw`\n {4}${schema}:\n {6}type: object\n {6}required: \[([^\]]*)\]`);
  const match = pattern.exec(contract.replace(/\r\n/g, '\n'));
  if (!match) {
    throw new Error(`${schema} has no required list in API-CONTRACT.yaml`);
  }
  return match[1].split(',').map((field) => field.trim());
}

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
