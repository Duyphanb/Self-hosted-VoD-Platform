import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import type { PropsWithChildren, ReactNode } from 'react';
import {
  MemoryRouter,
  Route,
  Routes,
  useLocation
} from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AuthResponse, UserProfile } from '../../lib/api/types';
import { AuthProvider } from './AuthContext';
import { AdminRoute, ProtectedRoute } from './RouteGuards';
import { createAuthSession, writeAuthSession } from './authStorage';

const regularUser: UserProfile = {
  id: '3fcf25c2-f096-4462-ae63-4ba4702a9078',
  email: 'viewer@example.com',
  displayName: 'Viewer',
  roles: ['ROLE_USER']
};

const authResponse: AuthResponse = {
  user: regularUser,
  accessToken: 'access-token',
  refreshToken: 'refresh-token',
  expiresInSeconds: 900
};

describe('route guards', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('redirects an unauthenticated protected route and preserves the intended path', async () => {
    renderGuardRoutes('/account?tab=security#sessions', <ProtectedRoute />);

    expect(await screen.findByTestId('login-destination')).toHaveTextContent(
      '/account?tab=security#sessions'
    );
    expect(screen.queryByText('Protected content')).not.toBeInTheDocument();
  });

  it('renders protected content for an authenticated user', async () => {
    persistSession(authResponse);
    renderGuardRoutes('/account', <ProtectedRoute />);

    expect(await screen.findByText('Protected content')).toBeInTheDocument();
  });

  it('does not render or redirect until auth initialization finishes', async () => {
    persistSession(authResponse, Date.now() + 1_000);
    fetchMock.mockReturnValue(new Promise<Response>(() => undefined));
    renderGuardRoutes('/account', <ProtectedRoute />);

    expect(await screen.findByRole('status')).toHaveTextContent('Checking authentication…');
    expect(screen.queryByText('Protected content')).not.toBeInTheDocument();
    expect(screen.queryByTestId('login-destination')).not.toBeInTheDocument();
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
  });

  it('redirects a regular user away from an admin route', async () => {
    persistSession(authResponse);
    renderGuardRoutes('/admin', <AdminRoute />);

    expect(await screen.findByText('Forbidden destination')).toBeInTheDocument();
    expect(screen.queryByText('Admin content')).not.toBeInTheDocument();
  });

  it('renders an admin route only for ROLE_ADMIN', async () => {
    persistSession({
      ...authResponse,
      user: { ...regularUser, roles: ['ROLE_USER', 'ROLE_ADMIN'] }
    });
    renderGuardRoutes('/admin', <AdminRoute />);

    expect(await screen.findByText('Admin content')).toBeInTheDocument();
  });

  it('redirects an unauthenticated admin route to login', async () => {
    renderGuardRoutes('/admin', <AdminRoute />);

    expect(await screen.findByTestId('login-destination')).toHaveTextContent('/admin');
    expect(screen.queryByText('Admin content')).not.toBeInTheDocument();
  });
});

function renderGuardRoutes(initialEntry: string, guard: ReactNode) {
  return render(
    <MemoryRouter
      future={{ v7_relativeSplatPath: true, v7_startTransition: true }}
      initialEntries={[initialEntry]}
    >
      <TestProviders>
        <Routes>
          <Route element={guard}>
            <Route path="/account" element={<div>Protected content</div>} />
            <Route path="/admin" element={<div>Admin content</div>} />
          </Route>
          <Route path="/login" element={<LoginDestination />} />
          <Route path="/forbidden" element={<div>Forbidden destination</div>} />
        </Routes>
      </TestProviders>
    </MemoryRouter>
  );
}

function LoginDestination() {
  const location = useLocation();
  const from = (location.state as { from?: unknown } | null)?.from;
  return (
    <div data-testid="login-destination">
      Login destination {typeof from === 'string' ? from : ''}
    </div>
  );
}

function TestProviders({ children }: PropsWithChildren) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider redirectToLogin={vi.fn()}>{children}</AuthProvider>
    </QueryClientProvider>
  );
}

function persistSession(response: AuthResponse, accessTokenExpiresAt?: number) {
  writeAuthSession({
    ...createAuthSession(response),
    ...(accessTokenExpiresAt === undefined ? {} : { accessTokenExpiresAt })
  }, window.localStorage);
}
