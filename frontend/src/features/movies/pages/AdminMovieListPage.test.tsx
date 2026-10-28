import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AuthResponse, PageMeta, UserProfile } from '../../../lib/api/types';
import { AuthProvider } from '../../auth/AuthContext';
import { createAuthSession, writeAuthSession } from '../../auth/authStorage';
import { AdminRoute } from '../../auth/RouteGuards';
import { movieKeys, type MovieSummary } from '../moviesApi';
import { AdminMovieListPage } from './AdminMovieListPage';

const admin: UserProfile = {
  id: '3fcf25c2-f096-4462-ae63-4ba4702a9078', email: 'admin@example.com', displayName: 'Admin', roles: ['ROLE_USER', 'ROLE_ADMIN']
};
const fetchMock = vi.fn<typeof fetch>();

describe('AdminMovieListPage', () => {
  beforeEach(() => { fetchMock.mockReset(); vi.stubGlobal('fetch', fetchMock); });
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

  it('requests all statuses with authentication and renders the admin table actions', async () => {
    fetchMock.mockImplementation(async () => moviePage([movie(1), movie(2, { status: 'ARCHIVED', videoStatus: 'READY' })]));
    renderList();

    const table = await screen.findByRole('table', { name: 'Movies available for administration' }, { timeout: 5_000 });
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Movies');
    expect(within(table).getByRole('link', { name: 'Movie 1' })).toHaveAttribute('href', `/admin/movies/${movie(1).id}/edit`);
    expect(within(table).getByRole('link', { name: 'View Movie 1' })).toHaveAttribute('href', `/movies/${movie(1).id}`);
    expect(within(table).getByRole('link', { name: 'Edit Movie 1' })).toHaveAttribute('href', `/admin/movies/${movie(1).id}/edit`);
    expect(within(table).getByRole('button', { name: 'Archive Movie 1' })).toBeInTheDocument();
    expect(within(table).queryByRole('button', { name: 'Archive Movie 2' })).not.toBeInTheDocument();
    expect(table).toHaveTextContent('Drama');
    expect(table).toHaveTextContent('Draft');
    expect(table).toHaveTextContent('Archived');
    expect(table).toHaveTextContent('Ready');
    expect(screen.getByRole('link', { name: 'New movie' })).toHaveAttribute('href', '/admin/movies/new');
    expect(apiQueries()).toEqual([{ page: '0', size: '20', sort: 'latest' }]);
    expect(new Headers(fetchMock.mock.calls[0][1]?.headers).get('Authorization')).toBe('Bearer access-token');
    expect(new URL(String(fetchMock.mock.calls[0][0]), 'http://localhost').pathname).toBe('/api/v1/admin/movies');
  });

  it('restores status/sort/page and resets the page when status changes', async () => {
    fetchMock.mockImplementation(async () => moviePage([movie(1)], { page: 2, totalPages: 4, totalElements: 65 }));
    renderList({ path: '/admin/movies?status=ARCHIVED&sort=title&page=3' });
    await screen.findByRole('table');
    expect(apiQueries()[0]).toEqual({ status: 'ARCHIVED', page: '2', size: '20', sort: 'title' });
    const filters = screen.getByRole('group', { name: 'Movie status' });
    expect(within(filters).getByRole('button', { name: 'Archived' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByLabelText('Sort by')).toHaveValue('title');
    fireEvent.click(within(filters).getByRole('button', { name: 'Draft' }));
    await waitFor(() => expect(currentLocation()).toBe('/admin/movies?status=DRAFT&sort=title'));
    await waitFor(() => expect(apiQueries().slice(-1)[0]).toEqual({ status: 'DRAFT', page: '0', size: '20', sort: 'title' }));
  });

  it('paginates with API zero-based pages, keeps filters, and focuses the heading', async () => {
    fetchMock.mockImplementation(async (input) => {
      const page = Number(new URL(String(input), 'http://localhost').searchParams.get('page'));
      return moviePage([movie(page + 1)], { page, totalPages: 3, totalElements: 41 });
    });
    renderList({ path: '/admin/movies?status=PUBLISHED' });
    await screen.findByRole('table');
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(apiQueries().slice(-1)[0]?.page).toBe('1'));
    expect(currentLocation()).toBe('/admin/movies?status=PUBLISHED&page=2');
    expect(screen.getByRole('heading', { level: 1, name: 'Movies' })).toHaveFocus();
    expect(await screen.findByRole('link', { name: 'Movie 2' })).toBeInTheDocument();
  });

  it('offers the last valid page instead of silently showing a different page', async () => {
    fetchMock.mockImplementation(async (input) => {
      const page = Number(new URL(String(input), 'http://localhost').searchParams.get('page'));
      return moviePage(page === 8 ? [] : [movie(3)], { page, totalPages: 3, totalElements: 41 });
    });
    renderList({ path: '/admin/movies?sort=title&page=9' });
    await screen.findByRole('heading', { name: "This page doesn't exist" });
    fireEvent.click(screen.getByRole('button', { name: 'Go to last page' }));
    expect(await screen.findByRole('link', { name: 'Movie 3' })).toBeInTheDocument();
    expect(currentLocation()).toBe('/admin/movies?page=3&sort=title');
  });

  it('handles empty catalogs and clears an empty status filter', async () => {
    fetchMock.mockImplementation(async () => moviePage([]));
    renderList({ path: '/admin/movies?status=DRAFT&sort=title' });
    await screen.findByRole('heading', { name: 'No movies match this status' });
    fireEvent.click(screen.getByRole('button', { name: 'Show all movies' }));
    expect(await screen.findByRole('heading', { name: 'No movies yet' })).toBeInTheDocument();
    expect(currentLocation()).toBe('/admin/movies?sort=title');
    expect(screen.getByRole('link', { name: 'New movie' })).toBeInTheDocument();
  });

  it('clears only a filter named by HTTP 400', async () => {
    fetchMock.mockImplementation(async (input) => new URL(String(input), 'http://localhost').searchParams.has('status')
      ? apiError(400, 'VALIDATION_ERROR', [{ field: 'status', message: 'Invalid status' }])
      : moviePage([movie(1)]));
    renderList({ path: '/admin/movies?status=DRAFT&sort=title&page=3' });
    await screen.findByRole('table');
    expect(currentLocation()).toBe('/admin/movies?sort=title');
    expect(apiQueries()).toEqual([
      { status: 'DRAFT', sort: 'title', page: '2', size: '20' }, { sort: 'title', page: '0', size: '20' }
    ]);
  });

  it('shows a forbidden state when the server denies the admin list', async () => {
    fetchMock.mockImplementation(async () => apiError(403, 'FORBIDDEN'));
    renderList();
    expect(await screen.findByRole('heading', { name: 'Access forbidden' })).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('retries other failures and retains keyboard focus on the page heading', async () => {
    fetchMock.mockResolvedValueOnce(apiError(500, 'INTERNAL_ERROR')).mockResolvedValueOnce(moviePage([movie(1)]));
    renderList();
    await screen.findByRole('heading', { name: 'Movies could not be loaded' });
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('table')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Movies' })).toHaveFocus();
  });

  it('does not display stale rows when the status filter changes', async () => {
    const held = deferredResponse();
    fetchMock.mockResolvedValueOnce(moviePage([movie(1)])).mockReturnValueOnce(held.promise);
    renderList();
    await screen.findByRole('link', { name: 'Movie 1' });
    fireEvent.click(screen.getByRole('button', { name: 'Published' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole('link', { name: 'Movie 1' })).not.toBeInTheDocument();
    expect(screen.getByText('Loading admin movies…')).toBeInTheDocument();
    held.release(moviePage([movie(2)]));
    expect(await screen.findByRole('link', { name: 'Movie 2' })).toBeInTheDocument();
  });

  it('consumes form success feedback so later navigation does not replay it', async () => {
    fetchMock.mockImplementation(async () => moviePage([movie(1)], { totalPages: 2, totalElements: 21 }));
    renderList({ state: { message: 'Movie created' } });
    await screen.findByRole('table');
    expect(screen.getByText('Movie created')).toHaveAttribute('role', 'status');
    await waitFor(() => expect(screen.getByTestId('location-state')).toHaveTextContent('null'));
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(currentLocation()).toContain('page=2'));
    expect(screen.queryByText('Movie created')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'History back' }));
    await waitFor(() => expect(currentLocation()).toBe('/admin/movies'));
    expect(screen.queryByText('Movie created')).not.toBeInTheDocument();
  });

  it('opens archive on Cancel, traps Tab, supports Escape and restores trigger focus', async () => {
    fetchMock.mockImplementation(async () => moviePage([movie(1)]));
    renderList();
    const trigger = await screen.findByRole('button', { name: 'Archive Movie 1' });
    trigger.focus();
    fireEvent.click(trigger);
    const dialog = screen.getByRole('dialog', { name: "Archive 'Movie 1'?" });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    const cancel = within(dialog).getByRole('button', { name: 'Cancel' });
    const confirm = within(dialog).getByRole('button', { name: 'Archive' });
    expect(cancel).toHaveFocus();
    fireEvent.keyDown(cancel, { key: 'Tab', shiftKey: true });
    expect(confirm).toHaveFocus();
    fireEvent.keyDown(confirm, { key: 'Tab' });
    expect(cancel).toHaveFocus();
    fireEvent.keyDown(cancel, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('archives once during a double submit, refreshes all movie queries and focuses the heading', async () => {
    const held = deferredResponse();
    let archived = false;
    fetchMock.mockImplementation(async (_input, init) => {
      if (init?.method === 'DELETE') { archived = true; return held.promise; }
      return moviePage([movie(1, { status: archived ? 'ARCHIVED' : 'DRAFT' })]);
    });
    const { queryClient } = renderList();
    queryClient.setQueryData(movieKeys.detail(movie(1).id), { title: 'Cached detail' });
    fireEvent.click(await screen.findByRole('button', { name: 'Archive Movie 1' }));
    const confirm = within(screen.getByRole('dialog')).getByRole('button', { name: 'Archive' });
    fireEvent.click(confirm);
    fireEvent.click(confirm);
    expect(confirm).toBeDisabled();
    expect(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' })).toBeDisabled();
    expect(screen.getByRole('dialog')).toHaveFocus();
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Tab', shiftKey: true });
    expect(screen.getByRole('dialog')).toHaveFocus();
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'DELETE')).toHaveLength(1);
    held.release(new Response(null, { status: 204 }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.getByRole('status')).toHaveTextContent('Archived');
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Archive Movie 1' })).not.toBeInTheDocument());
    expect(queryClient.getQueryState(movieKeys.detail(movie(1).id))?.isInvalidated).toBe(true);
    expect(screen.getByRole('heading', { level: 1, name: 'Movies' })).toHaveFocus();
  });

  it('keeps archive errors in the dialog and lets the user retry', async () => {
    let deletes = 0;
    fetchMock.mockImplementation(async (_input, init) => init?.method === 'DELETE'
      ? ++deletes === 1 ? apiError(500, 'INTERNAL_ERROR') : new Response(null, { status: 204 })
      : moviePage([movie(1)]));
    renderList();
    fireEvent.click(await screen.findByRole('button', { name: 'Archive Movie 1' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Archive' }));
    expect(await within(screen.getByRole('dialog')).findByRole('alert')).toHaveTextContent('Movie could not be archived');
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Tab', shiftKey: true });
    expect(within(screen.getByRole('dialog')).getByRole('button', { name: 'Archive' })).toHaveFocus();
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Archive' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(deletes).toBe(2);
  });

  it('refreshes a missing movie after archive 404 and announces that it no longer exists', async () => {
    let missing = false;
    fetchMock.mockImplementation(async (_input, init) => {
      if (init?.method === 'DELETE') { missing = true; return apiError(404, 'MOVIE_NOT_FOUND'); }
      return moviePage(missing ? [] : [movie(1)]);
    });
    renderList();
    fireEvent.click(await screen.findByRole('button', { name: 'Archive Movie 1' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Archive' }));
    expect(await screen.findByText('Movie no longer exists')).toHaveAttribute('role', 'status');
    expect(await screen.findByRole('heading', { name: 'No movies yet' })).toBeInTheDocument();
  });

  it('shows Forbidden when archive authorization is denied by the API', async () => {
    fetchMock.mockImplementation(async (_input, init) => init?.method === 'DELETE' ? apiError(403, 'FORBIDDEN') : moviePage([movie(1)]));
    renderList();
    fireEvent.click(await screen.findByRole('button', { name: 'Archive Movie 1' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Archive' }));
    expect(await screen.findByRole('heading', { name: 'Access forbidden' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('redirects a regular user before requesting admin data', async () => {
    renderList({ user: { ...admin, roles: ['ROLE_USER'] } });
    expect(await screen.findByText('Forbidden destination')).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

function renderList({ path = '/admin/movies', state = null, user = admin }: { path?: string; state?: unknown; user?: UserProfile } = {}) {
  const response: AuthResponse = { user, accessToken: 'access-token', refreshToken: 'refresh-token', expiresInSeconds: 900 };
  writeAuthSession(createAuthSession(response), window.localStorage);
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(
    <MemoryRouter future={{ v7_relativeSplatPath: true, v7_startTransition: true }} initialEntries={state ? [{ pathname: '/admin/movies', state }] : [path]}>
      <QueryClientProvider client={queryClient}><AuthProvider redirectToLogin={vi.fn()}><LocationProbe /><Routes>
        <Route element={<AdminRoute />}><Route path="/admin/movies" element={<AdminMovieListPage />} /></Route>
        <Route path="/forbidden" element={<p>Forbidden destination</p>} />
        <Route path="*" element={<p>Other destination</p>} />
      </Routes></AuthProvider></QueryClientProvider>
    </MemoryRouter>
  );
  return { ...view, queryClient };
}

function LocationProbe() {
  const location = useLocation();
  const navigate = useNavigate();
  return <><span data-testid="location">{location.pathname}{location.search}</span><span data-testid="location-state">{JSON.stringify(location.state)}</span><button onClick={() => navigate(-1)} type="button">History back</button></>;
}
function currentLocation() { return screen.getByTestId('location').textContent; }
function apiQueries() { return fetchMock.mock.calls.map(([input]) => Object.fromEntries(new URL(String(input), 'http://localhost').searchParams)); }
function deferredResponse() {
  let resolveResponse: (response: Response) => void = () => undefined;
  const promise = new Promise<Response>((resolve) => { resolveResponse = resolve; });
  return { promise, release: (response: Response) => act(() => resolveResponse(response)) };
}
function movie(index: number, overrides: Partial<MovieSummary> = {}): MovieSummary {
  return { id: `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`, title: `Movie ${index}`, slug: `movie-${index}`, description: `Description ${index}`, releaseYear: 2020, posterUrl: null, genres: ['Drama'], averageRating: null, status: 'DRAFT', videoStatus: null, ...overrides };
}
function moviePage(items: MovieSummary[], page: Partial<PageMeta> = {}) {
  return new Response(JSON.stringify({ items, page: { page: 0, size: 20, totalElements: items.length, totalPages: items.length ? 1 : 0, ...page } }), { status: 200, headers: { 'Content-Type': 'application/json' } });
}
function apiError(status: number, code: string, fieldErrors?: Array<{ field: string; message: string }>) {
  return new Response(JSON.stringify({ timestamp: '2026-10-28T00:00:00Z', status, code, message: 'Request could not be completed', fieldErrors }), { status, headers: { 'Content-Type': 'application/json' } });
}
