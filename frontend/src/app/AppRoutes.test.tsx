import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AuthResponse, UserProfile } from '../lib/api/types';
import { AuthProvider } from '../features/auth/AuthContext';
import { createAuthSession, writeAuthSession } from '../features/auth/authStorage';
import { AppRoutes } from './AppRoutes';
import { AppProviders } from './AppProviders';

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

  it('renders /admin for ROLE_ADMIN', async () => {
    persistUser({ ...regularUser, roles: ['ROLE_USER', 'ROLE_ADMIN'] });
    renderRoutes('/admin');

    expect(await screen.findByRole('heading', { name: 'Administration' })).toBeInTheDocument();
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
  return render(
    <MemoryRouter
      future={{ v7_relativeSplatPath: true, v7_startTransition: true }}
      initialEntries={[initialEntry]}
    >
      <AppProviders>
        <LocationProbe />
        <AppRoutes />
      </AppProviders>
    </MemoryRouter>
  );
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
