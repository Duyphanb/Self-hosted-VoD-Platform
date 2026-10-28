import { describe, expect, it } from 'vitest';
import { adminMovieListSearchParams, clearRejectedAdminFilters, parseAdminMovieListParams, toAdminMovieListQuery } from './adminMovieListParams';

describe('admin movie list URL state', () => {
  it('defaults to every status, latest first, on the first page', () => {
    const params = parseAdminMovieListParams(new URLSearchParams());
    expect(params).toEqual({ status: null, sort: 'latest', page: 1 });
    expect(toAdminMovieListQuery(params)).toEqual({ status: null, sort: 'latest', page: 0, size: 20 });
    expect(adminMovieListSearchParams(params).toString()).toBe('');
  });

  it('round trips status/sort/page and sends a zero-based page to the API', () => {
    const params = parseAdminMovieListParams(new URLSearchParams('status=ARCHIVED&sort=title&page=3'));
    expect(params).toEqual({ status: 'ARCHIVED', sort: 'title', page: 3 });
    expect(Object.fromEntries(adminMovieListSearchParams(params))).toEqual({ status: 'ARCHIVED', sort: 'title', page: '3' });
    expect(toAdminMovieListQuery(params).page).toBe(2);
  });

  it.each(['draft', 'UNKNOWN', '', 'PUBLISHED<script>'])('does not send unknown status %s to the API', (status) => {
    expect(parseAdminMovieListParams(new URLSearchParams({ status })).status).toBeNull();
  });

  it.each(['0', '-1', '2.5', 'abc'])('defaults an invalid page %s to one', (page) => {
    expect(parseAdminMovieListParams(new URLSearchParams({ page })).page).toBe(1);
  });

  it('clamps excessive pages and defaults unknown sort values', () => {
    expect(parseAdminMovieListParams(new URLSearchParams('page=99999999&sort=unknown')))
      .toEqual({ status: null, page: 10001, sort: 'latest' });
  });

  it('clears only rejected filters and resets pagination', () => {
    const params = { status: 'DRAFT', sort: 'title', page: 3 } as const;
    expect(clearRejectedAdminFilters(params, ['status'])).toEqual({ status: null, sort: 'title', page: 1 });
    expect(clearRejectedAdminFilters(params, ['sort'])).toEqual({ status: 'DRAFT', sort: 'latest', page: 1 });
    expect(clearRejectedAdminFilters(params, [])).toEqual({ status: null, sort: 'latest', page: 1 });
    expect(clearRejectedAdminFilters(params, ['page'])).toBeNull();
    expect(clearRejectedAdminFilters({ status: null, sort: 'latest', page: 1 }, [])).toBeNull();
  });
});
