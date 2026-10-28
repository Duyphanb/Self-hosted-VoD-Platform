import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, MemoryRouter, RouterProvider, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AuthResponse, UserProfile } from '../lib/api/types';
import { AuthProvider } from '../features/auth/AuthContext';
import { createAuthSession, writeAuthSession } from '../features/auth/authStorage';
import { AppRoutes } from './AppRoutes';
import { APP_ROUTER_FUTURE, APP_ROUTER_PROVIDER_FUTURE, createAppRoutes } from './AppRouter';

const regularUser: UserProfile = {
  id: '3fcf25c2-f096-4462-ae63-4ba4702a9078',
  email: 'viewer@example.com',
  displayName: 'Viewer',
  roles: ['ROLE_USER']
};

describe('AppRoutes guard wiring', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('sends a guest from /account to the login page', async () => {
    renderRoutes('/account');

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('sends a regular user from /admin to the forbidden page', async () => {
    persistUser(regularUser);
    renderRoutes('/admin');

    expect(await screen.findByRole('heading', { name: 'Access forbidden' })).toBeInTheDocument();
  });

  const adminMovieRoutes = ['/admin/movies', '/admin/movies/new', '/admin/movies/6f1c0f55-2d4a-4b64-9a43-1d0f3c7b8e21/edit'];
  it.each(adminMovieRoutes)('rejects a regular user on the production route %s before any request', async (path) => {
    persistUser(regularUser);
    renderProductionRoutes(path);
    expect(await screen.findByRole('heading', { name: 'Access forbidden' })).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it.each(adminMovieRoutes)('sends a guest from the production route %s to sign in before any request', async (path) => {
    renderProductionRoutes(path);
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.getByTestId('current-location')).toHaveTextContent('/login');
  });

  it('renders /admin for ROLE_ADMIN', async () => {
    persistUser({ ...regularUser, roles: ['ROLE_USER', 'ROLE_ADMIN'] });
    fetchMock.mockResolvedValue(jsonResponse({
      items: [],
      page: { page: 0, size: 20, totalElements: 0, totalPages: 0 }
    }));
    renderRoutes('/admin');

    expect(await screen.findByRole('heading', { level: 1, name: 'Movies' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'New movie' })).toHaveAttribute('href', '/admin/movies/new');
  });

  it('sends a signed-in user from the landing page to the movie list', async () => {
    persistUser(regularUser);
    fetchMock.mockResolvedValue(jsonResponse({
      items: [],
      page: { page: 0, size: 20, totalElements: 0, totalPages: 0 }
    }));
    renderRoutes('/');

    expect(await screen.findByRole('heading', { level: 1, name: 'Movies' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Your self-hosted video library' }))
      .not.toBeInTheDocument();
  });

  it('keeps an anonymous visitor on the landing page with sign-in and register links', async () => {
    renderRoutes('/');

    expect(await screen.findByRole('heading', { name: 'Your self-hosted video library' }))
      .toBeInTheDocument();
    const main = screen.getByRole('main');
    expect(main).toHaveTextContent('Sign in to browse the movie library.');
    expect(main).not.toHaveTextContent(/later sprint/i);
    expect(within(main).getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/login');
    expect(within(main).getByRole('link', { name: 'Register' })).toHaveAttribute('href', '/register');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('sends a guest from /movies to the login page without calling the API', async () => {
    renderRoutes('/movies?genre=Drama');

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('renders a not-found page with navigation for an unknown route', async () => {
    renderRoutes('/no-such-page');

    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Primary navigation' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Return home' })).toHaveAttribute('href', '/');
  });

  it('redirects to login after logout from a protected route', async () => {
    persistUser(regularUser);
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    renderProductionRoutes('/account');

    expect(await screen.findByRole('heading', { name: 'Account' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    await waitFor(() => expect(screen.getByTestId('current-location')).toHaveTextContent('/login'));
    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('preserves the intended route when bootstrap refresh fails before login', async () => {
    persistUser(regularUser, Date.now() + 1_000);
    fetchMock
      .mockResolvedValueOnce(jsonResponse({
        timestamp: '2026-08-21T00:00:00Z',
        status: 401,
        code: 'INVALID_REFRESH_TOKEN',
        message: 'Refresh token is invalid or expired'
      }, 401))
      .mockResolvedValueOnce(jsonResponse({
        user: regularUser,
        accessToken: 'new-access-token',
        refreshToken: 'new-refresh-token',
        expiresInSeconds: 900
      }));
    renderProductionRoutes('/account?tab=security#sessions');

    await screen.findByRole('heading', { name: 'Sign in' });
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'viewer@example.com' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'strong-password' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(screen.getByTestId('current-location'))
      .toHaveTextContent('/account?tab=security#sessions'));
    expect(screen.getByRole('heading', { name: 'Account' })).toBeInTheDocument();
  });
});

function renderRoutes(initialEntry: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter
      future={{ v7_relativeSplatPath: true, v7_startTransition: true }}
      initialEntries={[initialEntry]}
    >
      <QueryClientProvider client={queryClient}>
        <AuthProvider redirectToLogin={vi.fn()}>
          <AppRoutes />
        </AuthProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
}

function renderProductionRoutes(initialEntry: string) {
  const router = createMemoryRouter(createAppRoutes(<LocationProbe />), {
    initialEntries: [initialEntry], future: APP_ROUTER_FUTURE
  });
  return { ...render(<RouterProvider future={APP_ROUTER_PROVIDER_FUTURE} router={router} />), router };
}

function LocationProbe() {
  const location = useLocation();
  return (
    <span className="sr-only" data-testid="current-location">
      {`${location.pathname}${location.search}${location.hash}`}
    </span>
  );
}

function persistUser(user: UserProfile, accessTokenExpiresAt?: number) {
  const response: AuthResponse = {
    user,
    accessToken: 'access-token',
    refreshToken: 'refresh-token',
    expiresInSeconds: 900
  };
  writeAuthSession({
    ...createAuthSession(response),
    ...(accessTokenExpiresAt === undefined ? {} : { accessTokenExpiresAt })
  }, window.localStorage);
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' }
  });
}
