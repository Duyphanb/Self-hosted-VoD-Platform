import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiClient, AuthenticationSupersededError } from '../../../lib/api/client';
import type { AuthResponse, PageMeta, UserProfile } from '../../../lib/api/types';
import { AuthProvider } from '../../auth/AuthContext';
import { createAuthSession, writeAuthSession } from '../../auth/authStorage';
import { ProtectedRoute } from '../../auth/RouteGuards';
import { movieKeys, type MovieSummary } from '../moviesApi';
import { MovieListPage } from './MovieListPage';

const regularUser: UserProfile = {
  id: '3fcf25c2-f096-4462-ae63-4ba4702a9078',
  email: 'viewer@example.com',
  displayName: 'Viewer',
  roles: ['ROLE_USER']
};

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

  it('requests the first page with the bearer token and renders the cards as text', async () => {
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
    expect(within(cards[0]).getByText('Drama · Thriller')).toBeInTheDocument();
    expect(within(cards[0]).queryByText(/Mystery/)).not.toBeInTheDocument();
    expect(within(cards[1]).getByRole('heading', { level: 2, name: 'Untitled Draft Cut' })).toBeInTheDocument();
    expect(cards[1]).not.toHaveTextContent(/\d{4}/);
    // The detail route arrives with #90; until then a card must not link to a missing page.
    expect(within(list).queryAllByRole('link')).toHaveLength(0);

    expect(apiQueries()).toEqual([{ page: '0', size: '20', sort: 'latest' }]);
    const [input, init] = fetchMock.mock.calls[0];
    expect(new URL(String(input), 'http://localhost').pathname).toBe('/api/v1/movies');
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer access-token');

    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByRole('heading', { level: 1, name: 'Movies' })).toBeInTheDocument();
    expect(screen.getByText('2 movies')).toHaveAttribute('aria-live', 'polite');
    expect(screen.getByRole('search', { name: 'Movie filters' })).toBeInTheDocument();
    expect(screen.getByLabelText('Genre')).toHaveValue('');
    expect(screen.getByLabelText('Year')).toHaveValue(null);
    expect(screen.getByLabelText('Sort by')).toHaveValue('latest');
    expect(screen.queryByRole('navigation', { name: 'Pagination' })).not.toBeInTheDocument();
  });

  it('restores genre, year, sort and page from the URL', async () => {
    serveMovies(() => moviePage([movieSummary(41)], { page: 2, totalElements: 81, totalPages: 5 }));
    renderMovieList(['/movies?genre=Drama&year=2019&sort=title&page=3']);

    await screen.findByRole('heading', { level: 2, name: 'Movie 41' });
    expect(apiQueries()).toEqual([{ page: '2', size: '20', genre: 'Drama', year: '2019', sort: 'title' }]);
    expect(screen.getByLabelText('Genre')).toHaveValue('Drama');
    expect(screen.getByLabelText('Year')).toHaveValue(2019);
    expect(screen.getByLabelText('Sort by')).toHaveValue('title');
    const pagination = screen.getByRole('navigation', { name: 'Pagination' });
    expect(within(pagination).getByRole('button', { name: 'Page 3' })).toHaveAttribute('aria-current', 'page');
    expect(within(pagination).getByText('Page 3 of 5')).toBeInTheDocument();
  });

  it('sends the 0-based page to the API on Next, adds a history entry and scrolls to the heading', async () => {
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
    expect(screen.getByRole('heading', { level: 1, name: 'Movies' })).toHaveFocus();

    fireEvent.click(screen.getByRole('button', { name: 'History back' }));
    await waitFor(() => expect(currentLocation()).toBe('/movies'));
    expect(await screen.findByRole('heading', { level: 2, name: 'Movie 1' })).toBeInTheDocument();
  });

  it('keeps the previous page visible while the next page loads', async () => {
    let releaseSecondPage: (() => void) | undefined;
    serveMovies((query) => {
      const response = moviePage([movieSummary(Number(query.page) + 1)], {
        page: Number(query.page),
        totalElements: 45,
        totalPages: 3
      });
      if (query.page === '0') {
        return response;
      }
      return new Promise<Response>((resolve) => {
        releaseSecondPage = () => resolve(response);
      });
    });
    renderMovieList();

    await screen.findByRole('heading', { level: 2, name: 'Movie 1' });
    fireEvent.click(screen.getByRole('button', { name: 'Page 2' }));

    await waitFor(() => expect(releaseSecondPage).toBeDefined());
    const previousCard = screen.getByRole('heading', { level: 2, name: 'Movie 1' });
    expect(previousCard.closest('ul')?.parentElement).toHaveAttribute('aria-busy', 'true');
    expect(screen.queryByText('Loading movies…')).not.toBeInTheDocument();
    expect(liveRegion()).toBeEmptyDOMElement();

    releaseSecondPage?.();
    const nextCard = await screen.findByRole('heading', { level: 2, name: 'Movie 2' });
    expect(nextCard.closest('ul')?.parentElement).toHaveAttribute('aria-busy', 'false');
    expect(liveRegion()).toHaveTextContent('45 movies');
  });

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

  it('applies a typed genre once typing pauses', async () => {
    serveMovies(() => moviePage([movieSummary(1)]));
    renderMovieList(['/movies?page=2']);

    await screen.findByRole('heading', { level: 2, name: 'Movie 1' });
    fireEvent.change(screen.getByLabelText('Genre'), { target: { value: 'Noir' } });

    expect(currentLocation()).toBe('/movies?page=2');
    await waitFor(() => expect(currentLocation()).toBe('/movies?genre=Noir'));
    await waitFor(() => expect(lastApiQuery()).toEqual({
      page: '0',
      size: '20',
      genre: 'Noir',
      sort: 'latest'
    }));
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
    expect(screen.getByRole('option', { name: 'Title A–Z' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Recently added' })).toBeInTheDocument();
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

  it('clears genre and year from the toolbar but keeps the sort', async () => {
    serveMovies(() => moviePage([movieSummary(1)], { totalElements: 45, totalPages: 3 }));
    renderMovieList(['/movies?genre=Drama&year=2019&sort=title&page=2']);

    await screen.findByRole('heading', { level: 2, name: 'Movie 1' });
    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }));

    await waitFor(() => expect(currentLocation()).toBe('/movies?sort=title'));
    expect(screen.getByLabelText('Genre')).toHaveValue('');
    expect(screen.getByLabelText('Year')).toHaveValue(null);
    expect(screen.getByRole('button', { name: 'Clear filters' })).toBeDisabled();
  });

  it('updates the filter fields when history changes the URL', async () => {
    serveMovies(() => moviePage([movieSummary(1)]));
    renderMovieList(['/movies?genre=Drama&year=2001', '/movies?genre=Comedy']);

    await screen.findByRole('heading', { level: 2, name: 'Movie 1' });
    expect(screen.getByLabelText('Genre')).toHaveValue('Comedy');
    expect(screen.getByLabelText('Year')).toHaveValue(null);

    fireEvent.click(screen.getByRole('button', { name: 'History back' }));

    await waitFor(() => expect(screen.getByLabelText('Genre')).toHaveValue('Drama'));
    expect(screen.getByLabelText('Year')).toHaveValue(2001);
    await waitFor(() => expect(lastApiQuery()).toEqual({
      page: '0',
      size: '20',
      genre: 'Drama',
      year: '2001',
      sort: 'latest'
    }));
  });

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
    expect(screen.getByText('0 movies')).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Pagination' })).not.toBeInTheDocument();
  });

  it('offers Clear filters when no movie matches the filters', async () => {
    serveMovies((query) => moviePage(query.genre ? [] : [movieSummary(1)]));
    renderMovieList(['/movies?genre=Western&page=2']);

    const heading = await screen.findByRole('heading', { level: 2, name: 'No movies match these filters' });
    fireEvent.click(within(heading.parentElement as HTMLElement).getByRole('button', { name: 'Clear filters' }));

    await waitFor(() => expect(currentLocation()).toBe('/movies'));
    expect(await screen.findByRole('heading', { level: 2, name: 'Movie 1' })).toBeInTheDocument();
  });

  it('offers the last page when the URL page is beyond the result', async () => {
    serveMovies((query) => (query.page === '2'
      ? moviePage([movieSummary(41)], { page: 2, totalElements: 41, totalPages: 3 })
      : moviePage([], { page: Number(query.page), totalElements: 41, totalPages: 3 })));
    renderMovieList(['/movies?page=9']);

    expect(await screen.findByRole('heading', { level: 2, name: "This page doesn't exist" })).toBeInTheDocument();
    expect(apiQueries()[0]).toEqual({ page: '8', size: '20', sort: 'latest' });
    fireEvent.click(screen.getByRole('button', { name: 'Go to last page' }));

    await waitFor(() => expect(currentLocation()).toBe('/movies?page=3'));
    expect(await screen.findByRole('heading', { level: 2, name: 'Movie 41' })).toBeInTheDocument();
    expect(lastApiQuery()).toEqual({ page: '2', size: '20', sort: 'latest' });
  });

  it('shows the error message and request id, and retries on request', async () => {
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

    fireEvent.click(within(alert).getByRole('button', { name: 'Retry' }));

    expect(await screen.findByRole('heading', { level: 2, name: 'Movie 1' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('clears the filters when the API rejects them with 400', async () => {
    serveMovies((query) => (query.genre
      ? jsonResponse({
        timestamp: '2026-10-28T00:00:00Z',
        status: 400,
        code: 'VALIDATION_ERROR',
        message: 'genre must contain a letter or digit'
      }, 400)
      : moviePage([movieSummary(1)])));
    renderMovieList(['/movies?genre=!!!&year=2000&sort=title&page=2']);

    expect(await screen.findByRole('heading', { level: 2, name: 'Movie 1' })).toBeInTheDocument();
    expect(currentLocation()).toBe('/movies?sort=title');
    expect(screen.getByRole('alert')).toHaveTextContent(
      'The server did not accept those filters, so they were cleared.'
    );
    expect(apiQueries()).toEqual([
      { page: '1', size: '20', genre: '!!!', year: '2000', sort: 'title' },
      { page: '0', size: '20', sort: 'title' }
    ]);
    expect(screen.getByLabelText('Genre')).toHaveValue('');
    expect(screen.getByLabelText('Year')).toHaveValue(null);
  });

  it('stays silent when the request was superseded by a sign-in change', async () => {
    const request = vi.spyOn(ApiClient.prototype, 'request')
      .mockRejectedValue(new AuthenticationSupersededError());
    const { queryClient } = renderMovieList();

    await waitFor(() => expect(request).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(queryClient.getQueryCache()
      .findAll({ queryKey: movieKeys.all })[0]?.state.status).toBe('error'));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByText('Loading movies…')).toBeInTheDocument();
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
    expect(container.querySelector('img, script, b, a')).toBeNull();
    expect((window as Window & { __xss?: number }).__xss).toBeUndefined();
  });
});

function renderMovieList(initialEntries: string[] = ['/movies']) {
  persistUser(regularUser);
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
      <button onClick={() => navigate(-1)} type="button">History back</button>
    </>
  );
}

function currentLocation(): string {
  return screen.getByTestId('current-location').textContent ?? '';
}

function persistUser(user: UserProfile) {
  const response: AuthResponse = {
    user,
    accessToken: 'access-token',
    refreshToken: 'refresh-token',
    expiresInSeconds: 900
  };
  writeAuthSession(createAuthSession(response), window.localStorage);
}

function serveMovies(handler: (query: Record<string, string>) => Response | Promise<Response>) {
  fetchMock.mockImplementation(async (input) => handler(
    Object.fromEntries(new URL(String(input), 'http://localhost').searchParams)
  ));
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

function liveRegion(): HTMLElement | null {
  return document.querySelector('p[aria-live="polite"]');
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

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' }
  });
}
