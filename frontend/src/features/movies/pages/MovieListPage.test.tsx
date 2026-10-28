import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiClient, AuthenticationSupersededError } from '../../../lib/api/client';
import type { AuthResponse, PageMeta, UserProfile } from '../../../lib/api/types';
import { AuthProvider } from '../../auth/AuthContext';
import { AUTH_SESSION_STORAGE_KEY, createAuthSession, writeAuthSession } from '../../auth/authStorage';
import { ProtectedRoute } from '../../auth/RouteGuards';
import { movieKeys, type MovieSummary } from '../moviesApi';
import { MovieListPage } from './MovieListPage';

const regularUser: UserProfile = {
  id: '3fcf25c2-f096-4462-ae63-4ba4702a9078',
  email: 'viewer@example.com',
  displayName: 'Viewer',
  roles: ['ROLE_USER']
};

const otherUser: UserProfile = {
  id: 'b5f7c0f4-1d2e-4c3b-9a8f-7e6d5c4b3a21',
  email: 'other@example.com',
  displayName: 'Other viewer',
  roles: ['ROLE_USER']
};

const FILTERS_CLEARED = 'The server did not accept those filters, so they were cleared.';

const fetchMock = vi.fn<typeof fetch>();

describe('MovieListPage', () => {
  const scrollIntoView = vi.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    scrollIntoView.mockReset();
    vi.stubGlobal('fetch', fetchMock);
    // jsdom does not implement scrolling.
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value: scrollIntoView
    });
  });

  afterEach(() => {
    Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView');
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  describe('list', () => {
    it('requests the first page with the bearer token and renders each card as one link', async () => {
      serveMovies(() => moviePage([
        movieSummary(1, { title: 'The Long Night', releaseYear: 2019, genres: ['Drama', 'Thriller', 'Mystery'] }),
        movieSummary(2, { title: 'Untitled Draft Cut', releaseYear: null, genres: [] })
      ]));
      renderMovieList();

      const list = await screen.findByRole('list');
      const cards = within(list).getAllByRole('listitem');
      expect(cards).toHaveLength(2);
      expect(within(cards[0]).getByRole('heading', { level: 2, name: 'The Long Night' })).toBeInTheDocument();
      expect(within(cards[0]).getByText('2019', { selector: 'p' })).toBeInTheDocument();
      expect(within(cards[0]).getByText('Drama · Thriller')).toHaveClass('line-clamp-2');
      expect(within(cards[0]).queryByText(/Mystery/)).not.toBeInTheDocument();
      expect(within(cards[1]).getByRole('heading', { level: 2, name: 'Untitled Draft Cut' })).toBeInTheDocument();
      expect(cards[1]).not.toHaveTextContent(/\d{4}/);

      // The whole card is one link to the detail page, with nothing interactive inside it.
      cards.forEach((card, index) => {
        const links = within(card).getAllByRole('link');
        expect(links).toHaveLength(1);
        expect(links[0]).toHaveAttribute('href', `/movies/${movieSummary(index + 1).id}`);
        expect(within(links[0]).getByRole('heading', { level: 2 })).toBeInTheDocument();
        expect(links[0].querySelectorAll('a, button, input, select, textarea, [tabindex]')).toHaveLength(0);
      });

      expect(apiQueries()).toEqual([{ page: '0', size: '20', sort: 'latest' }]);
      const [input, init] = fetchMock.mock.calls[0];
      expect(new URL(String(input), 'http://localhost').pathname).toBe('/api/v1/movies');
      expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer access-token');

      expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
      expect(screen.getByRole('heading', { level: 1, name: 'Movies' })).toBeInTheDocument();
      expect(liveRegion()).toHaveTextContent('2 movies, page 1 of 1');
      expect(screen.getByRole('search', { name: 'Movie filters' })).toBeInTheDocument();
      expect(screen.getByLabelText('Genre')).toHaveValue('');
      expect(screen.getByLabelText('Year')).toHaveValue('');
      expect(screen.getByLabelText('Sort by')).toHaveValue('latest');
      expect(screen.queryByRole('navigation', { name: 'Pagination' })).not.toBeInTheDocument();
    });

    it('says "1 movie" for a single result', async () => {
      serveMovies(() => moviePage([movieSummary(1)]));
      renderMovieList();

      await screen.findByRole('heading', { level: 2, name: 'Movie 1' });
      expect(liveRegion()).toHaveTextContent('1 movie, page 1 of 1');
    });

    it('restores genre, year, sort and page from the URL', async () => {
      serveMovies(() => moviePage([movieSummary(41)], { page: 2, totalElements: 81, totalPages: 5 }));
      renderMovieList(['/movies?genre=Drama&year=2019&sort=title&page=3']);

      await screen.findByRole('heading', { level: 2, name: 'Movie 41' });
      expect(apiQueries()).toEqual([{ page: '2', size: '20', genre: 'Drama', year: '2019', sort: 'title' }]);
      expect(screen.getByLabelText('Genre')).toHaveValue('Drama');
      expect(screen.getByLabelText('Year')).toHaveValue('2019');
      expect(screen.getByLabelText('Sort by')).toHaveValue('title');
      const pagination = screen.getByRole('navigation', { name: 'Pagination' });
      expect(within(pagination).getByRole('button', { name: 'Page 3' })).toHaveAttribute('aria-current', 'page');
      expect(within(pagination).getByText('Page 3 of 5')).toBeInTheDocument();
      expect(liveRegion()).toHaveTextContent('81 movies, page 3 of 5');
    });

    it('never sends a genre from the URL whose slug is empty', async () => {
      serveMovies(() => moviePage([movieSummary(1)]));
      renderMovieList(['/movies?genre=!!!&sort=title']);

      await screen.findByRole('heading', { level: 2, name: 'Movie 1' });
      expect(apiQueries()).toEqual([{ page: '0', size: '20', sort: 'title' }]);
      expect(screen.getByLabelText('Genre')).toHaveValue('');
    });

    it('renders HTML in movie metadata as literal text', async () => {
      const title = '<img src=x onerror="window.__xss = 1">';
      serveMovies(() => moviePage([movieSummary(1, {
        title,
        description: '<img src=x onerror=alert(1)><script>alert(2)</script>',
        genres: ['<b>Bold</b>', '<a href="javascript:alert(3)">Link</a>']
      })]));
      const { container } = renderMovieList();

      expect(await screen.findByRole('heading', { level: 2, name: title })).toBeInTheDocument();
      expect(screen.getByText('<b>Bold</b> · <a href="javascript:alert(3)">Link</a>')).toBeInTheDocument();
      expect(container.querySelector('img, script, b')).toBeNull();
      const anchors = [...container.querySelectorAll('a')];
      expect(anchors.map((anchor) => anchor.getAttribute('href'))).toEqual([`/movies/${movieSummary(1).id}`]);
      expect((window as Window & { __xss?: number }).__xss).toBeUndefined();
    });
  });

  describe('pages', () => {
    it('sends the 0-based page to the API on Next, adds a history entry and focuses the heading', async () => {
      serveMovies((query) => moviePage(
        [movieSummary(Number(query.page) + 1)],
        { page: Number(query.page), totalElements: 45, totalPages: 3 }
      ));
      renderMovieList();

      await screen.findByRole('heading', { level: 2, name: 'Movie 1' });
      fireEvent.click(screen.getByRole('button', { name: 'Next' }));

      expect(await screen.findByRole('heading', { level: 2, name: 'Movie 2' })).toBeInTheDocument();
      expect(lastApiQuery()).toEqual({ page: '1', size: '20', sort: 'latest' });
      expect(currentLocation()).toBe('/movies?page=2');
      expect(scrollIntoView).toHaveBeenCalledWith({ block: 'start' });
      expect(listHeading()).toHaveFocus();
      expect(liveRegion()).toHaveTextContent('45 movies, page 2 of 3');

      fireEvent.click(screen.getByRole('button', { name: 'History back' }));
      await waitFor(() => expect(currentLocation()).toBe('/movies'));
      expect(await screen.findByRole('heading', { level: 2, name: 'Movie 1' })).toBeInTheDocument();
    });

    it('keeps the previous page visible while the next page of the same filters loads', async () => {
      const held = holdResponses((query) => query.page !== '0');
      serveMovies((query) => held.respond(query, moviePage([movieSummary(Number(query.page) + 1)], {
        page: Number(query.page),
        totalElements: 45,
        totalPages: 3
      })));
      renderMovieList();

      await screen.findByRole('heading', { level: 2, name: 'Movie 1' });
      fireEvent.click(screen.getByRole('button', { name: 'Page 2' }));

      await waitFor(() => expect(held.count()).toBe(1));
      const previousCard = screen.getByRole('heading', { level: 2, name: 'Movie 1' });
      expect(previousCard.closest('ul')?.parentElement).toHaveAttribute('aria-busy', 'true');
      expect(screen.queryByText('Loading movies…')).not.toBeInTheDocument();
      expect(liveRegion()).toBeEmptyDOMElement();

      held.release();
      const nextCard = await screen.findByRole('heading', { level: 2, name: 'Movie 2' });
      expect(nextCard.closest('ul')?.parentElement).toHaveAttribute('aria-busy', 'false');
      expect(liveRegion()).toHaveTextContent('45 movies, page 2 of 3');
    });

    it('offers the last page when the URL page is beyond the result, replacing the history entry', async () => {
      const held = holdResponses((query) => query.page === '2');
      serveMovies((query) => (query.page === '2'
        ? held.respond(query, moviePage([movieSummary(41)], { page: 2, totalElements: 41, totalPages: 3 }))
        : moviePage([], { page: Number(query.page), totalElements: 41, totalPages: 3 })));
      renderMovieList(['/elsewhere', '/movies?page=9']);

      expect(await screen.findByRole('heading', { level: 2, name: "This page doesn't exist" })).toBeInTheDocument();
      expect(screen.getByText('This list ends at page 3.')).toBeInTheDocument();
      expect(apiQueries()[0]).toEqual({ page: '8', size: '20', sort: 'latest' });
      expect(liveRegion()).toBeEmptyDOMElement();
      const goToLastPage = screen.getByRole('button', { name: 'Go to last page' });
      goToLastPage.focus();
      fireEvent.click(goToLastPage);

      await waitFor(() => expect(currentLocation()).toBe('/movies?page=3'));
      expect(listHeading()).toHaveFocus();
      // The empty result of page 9 is not kept on screen while page 3 loads.
      await waitFor(() => expect(held.count()).toBe(1));
      expect(screen.getByText('Loading movies…')).toBeInTheDocument();
      expect(screen.queryByRole('heading', { level: 2, name: "This page doesn't exist" })).not.toBeInTheDocument();

      held.release();
      expect(await screen.findByRole('heading', { level: 2, name: 'Movie 41' })).toBeInTheDocument();
      expect(lastApiQuery()).toEqual({ page: '2', size: '20', sort: 'latest' });
      expect(liveRegion()).toHaveTextContent('41 movies, page 3 of 3');

      // Back skips the page that does not exist.
      fireEvent.click(screen.getByRole('button', { name: 'History back' }));
      await waitFor(() => expect(currentLocation()).toBe('/elsewhere'));
    });
  });

  describe('filters', () => {
    it('goes back to page 1 and replaces the history entry when a filter changes', async () => {
      serveMovies((query) => moviePage([movieSummary(1)], {
        page: Number(query.page),
        totalElements: 81,
        totalPages: 5
      }));
      renderMovieList(['/elsewhere', '/movies?sort=title&page=3']);

      await screen.findByRole('heading', { level: 2, name: 'Movie 1' });
      const genre = screen.getByLabelText('Genre');
      fireEvent.change(genre, { target: { value: '  Drama ' } });
      fireEvent.keyDown(genre, { key: 'Enter' });

      await waitFor(() => expect(currentLocation()).toBe('/movies?genre=Drama&sort=title'));
      await waitFor(() => expect(lastApiQuery()).toEqual({
        page: '0',
        size: '20',
        genre: 'Drama',
        sort: 'title'
      }));
      expect(genre).toHaveValue('  Drama ');

      fireEvent.click(screen.getByRole('button', { name: 'History back' }));
      await waitFor(() => expect(currentLocation()).toBe('/elsewhere'));
    });

    it('applies a typed genre with Apply, not while typing or when the field loses focus', async () => {
      serveMovies(() => moviePage([movieSummary(1)]));
      renderMovieList(['/movies?page=2']);

      await screen.findByRole('heading', { level: 2, name: 'Movie 1' });
      const genre = screen.getByLabelText('Genre');
      genre.focus();
      fireEvent.change(genre, { target: { value: 'Dra' } });
      fireEvent.change(genre, { target: { value: 'Drama' } });
      const apply = screen.getByRole('button', { name: 'Apply' });
      apply.focus();
      await settle();
      expect(currentLocation()).toBe('/movies?page=2');
      expect(fetchMock).toHaveBeenCalledTimes(1);

      fireEvent.click(apply);

      await waitFor(() => expect(currentLocation()).toBe('/movies?genre=Drama'));
      await waitFor(() => expect(lastApiQuery()).toEqual({ page: '0', size: '20', genre: 'Drama', sort: 'latest' }));
      expect(apiQueries().some((query) => query.genre === 'Dra')).toBe(false);
    });

    it('opens a movie clicked while a genre is still being typed, without applying it', async () => {
      serveMovies(() => moviePage([movieSummary(1), movieSummary(2)]));
      renderMovieList();

      await screen.findByRole('heading', { level: 2, name: 'Movie 2' });
      const genre = screen.getByLabelText('Genre');
      genre.focus();
      fireEvent.change(genre, { target: { value: 'Noir' } });
      // Pressing the mouse on the card moves focus there before the click lands.
      const card = screen.getByRole('link', { name: /Movie 2/ });
      card.focus();
      await settle();
      expect(card).toBeInTheDocument();

      fireEvent.click(card);

      await waitFor(() => expect(currentLocation()).toBe(`/movies/${movieSummary(2).id}`));
      expect(apiQueries().some((query) => query.genre !== undefined)).toBe(false);
    });

    it('clears the filters from the empty result while another genre is still being typed', async () => {
      serveMovies((query) => (query.genre ? moviePage([]) : moviePage([movieSummary(1)])));
      renderMovieList(['/movies?genre=Western']);

      const heading = await screen.findByRole('heading', { level: 2, name: 'No movies match these filters' });
      const genre = screen.getByLabelText('Genre');
      genre.focus();
      fireEvent.change(genre, { target: { value: 'Westerns' } });
      const clear = within(heading.parentElement as HTMLElement).getByRole('button', { name: 'Clear filters' });
      clear.focus();
      await settle();
      expect(clear).toBeInTheDocument();

      fireEvent.click(clear);

      await waitFor(() => expect(currentLocation()).toBe('/movies'));
      expect(await screen.findByRole('heading', { level: 2, name: 'Movie 1' })).toBeInTheDocument();
      expect(genre).toHaveValue('');
      expect(apiQueries().map((query) => query.genre)).toEqual(['Western', undefined]);
    });

    it('shows the loading state, not the previous result or pagination, while a filter change loads', async () => {
      const held = holdResponses((query) => query.genre === 'Noir');
      serveMovies((query) => (query.genre === 'Noir'
        ? held.respond(query, moviePage([movieSummary(7)], { totalElements: 3, totalPages: 1 }))
        : moviePage([movieSummary(1)], { totalElements: 81, totalPages: 5 })));
      renderMovieList();

      await screen.findByRole('heading', { level: 2, name: 'Movie 1' });
      expect(screen.getByRole('navigation', { name: 'Pagination' })).toBeInTheDocument();
      const genre = screen.getByLabelText('Genre');
      fireEvent.change(genre, { target: { value: 'Noir' } });
      fireEvent.keyDown(genre, { key: 'Enter' });

      await waitFor(() => expect(held.count()).toBe(1));
      expect(screen.getByText('Loading movies…')).toBeInTheDocument();
      expect(screen.queryByRole('heading', { level: 2, name: 'Movie 1' })).not.toBeInTheDocument();
      expect(screen.queryByRole('navigation', { name: 'Pagination' })).not.toBeInTheDocument();

      held.release();
      expect(await screen.findByRole('heading', { level: 2, name: 'Movie 7' })).toBeInTheDocument();
      expect(liveRegion()).toHaveTextContent('3 movies, page 1 of 1');
    });

    it('flags a genre whose slug is empty and never sends it', async () => {
      serveMovies(() => moviePage([movieSummary(1)]));
      renderMovieList();

      await screen.findByRole('heading', { level: 2, name: 'Movie 1' });
      const genre = screen.getByLabelText('Genre');
      fireEvent.change(genre, { target: { value: '#' } });
      fireEvent.keyDown(genre, { key: 'Enter' });
      fireEvent.click(screen.getByRole('button', { name: 'Apply' }));
      await settle();

      expect(screen.getByRole('alert')).toHaveTextContent('Enter a genre with at least one Latin letter (a–z) or digit.');
      expect(currentLocation()).toBe('/movies');
      expect(apiQueries()).toEqual([{ page: '0', size: '20', sort: 'latest' }]);
    });

    it('applies a sort change at once and resets the page', async () => {
      serveMovies((query) => moviePage([movieSummary(1)], {
        page: Number(query.page),
        totalElements: 45,
        totalPages: 3
      }));
      renderMovieList(['/movies?page=2']);

      await screen.findByRole('heading', { level: 2, name: 'Movie 1' });
      fireEvent.change(screen.getByLabelText('Sort by'), { target: { value: 'title' } });

      await waitFor(() => expect(currentLocation()).toBe('/movies?sort=title'));
      await waitFor(() => expect(lastApiQuery()).toEqual({ page: '0', size: '20', sort: 'title' }));
    });

    it('does not apply a year outside 1888-2100', async () => {
      serveMovies(() => moviePage([movieSummary(1)]));
      renderMovieList();

      await screen.findByRole('heading', { level: 2, name: 'Movie 1' });
      const year = screen.getByLabelText('Year');
      fireEvent.change(year, { target: { value: '1700' } });
      fireEvent.keyDown(year, { key: 'Enter' });

      expect(screen.getByText('Enter a year from 1888 to 2100.')).toBeInTheDocument();
      expect(year).toHaveAttribute('aria-invalid', 'true');
      expect(year).toHaveAccessibleDescription('Enter a year from 1888 to 2100.');
      expect(currentLocation()).toBe('/movies');

      fireEvent.change(year, { target: { value: '1999' } });
      fireEvent.keyDown(year, { key: 'Enter' });

      await waitFor(() => expect(currentLocation()).toBe('/movies?year=1999'));
      expect(year).toHaveAttribute('aria-invalid', 'false');
      await waitFor(() => expect(lastApiQuery()).toEqual({
        page: '0',
        size: '20',
        year: '1999',
        sort: 'latest'
      }));
    });

    it('clears genre and year from the toolbar, keeps the sort and focuses Genre', async () => {
      serveMovies(() => moviePage([movieSummary(1)], { totalElements: 45, totalPages: 3 }));
      renderMovieList(['/movies?genre=Drama&year=2019&sort=title&page=2']);

      await screen.findByRole('heading', { level: 2, name: 'Movie 1' });
      const clear = screen.getByRole('button', { name: 'Clear filters' });
      clear.focus();
      fireEvent.click(clear);

      await waitFor(() => expect(currentLocation()).toBe('/movies?sort=title'));
      expect(screen.getByLabelText('Genre')).toHaveValue('');
      expect(screen.getByLabelText('Year')).toHaveValue('');
      expect(screen.getByRole('button', { name: 'Clear filters' })).toBeDisabled();
      expect(screen.getByLabelText('Genre')).toHaveFocus();
    });

    it('updates the filter fields when history changes the URL', async () => {
      serveMovies(() => moviePage([movieSummary(1)]));
      renderMovieList(['/movies?genre=Drama&year=2001', '/movies?genre=Comedy']);

      await screen.findByRole('heading', { level: 2, name: 'Movie 1' });
      expect(screen.getByLabelText('Genre')).toHaveValue('Comedy');
      expect(screen.getByLabelText('Year')).toHaveValue('');

      fireEvent.click(screen.getByRole('button', { name: 'History back' }));

      await waitFor(() => expect(screen.getByLabelText('Genre')).toHaveValue('Drama'));
      expect(screen.getByLabelText('Year')).toHaveValue('2001');
      await waitFor(() => expect(lastApiQuery()).toEqual({
        page: '0',
        size: '20',
        genre: 'Drama',
        year: '2001',
        sort: 'latest'
      }));
    });
  });

  describe('states', () => {
    it('shows a loading status with a reduced-motion skeleton', async () => {
      fetchMock.mockReturnValue(new Promise<Response>(() => undefined));
      renderMovieList();

      const label = await screen.findByText('Loading movies…');
      const status = label.closest('[role="status"]');
      expect(status).not.toBeNull();
      expect(status?.querySelectorAll('.animate-pulse.motion-reduce\\:animate-none').length).toBeGreaterThan(0);
      expect(liveRegion()).toBeEmptyDOMElement();
    });

    it('shows "No movies yet" when the catalog is empty', async () => {
      serveMovies(() => moviePage([]));
      renderMovieList();

      expect(await screen.findByRole('heading', { level: 2, name: 'No movies yet' })).toBeInTheDocument();
      expect(liveRegion()).toHaveTextContent('0 movies');
      expect(screen.queryByRole('navigation', { name: 'Pagination' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Clear filters' })).toBeDisabled();
    });

    it('offers Clear filters when no movie matches, keeping the sort and focusing the heading', async () => {
      const held = holdResponses((query) => query.genre === undefined);
      serveMovies((query) => (query.genre
        ? moviePage([])
        : held.respond(query, moviePage([movieSummary(1)]))));
      renderMovieList(['/movies?genre=Western&sort=title&page=2']);

      const heading = await screen.findByRole('heading', { level: 2, name: 'No movies match these filters' });
      expect(liveRegion()).toHaveTextContent('0 movies');
      const clear = within(heading.parentElement as HTMLElement).getByRole('button', { name: 'Clear filters' });
      clear.focus();
      fireEvent.click(clear);

      await waitFor(() => expect(currentLocation()).toBe('/movies?sort=title'));
      expect(listHeading()).toHaveFocus();
      // The previous empty result is not shown while the unfiltered list loads.
      await waitFor(() => expect(held.count()).toBe(1));
      expect(screen.getByText('Loading movies…')).toBeInTheDocument();
      expect(screen.queryByRole('heading', { level: 2, name: 'No movies match these filters' })).not.toBeInTheDocument();
      expect(screen.queryByRole('heading', { level: 2, name: 'No movies yet' })).not.toBeInTheDocument();

      held.release();
      expect(await screen.findByRole('heading', { level: 2, name: 'Movie 1' })).toBeInTheDocument();
      expect(lastApiQuery()).toEqual({ page: '0', size: '20', sort: 'title' });
    });

    it('shows the error message and request id, retries on request and focuses the heading', async () => {
      fetchMock
        .mockResolvedValueOnce(jsonResponse({
          timestamp: '2026-10-28T00:00:00Z',
          status: 503,
          code: 'SERVICE_UNAVAILABLE',
          message: 'The catalog is temporarily unavailable.',
          requestId: 'req-7f3a'
        }, 503))
        .mockResolvedValueOnce(moviePage([movieSummary(1)]));
      renderMovieList();

      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('Movies could not be loaded');
      expect(alert).toHaveTextContent('The catalog is temporarily unavailable.');
      expect(alert).toHaveTextContent('Request ID: req-7f3a');
      expect(liveRegion()).toBeEmptyDOMElement();

      const retry = within(alert).getByRole('button', { name: 'Retry' });
      retry.focus();
      fireEvent.click(retry);
      expect(listHeading()).toHaveFocus();

      expect(await screen.findByRole('heading', { level: 2, name: 'Movie 1' })).toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
      expect(fetchMock).toHaveBeenCalledTimes(2);
      expect(listHeading()).toHaveFocus();
    });
  });

  describe('filters rejected with 400', () => {
    it('clears only the filter the API names and keeps the other one', async () => {
      serveMovies((query) => (query.genre ? validationError(['genre']) : moviePage([movieSummary(1)])));
      renderMovieList(['/movies?genre=Western&year=2000&sort=title&page=2']);

      expect(await screen.findByRole('heading', { level: 2, name: 'Movie 1' })).toBeInTheDocument();
      expect(currentLocation()).toBe('/movies?year=2000&sort=title');
      expect(screen.getByRole('alert')).toHaveTextContent(FILTERS_CLEARED);
      expect(apiQueries()).toEqual([
        { page: '1', size: '20', genre: 'Western', year: '2000', sort: 'title' },
        { page: '0', size: '20', year: '2000', sort: 'title' }
      ]);
      expect(screen.getByLabelText('Genre')).toHaveValue('');
      expect(screen.getByLabelText('Year')).toHaveValue('2000');
      // The notice lives in the page, never in history state, so a reload cannot bring it back.
      expect(screen.getByTestId('location-state')).toHaveTextContent('null');
    });

    it('clears genre and year when the error names no field', async () => {
      serveMovies((query) => (query.genre || query.year ? validationError([]) : moviePage([movieSummary(1)])));
      renderMovieList(['/movies?genre=Western&year=2000&sort=title&page=2']);

      expect(await screen.findByRole('heading', { level: 2, name: 'Movie 1' })).toBeInTheDocument();
      expect(currentLocation()).toBe('/movies?sort=title');
      expect(screen.getByRole('alert')).toHaveTextContent(FILTERS_CLEARED);
      expect(screen.getByLabelText('Genre')).toHaveValue('');
      expect(screen.getByLabelText('Year')).toHaveValue('');
    });

    it.each([
      ['names only another field', '/movies?genre=Western', ['page']],
      ['arrives without any filter', '/movies?sort=title&page=2', []]
    ])('shows the error when a 400 %s', async (_label, entry, fields) => {
      serveMovies(() => validationError(fields));
      renderMovieList([entry]);

      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('Movies could not be loaded');
      expect(alert).toHaveTextContent('Invalid query parameters');
      expect(alert).not.toHaveTextContent(FILTERS_CLEARED);
      expect(currentLocation()).toBe(entry);
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it('does not show the notice again after Next and Back, Forward, or a new visit', async () => {
      serveMovies((query) => (query.genre
        ? validationError(['genre'])
        : moviePage([movieSummary(Number(query.page) + 1)], {
          page: Number(query.page),
          totalElements: 45,
          totalPages: 3
        })));
      const { unmount } = renderMovieList(['/movies?genre=Western']);

      expect(await screen.findByRole('alert')).toHaveTextContent(FILTERS_CLEARED);
      await screen.findByRole('heading', { level: 2, name: 'Movie 1' });

      fireEvent.click(screen.getByRole('button', { name: 'Next' }));
      await screen.findByRole('heading', { level: 2, name: 'Movie 2' });
      expect(screen.queryByText(FILTERS_CLEARED)).not.toBeInTheDocument();

      fireEvent.click(screen.getByRole('button', { name: 'History back' }));
      await waitFor(() => expect(currentLocation()).toBe('/movies'));
      await screen.findByRole('heading', { level: 2, name: 'Movie 1' });
      expect(screen.queryByText(FILTERS_CLEARED)).not.toBeInTheDocument();

      fireEvent.click(screen.getByRole('button', { name: 'History forward' }));
      await waitFor(() => expect(currentLocation()).toBe('/movies?page=2'));
      fireEvent.click(screen.getByRole('button', { name: 'History back' }));
      await waitFor(() => expect(currentLocation()).toBe('/movies'));
      expect(screen.queryByText(FILTERS_CLEARED)).not.toBeInTheDocument();

      unmount();
      renderMovieList(['/movies']);
      await screen.findByRole('heading', { level: 2, name: 'Movie 1' });
      expect(screen.queryByText(FILTERS_CLEARED)).not.toBeInTheDocument();
    });
  });

  describe('sign-in changes', () => {
    it('stays silent when the request was superseded and the session has not changed', async () => {
      const request = vi.spyOn(ApiClient.prototype, 'request')
        .mockRejectedValue(new AuthenticationSupersededError());
      const { queryClient } = renderMovieList();

      await waitFor(() => expect(queryClient.getQueryCache()
        .findAll({ queryKey: movieKeys.all })[0]?.state.status).toBe('error'));
      await settle();
      expect(request).toHaveBeenCalledTimes(1);
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
      expect(screen.getByText('Loading movies…')).toBeInTheDocument();
    });

    it("loads the movies once the same user's new session is in place", async () => {
      const held = holdResponses(() => fetchMock.mock.calls.length === 1);
      serveMovies((query) => held.respond(query, moviePage([movieSummary(1)])));
      const { queryClient } = renderMovieList();
      await waitFor(() => expect(held.count()).toBe(1));

      // Another tab rotates the tokens; this tab's response arrives before its storage event.
      persistSession(regularUser, 'rotated');
      held.release();
      await waitFor(() => expect(queryClient.getQueryCache()
        .findAll({ queryKey: movieKeys.all })[0]?.state.error).toBeInstanceOf(AuthenticationSupersededError));
      expect(screen.getByText('Loading movies…')).toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();

      announceStoredSession();

      expect(await screen.findByRole('heading', { level: 2, name: 'Movie 1' })).toBeInTheDocument();
      expect(fetchMock).toHaveBeenCalledTimes(2);
      expect(authorizationOf(1)).toBe('Bearer rotated-access-token');
    });

    it('loads the page when a request sent during a token rotation fails after the new session is in place', async () => {
      const held = holdResponses((query) => query.page === '1' && fetchMock.mock.calls.length === 2);
      serveMovies((query) => held.respond(query, moviePage([movieSummary(Number(query.page) + 1)], {
        page: Number(query.page),
        totalElements: 45,
        totalPages: 3
      })));
      renderMovieList();
      await screen.findByRole('heading', { level: 2, name: 'Movie 1' });

      // Another tab rotates the tokens, and this tab asks for page 2 before its storage event.
      persistSession(regularUser, 'rotated');
      fireEvent.click(screen.getByRole('button', { name: 'Next' }));
      await waitFor(() => expect(held.count()).toBe(1));
      // Memory and storage disagree, so the request went out without a token.
      expect(authorizationOf(1)).toBeNull();
      announceStoredSession();
      held.release(new Response(null, { status: 401 }));

      expect(await screen.findByRole('heading', { level: 2, name: 'Movie 2' })).toBeInTheDocument();
      expect(fetchMock).toHaveBeenCalledTimes(3);
      expect(authorizationOf(2)).toBe('Bearer rotated-access-token');
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it("shows the other user's movies when another user signs in while the list loads", async () => {
      const held = holdResponses((_query, authorization) => authorization === 'Bearer access-token');
      fetchMock.mockImplementation(async (input, init) => {
        const query = Object.fromEntries(new URL(String(input), 'http://localhost').searchParams);
        const authorization = new Headers(init?.headers).get('Authorization');
        return authorization === 'Bearer other-access-token'
          ? moviePage([movieSummary(2, { title: 'Movie for the other user' })])
          : held.respond(query, moviePage([movieSummary(1, { title: 'Movie for the first user' })]), authorization);
      });
      renderMovieList();
      await waitFor(() => expect(held.count()).toBe(1));

      persistSession(otherUser, 'other');
      announceStoredSession();

      expect(await screen.findByRole('heading', { level: 2, name: 'Movie for the other user' })).toBeInTheDocument();
      held.release();
      await settle();
      expect(screen.queryByRole('heading', { level: 2, name: 'Movie for the first user' })).not.toBeInTheDocument();
    });
  });
});

function renderMovieList(initialEntries: string[] = ['/movies']) {
  persistSession(regularUser);
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(
    <MemoryRouter
      future={{ v7_relativeSplatPath: true, v7_startTransition: true }}
      initialEntries={initialEntries}
      initialIndex={initialEntries.length - 1}
    >
      <QueryClientProvider client={queryClient}>
        <AuthProvider redirectToLogin={vi.fn()}>
          <LocationProbe />
          <Routes>
            <Route element={<ProtectedRoute />}>
              <Route path="/movies" element={<MovieListPage />} />
            </Route>
            <Route path="*" element={<p>Somewhere else</p>} />
          </Routes>
        </AuthProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
  return { ...view, queryClient };
}

function LocationProbe() {
  const location = useLocation();
  const navigate = useNavigate();
  return (
    <>
      <span className="sr-only" data-testid="current-location">
        {`${location.pathname}${location.search}`}
      </span>
      <span className="sr-only" data-testid="location-state">{JSON.stringify(location.state)}</span>
      <button onClick={() => navigate(-1)} type="button">History back</button>
      <button onClick={() => navigate(1)} type="button">History forward</button>
    </>
  );
}

function currentLocation(): string {
  return screen.getByTestId('current-location').textContent ?? '';
}

function listHeading(): HTMLElement {
  return screen.getByRole('heading', { level: 1, name: 'Movies' });
}

/** Stores a session as another tab would; `tokenPrefix` names its tokens. */
function persistSession(user: UserProfile, tokenPrefix?: string) {
  const response: AuthResponse = {
    user,
    accessToken: tokenPrefix ? `${tokenPrefix}-access-token` : 'access-token',
    refreshToken: tokenPrefix ? `${tokenPrefix}-refresh-token` : 'refresh-token',
    expiresInSeconds: 900
  };
  writeAuthSession(createAuthSession(response), window.localStorage);
}

/** The storage event another tab's write sends to this tab. */
function announceStoredSession() {
  act(() => {
    window.dispatchEvent(new StorageEvent('storage', { key: AUTH_SESSION_STORAGE_KEY }));
  });
}

function serveMovies(handler: (query: Record<string, string>) => Response | Promise<Response>) {
  fetchMock.mockImplementation(async (input) => handler(
    Object.fromEntries(new URL(String(input), 'http://localhost').searchParams)
  ));
}

/**
 * Holds back the responses that `shouldHold` picks until `release` is called, which answers them
 * with their own response or with `override`.
 */
function holdResponses(shouldHold: (query: Record<string, string>, authorization: string | null) => boolean) {
  const pending: Array<{ response: Response; resolve: (response: Response) => void }> = [];
  return {
    respond(query: Record<string, string>, response: Response, authorization: string | null = null) {
      if (!shouldHold(query, authorization)) {
        return Promise.resolve(response);
      }
      return new Promise<Response>((resolve) => {
        pending.push({ response, resolve });
      });
    },
    count: () => pending.length,
    release(override?: Response) {
      act(() => {
        for (const { response, resolve } of pending.splice(0)) {
          resolve(override ?? response);
        }
      });
    }
  };
}

function apiQueries(): Record<string, string>[] {
  return fetchMock.mock.calls.map(([input]) => Object.fromEntries(
    new URL(String(input), 'http://localhost').searchParams
  ));
}

function lastApiQuery(): Record<string, string> | undefined {
  const queries = apiQueries();
  return queries[queries.length - 1];
}

function authorizationOf(callIndex: number): string | null {
  return new Headers(fetchMock.mock.calls[callIndex]?.[1]?.headers).get('Authorization');
}

function liveRegion(): HTMLElement | null {
  return document.querySelector('p[aria-live="polite"]');
}

// Lets pending promises and deferred work run before asserting that nothing more happened.
async function settle() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
}

function movieSummary(index: number, overrides: Partial<MovieSummary> = {}): MovieSummary {
  return {
    id: `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`,
    title: `Movie ${index}`,
    slug: `movie-${index}`,
    description: `Description of movie ${index}`,
    releaseYear: 2000 + (index % 100),
    posterUrl: null,
    genres: ['Drama'],
    averageRating: null,
    status: 'PUBLISHED',
    videoStatus: null,
    ...overrides
  };
}

function moviePage(items: MovieSummary[], page: Partial<PageMeta> = {}): Response {
  return jsonResponse({
    items,
    page: {
      page: 0,
      size: 20,
      totalElements: items.length,
      totalPages: items.length > 0 ? 1 : 0,
      ...page
    }
  });
}

function validationError(fields: string[]): Response {
  return jsonResponse({
    timestamp: '2026-10-28T00:00:00Z',
    status: 400,
    code: 'VALIDATION_ERROR',
    message: 'Invalid query parameters',
    ...(fields.length > 0
      ? { fieldErrors: fields.map((field) => ({ field, message: 'is not valid' })) }
      : {})
  }, 400);
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' }
  });
}
