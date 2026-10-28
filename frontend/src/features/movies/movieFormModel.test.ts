import { describe, expect, it } from 'vitest';
import { ApiRequestError } from '../../lib/api/client';
import { slugify } from '../../lib/slug';
import {
  createEmptyMovieForm, mapMovieFormError, movieDetailToForm, normalizeCatalogName, toMoviePayload,
  validateGenreName, validateMovieForm, validatePersonName, type MovieFormValues
} from './movieFormModel';
import type { MovieDetail } from './moviesApi';

const personId = '3fcf25c2-f096-4462-ae63-4ba4702a9078';
const movieId = '6f1c0f55-2d4a-4b64-9a43-1d0f3c7b8e21';

describe('movie form model', () => {
  it('starts a create form as Draft with explicit empty collections', () => {
    expect(createEmptyMovieForm()).toEqual({
      title: '', slug: '', description: '', releaseYear: '', maturityRating: '', genres: [], people: [], status: 'DRAFT'
    });
    expect(validateMovieForm(createEmptyMovieForm())).toMatchObject({ title: expect.any(String), slug: expect.any(String), description: expect.any(String), genres: expect.any(String) });
  });

  it('accepts each supported status/rating, both year bounds and empty credits', () => {
    for (const status of ['DRAFT', 'PUBLISHED', 'ARCHIVED']) {
      for (const maturityRating of ['', 'P', 'K', 'T13', 'T16', 'T18']) {
        for (const releaseYear of ['', '1888', '2100']) {
          expect(validateMovieForm(valid({ status, maturityRating, releaseYear }))).toEqual({});
        }
      }
    }
  });

  it.each(['1887', '2101', '1999.5', '2e3', 'Infinity', 'NaN', '-2000'])('rejects invalid release year %s', (releaseYear) => {
    expect(validateMovieForm(valid({ releaseYear })).releaseYear).toBeDefined();
  });

  it('suggests Vietnamese slugs with the existing ADR-009 helper', () => {
    expect(slugify('Đất Rừng Phương Nam')).toBe('dat-rung-phuong-nam');
  });

  it.each([' invalid ', 'Movie', 'movie--title', 'movie_title', 'movie-', '-movie', 'x'.repeat(256)])('rejects a slug the backend will not rewrite', (slug) => {
    expect(validateMovieForm(valid({ slug })).slug).toBeDefined();
  });

  it('checks Unicode code-point limits against the movie payload while person requests retain their raw limit', () => {
    expect(validateMovieForm(valid({ title: '🎬'.repeat(255), description: 'x'.repeat(5000), slug: 'x'.repeat(255) }))).toEqual({});
    expect(validateMovieForm(valid({ title: ` ${'x'.repeat(255)} `, description: `\n${'x'.repeat(5000)}\t` }))).toEqual({});
    expect(validateMovieForm(valid({ title: '🎬'.repeat(256) })).title).toBeDefined();
    expect(validateMovieForm(valid({ description: 'x'.repeat(5001) })).description).toBeDefined();
    expect(validatePersonName('🎭'.repeat(255))).toBeNull();
    expect(validatePersonName('🎭'.repeat(256))).not.toBeNull();
    expect(validatePersonName(` ${'x'.repeat(255)}`)).not.toBeNull();
    expect(validateGenreName(`  ${'a'.repeat(49)}   ${'b'.repeat(50)}  `)).toBeNull();
    expect(validateGenreName(`${'a'.repeat(50)} ${'b'.repeat(50)}`)).not.toBeNull();
  });

  it('mirrors raw control character checks while allowing multiline description and Unicode format characters', () => {
    expect(validateMovieForm(valid({ title: 'Title\u0085' })).title).toBeDefined();
    expect(validateMovieForm(valid({ description: '\nOne\tline\r\nTwo\n' })).description).toBeUndefined();
    expect(validateMovieForm(valid({ description: 'Description\u0000' })).description).toBeDefined();
    expect(validateMovieForm(valid({ title: 'Title\u200e' })).title).toBeUndefined();
    expect(validatePersonName('Name\t')).not.toBeNull();
    expect(validateGenreName('Drama\n')).not.toBeNull();
  });

  it('normalizes catalog whitespace, and rejects blank, empty-slug and too-long genre names', () => {
    expect(normalizeCatalogName(' \u00a0Đất\u2007  Rừng\u202f ')).toBe('Đất Rừng');
    expect(validateGenreName('x'.repeat(100))).toBeNull();
    expect(validateGenreName('x'.repeat(101))).not.toBeNull();
    expect(validateGenreName('!!!')).not.toBeNull();
    expect(validateGenreName('   ')).not.toBeNull();
    expect(validatePersonName('王')).toBeNull();
  });

  it('accepts up to ten genres but rejects duplicate slugs across accents/case', () => {
    expect(validateMovieForm(valid({ genres: Array.from({ length: 10 }, (_, i) => `Genre ${i}`) }))).toEqual({});
    expect(validateMovieForm(valid({ genres: Array.from({ length: 11 }, (_, i) => `Genre ${i}`) })).genres).toBeDefined();
    expect(validateMovieForm(valid({ genres: ['Đất Rừng', 'dat rung'] }))['genres[1]']).toBeDefined();
  });

  it('requires deliberately selected person IDs, supported roles and distinct person/role pairs', () => {
    const credit = { key: 'one', personId, name: 'Person', role: 'ACTOR' };
    expect(validateMovieForm(valid({ people: [credit, { ...credit, key: 'two', role: 'DIRECTOR' }] }))).toEqual({});
    expect(validateMovieForm(valid({ people: [credit, { ...credit, key: 'two', personId: personId.toUpperCase() }] }))['people[1]']).toBeDefined();
    expect(validateMovieForm(valid({ people: [{ ...credit, personId: '' }] }))['people[0].personId']).toBeDefined();
    expect(validateMovieForm(valid({ people: [{ ...credit, role: 'UNKNOWN' }] }))['people[0].role']).toBeDefined();
    expect(validateMovieForm(valid({ status: 'UNKNOWN', maturityRating: 'UNKNOWN' }))).toMatchObject({ status: expect.any(String), maturityRating: expect.any(String) });
  });

  it('enforces the 50-credit limit without changing credit order', () => {
    const people = Array.from({ length: 50 }, (_, index) => ({
      key: `row-${index}`, personId: `${String(index).padStart(8, '0')}-f096-4462-ae63-4ba4702a9078`, name: `Person ${index}`, role: 'ACTOR'
    }));
    expect(validateMovieForm(valid({ people }))).toEqual({});
    expect(toMoviePayload(valid({ people })).people.map((credit) => credit.personId)).toEqual(people.map((credit) => credit.personId));
    expect(validateMovieForm(valid({ people: [...people, { ...people[0], key: 'extra' }] })).people).toBeDefined();
  });

  it('sends full replacements with nulls, normalized names, canonical IDs and no UI keys or stale name', () => {
    expect(toMoviePayload(valid({
      title: '  Two  spaces  ', description: ' Description\ntext ', genres: [' Đất   Rừng '],
      people: [{ key: 'ui-only', personId: personId.toUpperCase(), name: 'Display only', role: 'ACTOR' }]
    }))).toEqual({
      title: 'Two  spaces', slug: 'movie', description: 'Description\ntext', releaseYear: null, maturityRating: null,
      genres: ['Đất Rừng'], people: [{ personId, role: 'ACTOR' }], status: 'DRAFT'
    });
  });

  it('prefills edit values without mutating input and preserves UNKNOWN values for explicit correction', () => {
    const movie: MovieDetail = {
      id: movieId, title: 'Movie', slug: 'movie', description: 'Description', releaseYear: 2020,
      maturityRating: 'UNKNOWN', genres: ['Drama'], people: [{ personId, name: 'Person', role: 'UNKNOWN' }],
      status: 'ARCHIVED', videoStatus: null, posterUrl: null, averageRating: null,
      userRating: null, inWatchlist: false, playbackProgress: null
    };
    const values = movieDetailToForm(movie);
    expect(values.releaseYear).toBe('2020');
    expect(values.status).toBe('ARCHIVED');
    expect(values.people[0]).toMatchObject({ personId, role: 'UNKNOWN', key: `${personId}-UNKNOWN-0` });
    expect(validateMovieForm(values)).toHaveProperty('maturityRating');
    values.genres.push('Action');
    expect(movie.genres).toEqual(['Drama']);
  });
});

describe('server field-error mapping', () => {
  it('maps known scalar, genre and credit fields while keeping unknown fields in the summary', () => {
    const values = valid({ people: [{ key: 'row', personId, name: 'Person', role: 'ACTOR' }] });
    expect(mapMovieFormError(apiError(400, 'VALIDATION_ERROR', [
      { field: 'title', message: 'Title rejected' },
      { field: 'genres[0]', message: 'Genre rejected' },
      { field: 'people[0].personId', message: 'is required because more than one person has this name' },
      { field: 'people[0].name', message: 'Name rejected' },
      { field: 'people[9].name', message: 'Out of range' },
      { field: 'genres[01]', message: 'Noncanonical index' },
      { field: 'future', message: 'New field rejected' },
      { field: '__proto__', message: 'Untrusted field' }
    ]), values)).toEqual({
      fields: { title: 'Title rejected', 'genres[0]': 'Genre rejected', 'people[0].personId': 'Several people have this name; pick one.', 'people[0].name': 'Name rejected' },
      general: ['people[9].name: Out of range', 'genres[01]: Noncanonical index', 'future: New field rejected', '__proto__: Untrusted field']
    });
  });

  it('maps slug conflicts and preserves request IDs for troubleshooting', () => {
    expect(mapMovieFormError(apiError(409, 'MOVIE_SLUG_CONFLICT'))).toEqual({ fields: { slug: 'This slug is already used' }, general: [] });
    expect(mapMovieFormError(new ApiRequestError(409, {
      timestamp: '2026-10-28T00:00:00Z', status: 409, code: 'MOVIE_SLUG_CONFLICT', message: 'Conflict', requestId: 'slug-request'
    }))).toEqual({ fields: { slug: 'This slug is already used' }, general: ['Request ID: slug-request'] });
    const failure = new ApiRequestError(500, {
      timestamp: '2026-10-28T00:00:00Z', status: 500, code: 'INTERNAL_ERROR', message: 'Try again', requestId: 'request-id'
    });
    expect(mapMovieFormError(failure)).toEqual({ fields: {}, general: ['Try again', 'Request ID: request-id'] });
  });

  it('keeps unexpected transport failures in the summary', () => {
    expect(mapMovieFormError(new TypeError('Failed to fetch')).general).toEqual(['The request could not be completed. Please try again.']);
  });
});

function valid(overrides: Partial<MovieFormValues> = {}): MovieFormValues {
  return { ...createEmptyMovieForm(), title: 'Movie', slug: 'movie', description: 'Description', genres: ['Drama'], ...overrides };
}
function apiError(status: number, code: string, fieldErrors: { field: string; message: string }[] = []): ApiRequestError {
  return new ApiRequestError(status, { timestamp: '2026-10-28T00:00:00Z', status, code, message: 'Request rejected', fieldErrors });
}
