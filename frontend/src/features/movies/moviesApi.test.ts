import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import contract from '../../../../docs/architecture/API-CONTRACT.yaml?raw';
import { ApiClient, type AuthenticationSnapshot } from '../../lib/api/client';
import {
  getMovie,
  InvalidMovieResponseError,
  listMovies,
  type MovieCredit,
  type MovieDetail,
  movieKeys,
  type MovieSummary,
  type PlaybackProgress,
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

const detail: MovieDetail = {
  ...movie,
  maturityRating: 'T16',
  people: [
    { personId: '0b8f3a52-6a3e-4d0c-9a4b-3f2e1d0c9b8a', name: 'Trần Anh Hùng', role: 'DIRECTOR' },
    { personId: '1c9e4b63-7b4f-4e1d-8b5c-4a3f2e1d0c9b', name: 'Đỗ Thị Hải Yến', role: 'ACTOR' }
  ],
  userRating: 4,
  inWatchlist: true,
  playbackProgress: {
    movieId: movie.id,
    currentSeconds: 754,
    durationSeconds: 5400,
    finished: false,
    lastPlayedAt: '2026-10-27T09:15:00Z'
  }
};

describe('getMovie', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('sends an authenticated GET for the movie and decodes every field', async () => {
    fetchMock.mockResolvedValue(jsonResponse(detail));

    const result = await getMovie(apiClient(), movie.id);

    const [input, init] = fetchMock.mock.calls[0];
    expect(new URL(String(input), 'http://localhost').pathname).toBe(`/api/v1/movies/${movie.id}`);
    expect(init?.method ?? 'GET').toBe('GET');
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer access-token');
    expect(result).toEqual(detail);
  });

  it('reads an omitted or null playbackProgress, and omitted progress fields, as absent', async () => {
    const { playbackProgress: _omitted, ...withoutProgress } = detail;
    fetchMock.mockResolvedValueOnce(jsonResponse(withoutProgress));
    expect((await getMovie(apiClient(), movie.id)).playbackProgress).toBeNull();

    fetchMock.mockResolvedValueOnce(jsonResponse({ ...detail, playbackProgress: null }));
    expect((await getMovie(apiClient(), movie.id)).playbackProgress).toBeNull();

    fetchMock.mockResolvedValueOnce(jsonResponse({
      ...detail,
      playbackProgress: { movieId: movie.id, currentSeconds: 0, finished: true }
    }));
    expect((await getMovie(apiClient(), movie.id)).playbackProgress).toEqual({
      movieId: movie.id,
      currentSeconds: 0,
      durationSeconds: null,
      finished: true,
      lastPlayedAt: null
    });
  });

  it('accepts the nullable detail fields as null', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ ...detail, maturityRating: null, userRating: null, people: [] }));

    const result = await getMovie(apiClient(), movie.id);

    expect(result).toMatchObject({ maturityRating: null, userRating: null, people: [] });
  });

  it('decodes unknown maturity ratings and credit roles to the UNKNOWN sentinel', async () => {
    fetchMock.mockResolvedValue(jsonResponse({
      ...detail,
      maturityRating: 'C',
      people: [{ ...detail.people[0], role: 'WRITER' }, detail.people[1]]
    }));

    const result = await getMovie(apiClient(), movie.id);

    expect(result.maturityRating).toBe(UNKNOWN_ENUM_VALUE);
    expect(result.people.map((credit) => credit.role)).toEqual([UNKNOWN_ENUM_VALUE, 'ACTOR']);
  });

  it.each(rejectedDetails())('rejects a detail response with %s', async (_label, body) => {
    fetchMock.mockResolvedValue(jsonResponse(body));

    const request = getMovie(apiClient(), movie.id);

    await expect(request).rejects.toBeInstanceOf(InvalidMovieResponseError);
    await expect(request).rejects.toThrow('Server returned an invalid movie');
  });

  it('has a wrong-type value for every required field of the contract', () => {
    expect(Object.keys(WRONG_DETAIL_VALUES).sort()).toEqual(contractAllOfRequired('MovieDetail').sort());
    expect(Object.keys(WRONG_CREDIT_VALUES).sort()).toEqual(contractRequired('MovieCredit').sort());
    expect(Object.keys(WRONG_PROGRESS_VALUES).sort()).toEqual(contractRequired('ProgressResponse').sort());
  });
});

describe('movieKeys', () => {
  it('nests every list and detail key under the shared movies key', () => {
    const query = { page: 1, size: 20, genre: 'Drama', year: null, sort: 'latest' as const };
    expect(movieKeys.list(query)).toEqual(['movies', 'list', query]);
    expect(movieKeys.list(query).slice(0, 1)).toEqual(movieKeys.all);
    expect(movieKeys.detail(movie.id)).toEqual(['movies', 'detail', movie.id]);
  });
});

// A value of the wrong type for each required field the detail adds to MovieSummary.
const WRONG_DETAIL_VALUES: Partial<Record<keyof MovieDetail, unknown>> = {
  maturityRating: 16,
  people: 'cast',
  userRating: '4',
  inWatchlist: 'yes'
};

const WRONG_CREDIT_VALUES: Partial<Record<keyof MovieCredit, unknown>> = {
  personId: 7,
  name: null,
  role: 1
};

const WRONG_PROGRESS_VALUES: Partial<Record<keyof PlaybackProgress, unknown>> = {
  movieId: 'not-a-uuid',
  currentSeconds: -1,
  finished: 'no'
};

function rejectedDetails(): Array<[string, unknown]> {
  const progress = detail.playbackProgress as PlaybackProgress;
  const rows: Array<[string, unknown]> = [
    ['a body that is not an object', [detail]],
    ['a summary field of the wrong type', { ...detail, title: 1 }],
    ['an upper-case id', { ...detail, id: movie.id.toUpperCase() }],
    ['a rating below 1', { ...detail, userRating: 0 }],
    ['a rating above 5', { ...detail, userRating: 6 }],
    ['a fractional rating', { ...detail, userRating: 4.5 }],
    ['a credit that is not an object', { ...detail, people: ['Trần Anh Hùng'] }],
    ['a credit with a non-canonical personId', { ...detail, people: [{ ...detail.people[0], personId: '../admin' }] }],
    ['progress that is not an object', { ...detail, playbackProgress: 754 }],
    ['a fractional progress position', { ...detail, playbackProgress: { ...progress, currentSeconds: 1.5 } }],
    ['a string progress duration', { ...detail, playbackProgress: { ...progress, durationSeconds: '5400' } }],
    ['a numeric lastPlayedAt', { ...detail, playbackProgress: { ...progress, lastPlayedAt: 5 } }]
  ];
  for (const field of contractAllOfRequired('MovieDetail')) {
    const { [field]: _omitted, ...withoutField } = detail as unknown as Record<string, unknown>;
    rows.push([`MovieDetail.${field} missing`, withoutField]);
    rows.push([`MovieDetail.${field} of the wrong type`, { ...detail, [field]: WRONG_DETAIL_VALUES[field as keyof MovieDetail] }]);
  }
  for (const field of contractRequired('MovieCredit')) {
    const { [field]: _omitted, ...withoutField } = detail.people[0] as unknown as Record<string, unknown>;
    rows.push([`MovieCredit.${field} missing`, { ...detail, people: [withoutField] }]);
    rows.push([
      `MovieCredit.${field} of the wrong type`,
      { ...detail, people: [{ ...detail.people[0], [field]: WRONG_CREDIT_VALUES[field as keyof MovieCredit] }] }
    ]);
  }
  for (const field of contractRequired('ProgressResponse')) {
    const { [field]: _omitted, ...withoutField } = progress as unknown as Record<string, unknown>;
    rows.push([`ProgressResponse.${field} missing`, { ...detail, playbackProgress: withoutField }]);
    rows.push([
      `ProgressResponse.${field} of the wrong type`,
      { ...detail, playbackProgress: { ...progress, [field]: WRONG_PROGRESS_VALUES[field as keyof PlaybackProgress] } }
    ]);
  }
  return rows;
}

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

/** The `required` list of the object part of an `allOf` schema in the frozen contract. */
function contractAllOfRequired(schema: string): string[] {
  const pattern = new RegExp(String.raw`\n {4}${schema}:\n {6}allOf:\n[\s\S]*?\n {10}required: \[([^\]]*)\]`);
  const match = pattern.exec(contract.replace(/\r\n/g, '\n'));
  if (!match) {
    throw new Error(`${schema} has no allOf required list in API-CONTRACT.yaml`);
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
