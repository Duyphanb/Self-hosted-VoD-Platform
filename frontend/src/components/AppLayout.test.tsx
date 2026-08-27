import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { PropsWithChildren } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AuthResponse, UserProfile } from '../lib/api/types';
import { AuthProvider } from '../features/auth/AuthContext';
import { createAuthSession, writeAuthSession } from '../features/auth/authStorage';
import { AppLayout } from './AppLayout';

const regularUser: UserProfile = {
  id: '3fcf25c2-f096-4462-ae63-4ba4702a9078',
  email: 'viewer@example.com',
  displayName: 'Viewer',
  roles: ['ROLE_USER']
};

describe('AppLayout auth navigation', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('shows guest links without protected or admin navigation', async () => {
    renderLayout();

    expect(await screen.findByRole('link', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Register' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Account' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sign out' })).not.toBeInTheDocument();
  });

  it('hides admin navigation from a regular authenticated user', async () => {
    persistUser(regularUser);
    renderLayout();

    expect(await screen.findByRole('link', { name: 'Account' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Sign in' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeEnabled();
  });

  it('shows admin navigation only to ROLE_ADMIN', async () => {
    persistUser({ ...regularUser, roles: ['ROLE_USER', 'ROLE_ADMIN'] });
    renderLayout();

    expect(await screen.findByRole('link', { name: 'Admin' })).toHaveAttribute('href', '/admin');
  });

  it('sends the refresh token once and keeps a stable disabled control while logout is pending', async () => {
    persistUser(regularUser);
    let resolveLogout: ((response: Response) => void) | undefined;
    fetchMock.mockReturnValue(new Promise<Response>((resolve) => {
      resolveLogout = resolve;
    }));
    renderLayout();

    const signOut = await screen.findByRole('button', { name: 'Sign out' });
    fireEvent.click(signOut);
    fireEvent.click(signOut);

    const pendingControl = await screen.findByRole('button', { name: 'Signing out…' });
    expect(pendingControl).toBe(signOut);
    expect(pendingControl).toBeDisabled();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(window.localStorage.getItem('vod.auth.session.v1')).toBeNull();
    expect(screen.queryByRole('link', { name: 'Sign in' })).not.toBeInTheDocument();
    expectLogoutRequest(fetchMock.mock.calls[0]);

    resolveLogout?.(new Response(null, { status: 204 }));
    await waitFor(() => expect(screen.getByRole('link', { name: 'Sign in' })).toBeInTheDocument());
  });

  it('stays signed out and reports an accessible error when revocation fails', async () => {
    persistUser(regularUser);
    fetchMock.mockRejectedValue(new TypeError('network unavailable'));
    renderLayout();

    fireEvent.click(await screen.findByRole('button', { name: 'Sign out' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Signed out locally, but server token revocation could not be confirmed.'
    );
    expect(screen.getByRole('link', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sign out' })).not.toBeInTheDocument();
    expect(window.localStorage.getItem('vod.auth.session.v1')).toBeNull();
  });
});

function renderLayout() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter future={{ v7_relativeSplatPath: true, v7_startTransition: true }}>
      <QueryClientProvider client={queryClient}>
        <AuthProvider redirectToLogin={vi.fn()}>
          <Routes>
            <Route element={<AppLayout />}>
              <Route index element={<div>Home</div>} />
            </Route>
          </Routes>
        </AuthProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
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

function expectLogoutRequest(call: [input: RequestInfo | URL, init?: RequestInit]) {
  const [input, init] = call;
  expect(input).toBe('/api/v1/auth/logout');
  expect(init?.method).toBe('POST');
  expect(new Headers(init?.headers).get('Content-Type')).toBe('application/json');
  expect(JSON.parse(init?.body?.toString() ?? '{}')).toEqual({ refreshToken: 'refresh-token' });
}
