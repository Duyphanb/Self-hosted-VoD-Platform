import { describe, expect, it } from 'vitest';
import { getAdminMoviesReturnTo } from './adminMovieNavigation';

describe('admin movie return navigation', () => {
  it('keeps list filters, sort and page on a valid local return URL', () => {
    expect(getAdminMoviesReturnTo({ returnTo: '/admin/titles?status=PUBLISHED&page=2&sort=title' }))
      .toBe('/admin/titles?status=PUBLISHED&page=2&sort=title');
  });
  it.each([undefined, null, {}, { returnTo: '/admin/titles/new' }, { returnTo: '//example.com/admin/movies' },
    { returnTo: 'https://example.com/admin/movies' }, { returnTo: '/admin/movies#other' }, { returnTo: '/admin/titles?x=\nvalue' }])(
    'falls back to the list for an unsupported return target', (state) => {
      expect(getAdminMoviesReturnTo(state)).toBe('/admin/titles');
    }
  );
});
