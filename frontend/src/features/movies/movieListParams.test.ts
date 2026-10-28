import { describe, expect, it } from 'vitest';
import { MAX_LIST_PAGE } from '../../lib/pagination';
import {
  clearRejectedFilters,
  DEFAULT_MOVIE_LIST_PARAMS,
  hasActiveFilters,
  type MovieListParams,
  movieListSearchParams,
  parseMovieListParams,
  toMovieListQuery
} from './movieListParams';

describe('parseMovieListParams', () => {
  it('uses the defaults for an empty query string', () => {
    expect(parse('')).toEqual({ genre: null, year: null, sort: 'latest', page: 1 });
    expect(parse('')).toEqual(DEFAULT_MOVIE_LIST_PARAMS);
  });

  it('reads valid values', () => {
    expect(parse('?genre=Science%20Fiction&year=1999&sort=title&page=3')).toEqual({
      genre: 'Science Fiction',
      year: 1999,
      sort: 'title',
      page: 3
    });
  });

  it('trims the genre and keeps 1 to 120 characters', () => {
    expect(parse('?genre=%20%20Drama%20').genre).toBe('Drama');
    expect(parse(`?genre=${'a'.repeat(120)}`).genre).toBe('a'.repeat(120));
    expect(parse(`?genre=${'a'.repeat(121)}`).genre).toBeNull();
    expect(parse('?genre=').genre).toBeNull();
    expect(parse('?genre=%20%20%20').genre).toBeNull();
  });

  it('counts the genre length in code points, as the API does', () => {
    // 1 + 119 emoji: 120 code points but 239 UTF-16 code units.
    const genre = `a${'😀'.repeat(119)}`;
    expect(parse(`?genre=${encodeURIComponent(genre)}`).genre).toBe(genre);
    expect(parse(`?genre=${encodeURIComponent(`${genre}😀`)}`).genre).toBeNull();
  });

  it('drops a genre whose slug is empty, which the API would reject', () => {
    for (const genre of ['!!!', '#', '-', '"', 'Драма', '東京', '😀']) {
      expect(parse(`?genre=${encodeURIComponent(genre)}`).genre, genre).toBeNull();
    }
    for (const genre of ['Hài', 'Sci-Fi & Fantasy', '#1', 'Ωmega 2']) {
      expect(parse(`?genre=${encodeURIComponent(genre)}`).genre, genre).toBe(genre);
    }
  });

  it('keeps years from 1888 to 2100 and drops anything else', () => {
    expect(parse('?year=1888').year).toBe(1888);
    expect(parse('?year=2100').year).toBe(2100);
    for (const year of ['1887', '2101', '0', '-2000', '20x0', '1999.5', '01999', '', 'abcd']) {
      expect(parse(`?year=${encodeURIComponent(year)}`).year, year).toBeNull();
    }
  });

  it('accepts only the contract sort values', () => {
    expect(parse('?sort=latest').sort).toBe('latest');
    expect(parse('?sort=title').sort).toBe('title');
    expect(parse('?sort=TITLE').sort).toBe('latest');
    expect(parse('?sort=rating').sort).toBe('latest');
  });

  it('turns an invalid page into page 1', () => {
    for (const page of ['0', '-1', '1.5', 'abc', '', ' 2', '2e3']) {
      expect(parse(`?page=${encodeURIComponent(page)}`).page, page).toBe(1);
    }
  });

  it('clamps a page above the API limit to the last accepted page', () => {
    expect(parse('?page=10001').page).toBe(MAX_LIST_PAGE);
    expect(parse('?page=10002').page).toBe(MAX_LIST_PAGE);
    expect(parse('?page=99999999999999999999').page).toBe(MAX_LIST_PAGE);
  });
});

describe('toMovieListQuery', () => {
  it('converts the 1-based URL page into the 0-based API page with the contract page size', () => {
    expect(toMovieListQuery(DEFAULT_MOVIE_LIST_PARAMS)).toEqual({
      page: 0,
      size: 20,
      genre: null,
      year: null,
      sort: 'latest'
    });
    expect(toMovieListQuery({ genre: 'Drama', year: 2001, sort: 'title', page: 2 })).toEqual({
      page: 1,
      size: 20,
      genre: 'Drama',
      year: 2001,
      sort: 'title'
    });
  });

  it('keeps the highest URL page within the API maximum of 10000', () => {
    expect(toMovieListQuery(parse('?page=50000')).page).toBe(10_000);
  });
});

describe('movieListSearchParams', () => {
  it('leaves out default values', () => {
    expect(movieListSearchParams(DEFAULT_MOVIE_LIST_PARAMS).toString()).toBe('');
  });

  it('round-trips every value through the URL', () => {
    const params = { genre: 'Hài & Drama', year: 2024, sort: 'title' as const, page: 4 };
    expect(parseMovieListParams(movieListSearchParams(params))).toEqual(params);
  });
});

describe('clearRejectedFilters', () => {
  const filtered: MovieListParams = { genre: 'Western', year: 2000, sort: 'title', page: 3 };

  it.each([
    ['genre', ['genre'], { genre: null, year: 2000 }],
    ['year', ['year'], { genre: 'Western', year: null }],
    ['genre and year', ['year', 'genre'], { genre: null, year: null }],
    ['no field, so both', [], { genre: null, year: null }]
  ])('clears the filters an error names: %s', (_label, fields, expected) => {
    expect(clearRejectedFilters(filtered, fields)).toEqual({ ...expected, sort: 'title', page: 1 });
  });

  it('keeps the filters when the error names only other fields', () => {
    expect(clearRejectedFilters(filtered, ['page'])).toBeNull();
    expect(clearRejectedFilters(filtered, ['size', 'sort'])).toBeNull();
  });

  it('returns null when no named filter is active, so the error is shown instead', () => {
    expect(clearRejectedFilters({ ...filtered, genre: null, year: null }, [])).toBeNull();
    expect(clearRejectedFilters({ ...filtered, genre: null }, ['genre'])).toBeNull();
    expect(clearRejectedFilters({ ...filtered, year: null }, ['year'])).toBeNull();
  });
});

describe('hasActiveFilters', () => {
  it('counts genre and year but not sort or page', () => {
    expect(hasActiveFilters(DEFAULT_MOVIE_LIST_PARAMS)).toBe(false);
    expect(hasActiveFilters({ genre: 'Drama', year: null })).toBe(true);
    expect(hasActiveFilters({ genre: null, year: 2000 })).toBe(true);
    expect(hasActiveFilters(parse('?sort=title&page=3'))).toBe(false);
  });
});

function parse(search: string) {
  return parseMovieListParams(new URLSearchParams(search));
}
