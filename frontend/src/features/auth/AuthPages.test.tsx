import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import {
  MemoryRouter,
  Route,
  Routes,
  useLocation
} from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LoginPage } from './pages/LoginPage';
import { RegisterPage } from './pages/RegisterPage';
import { AuthProvider } from './AuthContext';
import { createAuthSession, writeAuthSession } from './authStorage';
import type { AuthResponse, UserProfile } from '../../lib/api/types';

const user: UserProfile = {
  id: '3fcf25c2-f096-4462-ae63-4ba4702a9078',
  email: 'viewer@example.com',
  displayName: 'Viewer',
  roles: ['ROLE_USER']
};

const authResponse: AuthResponse = {
  user,
  accessToken: 'access-token',
  refreshToken: 'refresh-token',
  expiresInSeconds: 900
};

describe('LoginPage', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('validates fields before sending a login request', async () => {
    renderAuthRoutes('/login');

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'invalid' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('Enter a valid email address.')).toHaveAttribute('role', 'alert');
    expect(screen.getByText('Password is required.')).toHaveAttribute('role', 'alert');
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-invalid', 'true');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('shows loading state and redirects to a safe intended route after login', async () => {
    let resolveLogin: ((response: Response) => void) | undefined;
    fetchMock.mockReturnValue(new Promise<Response>((resolve) => {
      resolveLogin = resolve;
    }));
    renderAuthRoutes({ pathname: '/login', state: { from: '/library?tab=recent#latest' } });
    fillLoginForm();

    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Signing in');
    expect(screen.getByRole('button', { name: 'Signing in…' })).toBeDisabled();
    resolveLogin?.(jsonResponse(authResponse));

    expect(await screen.findByText('/library?tab=recent#latest')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/auth/login', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ email: 'viewer@example.com', password: 'strong-password' })
    }));
  });

  it('falls back to home for an unsafe redirect and shows generic server errors', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(apiError(401, 'INVALID_CREDENTIALS', 'Invalid email or password'), 401))
      .mockResolvedValueOnce(jsonResponse(authResponse));
    const view = renderAuthRoutes({ pathname: '/login', state: { from: '//evil.example' } });
    fillLoginForm();
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByText('Invalid email or password')).toHaveAttribute('role', 'alert');

    view.unmount();
    renderAuthRoutes({ pathname: '/login', state: { from: '//evil.example' } });
    fillLoginForm();
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('/')).toBeInTheDocument();
  });

  it('uses accessible labels and login autocomplete values', () => {
    renderAuthRoutes('/login');

    expect(screen.getByLabelText('Email')).toHaveAttribute('autocomplete', 'email');
    expect(screen.getByLabelText('Password')).toHaveAttribute('autocomplete', 'current-password');
    expect(screen.getByRole('link', { name: 'Create an account' })).toHaveAttribute('href', '/register');
  });

  it('does not label the login form as submitting during a bootstrap refresh', async () => {
    writeAuthSession({
      ...createAuthSession(authResponse),
      accessTokenExpiresAt: Date.now() + 1_000
    }, window.localStorage);
    fetchMock.mockReturnValue(new Promise<Response>(() => undefined));
    renderAuthRoutes('/login');

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled();
    expect(screen.getByRole('status')).toHaveTextContent('');
  });
});

describe('RegisterPage', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('validates registration boundaries before sending a request', async () => {
    renderAuthRoutes('/register');
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'viewer@example.com' } });
    fireEvent.change(screen.getByLabelText('Display name'), { target: { value: 'V' } });
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: '1234567' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByText('Display name must be at least 2 characters.')).toBeInTheDocument();
    expect(screen.getByText('Password must be at least 8 characters.')).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('submits displayName and reports success without creating an auth session', async () => {
    fetchMock.mockResolvedValue(jsonResponse(user, 201));
    renderAuthRoutes('/register');
    fillRegistrationForm();
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }));

    await waitFor(() => expect(screen.getByRole('status'))
      .toHaveTextContent('Account created for viewer@example.com.'));
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/login');
    expect(window.localStorage.length).toBe(0);
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/auth/register', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({
        email: 'viewer@example.com',
        password: 'strong-password',
        displayName: 'Viewer'
      })
    }));

    fireEvent.click(screen.getByRole('button', { name: 'Create another account' }));
    expect(screen.getByLabelText('Email')).toHaveValue('');
    expect(screen.getByLabelText('Display name')).toHaveValue('');
    expect(screen.getByLabelText('Password')).toHaveValue('');
  });

  it('maps backend validation errors and duplicate-email errors', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({
      ...apiError(400, 'VALIDATION_ERROR', 'Request validation failed'),
      fieldErrors: [{ field: 'displayName', message: 'must be unique for this test' }]
    }, 400));
    renderAuthRoutes('/register');
    fillRegistrationForm();
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByText('must be unique for this test')).toHaveAttribute('role', 'alert');

    fetchMock.mockResolvedValueOnce(jsonResponse(
      apiError(409, 'EMAIL_ALREADY_EXISTS', 'An account with this email already exists'),
      409
    ));
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByText('An account with this email already exists')).toHaveAttribute('role', 'alert');
  });

  it('shows pending state and registration autocomplete values', async () => {
    fetchMock.mockReturnValue(new Promise<Response>(() => undefined));
    renderAuthRoutes('/register');

    expect(screen.getByLabelText('Email')).toHaveAttribute('autocomplete', 'email');
    expect(screen.getByLabelText('Display name')).toHaveAttribute('autocomplete', 'name');
    expect(screen.getByLabelText('Password')).toHaveAttribute('autocomplete', 'new-password');
    fillRegistrationForm();
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Creating account');
    expect(screen.getByRole('button', { name: 'Creating account…' })).toBeDisabled();
  });
});

function renderAuthRoutes(initialEntry: string | { pathname: string; state?: unknown }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <QueryClientProvider client={queryClient}>
        <AuthProvider redirectToLogin={vi.fn()}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/register" element={<RegisterPage />} />
            <Route path="*" element={<LocationProbe />} />
          </Routes>
        </AuthProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
}

function LocationProbe() {
  const location = useLocation();
  return <div>{`${location.pathname}${location.search}${location.hash}`}</div>;
}

function fillLoginForm() {
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'viewer@example.com' } });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'strong-password' } });
}

function fillRegistrationForm() {
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'viewer@example.com' } });
  fireEvent.change(screen.getByLabelText('Display name'), { target: { value: 'Viewer' } });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'strong-password' } });
}

function apiError(status: number, code: string, message: string) {
  return { timestamp: '2026-08-21T00:00:00Z', status, code, message };
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' }
  });
}
