import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { StrictMode, type PropsWithChildren } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider, useApiClient, useAuth } from './AuthContext';
import {
  AUTH_SESSION_STORAGE_KEY,
  advanceAuthGeneration,
  clearAuthSession,
  createAuthSession,
  writeAuthSession
} from './authStorage';
import type { AuthResponse, UserProfile } from '../../lib/api/types';
import { AuthenticationSupersededError } from '../../lib/api/client';

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

describe('AuthProvider', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.useRealTimers();
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value: undefined
    });
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('logs in and persists the complete token session', async () => {
    fetchMock.mockResolvedValue(jsonResponse(authResponse));
    const { result } = renderHook(() => useAuth(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isInitializing).toBe(false));

    await act(async () => {
      await result.current.login({
        email: 'viewer@example.com',
        password: 'strong-password'
      });
    });

    expect(result.current.user).toEqual(user);
    expect(result.current.accessToken).toBe('access-token');
    expect(result.current.refreshToken).toBe('refresh-token');
    expect(window.localStorage.length).toBe(1);
  });

  it('exposes loading state while an auth request is pending', async () => {
    let resolveLogin: ((response: Response) => void) | undefined;
    fetchMock.mockReturnValue(new Promise<Response>((resolve) => {
      resolveLogin = resolve;
    }));
    const { result } = renderHook(() => useAuth(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isInitializing).toBe(false));

    let loginPromise: Promise<UserProfile> = Promise.resolve(user);
    act(() => {
      loginPromise = result.current.login({
        email: 'viewer@example.com',
        password: 'strong-password'
      });
    });
    await waitFor(() => expect(result.current.isLoading).toBe(true));
    await act(async () => {
      resolveLogin?.(jsonResponse(authResponse));
      await loginPromise;
    });

    expect(result.current.isLoading).toBe(false);
    expect(result.current.user).toEqual(user);
  });

  it('registers without creating an authenticated session', async () => {
    fetchMock.mockResolvedValue(jsonResponse(user, 201));
    const { result } = renderHook(() => useAuth(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isInitializing).toBe(false));

    let registered: UserProfile | undefined;
    await act(async () => {
      registered = await result.current.register({
        email: 'viewer@example.com',
        password: 'strong-password',
        displayName: 'Viewer'
      });
    });

    expect(registered).toEqual(user);
    expect(result.current.isAuthenticated).toBe(false);
    expect(window.localStorage.getItem(AUTH_SESSION_STORAGE_KEY)).toBeNull();
  });

  it('clears local state and query cache even when logout transport fails', async () => {
    writeAuthSession(createAuthSession(authResponse), window.localStorage);
    fetchMock.mockRejectedValue(new TypeError('network unavailable'));
    const queryClient = new QueryClient();
    queryClient.setQueryData(['private'], { secret: true });
    const { result } = renderHook(() => useAuth(), {
      wrapper: createWrapper({ queryClient })
    });
    await waitFor(() => expect(result.current.isInitializing).toBe(false));

    await act(async () => {
      await expect(result.current.logout()).rejects.toThrow('network unavailable');
    });

    expect(result.current.isAuthenticated).toBe(false);
    expect(window.localStorage.getItem(AUTH_SESSION_STORAGE_KEY)).toBeNull();
    expect(queryClient.getQueryData(['private'])).toBeUndefined();
  });

  it('clears local state before a pending logout request settles', async () => {
    writeAuthSession(createAuthSession(authResponse), window.localStorage);
    let resolveLogout: ((response: Response) => void) | undefined;
    fetchMock.mockReturnValue(new Promise<Response>((resolve) => {
      resolveLogout = resolve;
    }));
    const { result } = renderHook(() => useAuth(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isInitializing).toBe(false));

    let logoutPromise: Promise<void> = Promise.resolve();
    act(() => {
      logoutPromise = result.current.logout();
    });
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));

    expect(result.current.isAuthenticated).toBe(false);
    expect(window.localStorage.getItem(AUTH_SESSION_STORAGE_KEY)).toBeNull();
    await act(async () => {
      resolveLogout?.(new Response(null, { status: 204 }));
      await logoutPromise;
    });
  });

  it('bounds a stalled refresh and releases loading state', async () => {
    writeAuthSession(createAuthSession(authResponse), window.localStorage);
    fetchMock.mockImplementation((_input, init) => new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => {
        reject(new DOMException('The operation was aborted', 'AbortError'));
      });
    }));
    const { result } = renderHook(() => useAuth(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isInitializing).toBe(false));
    vi.useFakeTimers();

    await act(async () => {
      const refreshPromise = result.current.refreshTokens();
      const refreshFailure = refreshPromise.catch((failure: unknown) => failure);
      await vi.advanceTimersByTimeAsync(10_000);
      await expect(refreshFailure).resolves.toMatchObject({ name: 'AbortError' });
    });

    expect(result.current.isLoading).toBe(false);
    expect(result.current.isAuthenticated).toBe(false);
  });

  it('keeps multi-token logout revocation bounded after an early failure', async () => {
    writeAuthSession(createAuthSession(authResponse), window.localStorage);
    const { result } = renderHook(() => useAuth(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isInitializing).toBe(false));
    writeAuthSession(createAuthSession({
      ...authResponse,
      accessToken: 'external-access-token',
      refreshToken: 'external-refresh-token'
    }), window.localStorage);
    const abortedRevocation = vi.fn();
    fetchMock.mockImplementation((_input, init) => {
      const refreshToken = JSON.parse(init?.body?.toString() ?? '{}').refreshToken;
      if (refreshToken === 'external-refresh-token') {
        return Promise.reject(new TypeError('revocation unavailable'));
      }
      return new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => {
          abortedRevocation();
          reject(new DOMException('The operation was aborted', 'AbortError'));
        });
      });
    });
    vi.useFakeTimers();

    let logoutPromise: Promise<void> = Promise.resolve();
    act(() => {
      logoutPromise = result.current.logout();
    });
    const logoutFailure = logoutPromise.catch((failure: unknown) => failure);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000);
      await logoutFailure;
    });

    await expect(logoutFailure).resolves.toBeInstanceOf(DOMException);
    expect(abortedRevocation).toHaveBeenCalledTimes(1);
    expect(result.current.isLoading).toBe(false);
    expect(result.current.isAuthenticated).toBe(false);
    expect(window.localStorage.getItem(AUTH_SESSION_STORAGE_KEY)).toBeNull();
  });

  it('single-flights StrictMode bootstrap refresh for a near-expiry session', async () => {
    const nearExpiry = {
      ...createAuthSession(authResponse),
      accessTokenExpiresAt: Date.now() + 1_000
    };
    writeAuthSession(nearExpiry, window.localStorage);
    fetchMock.mockResolvedValue(jsonResponse({
      ...authResponse,
      accessToken: 'rotated-access-token',
      refreshToken: 'rotated-refresh-token'
    }));
    const redirectToLogin = vi.fn();

    const { result } = renderHook(() => useAuth(), {
      wrapper: createWrapper({ redirectToLogin, strict: true })
    });

    await waitFor(() => expect(result.current.isInitializing).toBe(false));
    expect(result.current.accessToken).toBe('rotated-access-token');
    expect(result.current.refreshToken).toBe('rotated-refresh-token');
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(redirectToLogin).not.toHaveBeenCalled();
  });

  it('fails closed and redirects once when bootstrap refresh fails', async () => {
    const nearExpiry = {
      ...createAuthSession(authResponse),
      accessTokenExpiresAt: Date.now() + 1_000
    };
    writeAuthSession(nearExpiry, window.localStorage);
    fetchMock.mockResolvedValue(jsonResponse({ code: 'INVALID_REFRESH_TOKEN' }, 401));
    const redirectToLogin = vi.fn();

    const { result } = renderHook(() => useAuth(), {
      wrapper: createWrapper({ redirectToLogin, strict: true })
    });

    await waitFor(() => expect(result.current.isInitializing).toBe(false));
    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.error).not.toBeNull();
    expect(window.localStorage.length).toBe(0);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(redirectToLogin).toHaveBeenCalledTimes(1);
  });

  it('clears logout state immediately and revokes both racing refresh tokens', async () => {
    writeAuthSession(createAuthSession(authResponse), window.localStorage);
    let resolveRefresh: ((response: Response) => void) | undefined;
    const revokedRefreshTokens: string[] = [];
    const refreshResponse = new Promise<Response>((resolve) => {
      resolveRefresh = resolve;
    });
    fetchMock.mockImplementation((input, init) => {
      const url = String(input);
      if (url.endsWith('/auth/refresh')) {
        return refreshResponse;
      }
      revokedRefreshTokens.push(JSON.parse(init?.body?.toString() ?? '{}').refreshToken);
      return Promise.resolve(new Response(null, { status: 204 }));
    });
    const { result } = renderHook(() => useAuth(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isInitializing).toBe(false));

    let refreshPromise: Promise<void> = Promise.resolve();
    act(() => {
      refreshPromise = result.current.refreshTokens();
    });
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    let logoutPromise: Promise<void> = Promise.resolve();
    act(() => {
      logoutPromise = result.current.logout();
    });
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(result.current.isAuthenticated).toBe(false);
    expect(window.localStorage.getItem(AUTH_SESSION_STORAGE_KEY)).toBeNull();
    await act(async () => {
      resolveRefresh?.(jsonResponse({
        ...authResponse,
        accessToken: 'rotated-access-token',
        refreshToken: 'rotated-refresh-token'
      }));
      await expect(refreshPromise).rejects.toBeInstanceOf(AuthenticationSupersededError);
      await logoutPromise;
    });

    expect(revokedRefreshTokens).toEqual(['refresh-token', 'rotated-refresh-token']);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(result.current.isAuthenticated).toBe(false);
    expect(window.localStorage.getItem(AUTH_SESSION_STORAGE_KEY)).toBeNull();
  });

  it('does not let a stale refresh failure clear a newer login session', async () => {
    writeAuthSession(createAuthSession(authResponse), window.localStorage);
    let resolveRefresh: ((response: Response) => void) | undefined;
    const refreshResponse = new Promise<Response>((resolve) => {
      resolveRefresh = resolve;
    });
    const nextUser: UserProfile = {
      ...user,
      id: '5189c865-94aa-41a8-96e5-d9a398647761',
      email: 'next@example.com'
    };
    const nextAuthResponse: AuthResponse = {
      user: nextUser,
      accessToken: 'next-access-token',
      refreshToken: 'next-refresh-token',
      expiresInSeconds: 900
    };
    fetchMock.mockImplementation((input) => String(input).endsWith('/auth/refresh')
      ? refreshResponse
      : Promise.resolve(jsonResponse(nextAuthResponse)));
    const redirectToLogin = vi.fn();
    const { result } = renderHook(() => useAuth(), {
      wrapper: createWrapper({ redirectToLogin })
    });
    await waitFor(() => expect(result.current.isInitializing).toBe(false));

    let refreshPromise: Promise<void> = Promise.resolve();
    act(() => {
      refreshPromise = result.current.refreshTokens();
    });
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    await act(async () => {
      await result.current.login({
        email: 'next@example.com',
        password: 'strong-password'
      });
    });
    expect(result.current.isLoading).toBe(true);
    await act(async () => {
      resolveRefresh?.(jsonResponse({ code: 'INVALID_REFRESH_TOKEN' }, 401));
      await refreshPromise;
    });

    expect(result.current.user).toEqual(nextUser);
    expect(result.current.refreshToken).toBe('next-refresh-token');
    expect(result.current.isLoading).toBe(false);
    expect(redirectToLogin).not.toHaveBeenCalled();
  });

  it('revokes a stale successful refresh without replacing a newer login session', async () => {
    writeAuthSession(createAuthSession(authResponse), window.localStorage);
    let resolveRefresh: ((response: Response) => void) | undefined;
    let revokedRefreshToken: string | undefined;
    const refreshResponse = new Promise<Response>((resolve) => {
      resolveRefresh = resolve;
    });
    const nextUser: UserProfile = {
      ...user,
      id: '5189c865-94aa-41a8-96e5-d9a398647761',
      email: 'next@example.com'
    };
    const nextAuthResponse: AuthResponse = {
      user: nextUser,
      accessToken: 'next-access-token',
      refreshToken: 'next-refresh-token',
      expiresInSeconds: 900
    };
    fetchMock.mockImplementation((input, init) => {
      const url = String(input);
      if (url.endsWith('/auth/refresh')) {
        return refreshResponse;
      }
      if (url.endsWith('/auth/logout')) {
        revokedRefreshToken = JSON.parse(init?.body?.toString() ?? '{}').refreshToken;
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      return Promise.resolve(jsonResponse(nextAuthResponse));
    });
    const { result } = renderHook(() => useAuth(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isInitializing).toBe(false));

    let refreshPromise: Promise<void> = Promise.resolve();
    act(() => {
      refreshPromise = result.current.refreshTokens();
    });
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    await act(async () => {
      await result.current.login({
        email: 'next@example.com',
        password: 'strong-password'
      });
    });
    await act(async () => {
      resolveRefresh?.(jsonResponse({
        ...authResponse,
        accessToken: 'stale-rotated-access-token',
        refreshToken: 'stale-rotated-refresh-token'
      }));
      await refreshPromise;
    });

    expect(revokedRefreshToken).toBe('stale-rotated-refresh-token');
    expect(result.current.user).toEqual(nextUser);
    expect(result.current.refreshToken).toBe('next-refresh-token');
  });

  it('revokes a rotated token when another tab logs out during refresh', async () => {
    writeAuthSession(createAuthSession(authResponse), window.localStorage);
    let resolveRefresh: ((response: Response) => void) | undefined;
    let revokedRefreshToken: string | undefined;
    const refreshResponse = new Promise<Response>((resolve) => {
      resolveRefresh = resolve;
    });
    fetchMock.mockImplementation((input, init) => {
      const url = String(input);
      if (url.endsWith('/auth/refresh')) {
        return refreshResponse;
      }
      revokedRefreshToken = JSON.parse(init?.body?.toString() ?? '{}').refreshToken;
      return Promise.resolve(new Response(null, { status: 204 }));
    });
    const { result } = renderHook(() => useAuth(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isInitializing).toBe(false));

    let refreshPromise: Promise<void> = Promise.resolve();
    act(() => {
      refreshPromise = result.current.refreshTokens();
    });
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    window.localStorage.removeItem(AUTH_SESSION_STORAGE_KEY);
    await act(async () => {
      resolveRefresh?.(jsonResponse({
        ...authResponse,
        accessToken: 'rotated-access-token',
        refreshToken: 'rotated-refresh-token'
      }));
      await expect(refreshPromise).rejects.toBeInstanceOf(AuthenticationSupersededError);
    });

    expect(revokedRefreshToken).toBe('rotated-refresh-token');
    expect(result.current.isAuthenticated).toBe(false);
    expect(window.localStorage.length).toBe(0);
  });

  it('does not overwrite another provider login with a stale refresh result', async () => {
    writeAuthSession(createAuthSession(authResponse), window.localStorage);
    let resolveRefresh: ((response: Response) => void) | undefined;
    let revokedRefreshToken: string | undefined;
    const refreshResponse = new Promise<Response>((resolve) => {
      resolveRefresh = resolve;
    });
    const nextUser: UserProfile = {
      ...user,
      id: '5189c865-94aa-41a8-96e5-d9a398647761',
      email: 'next@example.com'
    };
    const nextAuthResponse: AuthResponse = {
      user: nextUser,
      accessToken: 'next-access-token',
      refreshToken: 'next-refresh-token',
      expiresInSeconds: 900
    };
    fetchMock.mockImplementation((input, init) => {
      const url = String(input);
      if (url.endsWith('/auth/refresh')) {
        return refreshResponse;
      }
      if (url.endsWith('/auth/logout')) {
        revokedRefreshToken = JSON.parse(init?.body?.toString() ?? '{}').refreshToken;
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      return Promise.resolve(jsonResponse(nextAuthResponse));
    });
    const first = renderHook(() => useAuth(), { wrapper: createWrapper() });
    const second = renderHook(() => useAuth(), { wrapper: createWrapper() });
    await waitFor(() => {
      expect(first.result.current.isInitializing).toBe(false);
      expect(second.result.current.isInitializing).toBe(false);
    });

    let refreshPromise: Promise<void> = Promise.resolve();
    act(() => {
      refreshPromise = first.result.current.refreshTokens();
    });
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    await act(async () => {
      await second.result.current.login({
        email: 'next@example.com',
        password: 'strong-password'
      });
    });
    await act(async () => {
      resolveRefresh?.(jsonResponse({
        ...authResponse,
        accessToken: 'stale-rotated-access-token',
        refreshToken: 'stale-rotated-refresh-token'
      }));
      await refreshPromise;
    });

    expect(revokedRefreshToken).toBe('stale-rotated-refresh-token');
    expect(first.result.current.user).toEqual(nextUser);
    expect(second.result.current.user).toEqual(nextUser);
    expect(window.localStorage.getItem(AUTH_SESSION_STORAGE_KEY)).toContain('next-refresh-token');
  });

  it('rejects a delayed protected response when persisted logout precedes its storage event', async () => {
    writeAuthSession(createAuthSession(authResponse), window.localStorage);
    let resolveResponse: ((response: Response) => void) | undefined;
    fetchMock.mockReturnValue(new Promise<Response>((resolve) => {
      resolveResponse = resolve;
    }));
    const { result } = renderHook(() => ({
      auth: useAuth(),
      client: useApiClient()
    }), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.auth.isInitializing).toBe(false));

    const request = result.current.client.request('/users/me', { authenticated: true });
    const requestFailure = request.catch((failure: unknown) => failure);
    advanceAuthGeneration(window.localStorage);
    clearAuthSession(window.localStorage);
    await act(async () => {
      resolveResponse?.(jsonResponse(user));
      await requestFailure;
    });

    await expect(requestFailure).resolves.toBeInstanceOf(AuthenticationSupersededError);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('coordinates refresh and logout across providers without losing a rotated token', async () => {
    const requestLock = installSequentialWebLocks();
    writeAuthSession(createAuthSession(authResponse), window.localStorage);
    let resolveRefresh: ((response: Response) => void) | undefined;
    const revokedRefreshTokens: string[] = [];
    const refreshResponse = new Promise<Response>((resolve) => {
      resolveRefresh = resolve;
    });
    fetchMock.mockImplementation((input, init) => {
      if (String(input).endsWith('/auth/refresh')) {
        return refreshResponse;
      }
      revokedRefreshTokens.push(JSON.parse(init?.body?.toString() ?? '{}').refreshToken);
      return Promise.resolve(new Response(null, { status: 204 }));
    });
    const first = renderHook(() => useAuth(), { wrapper: createWrapper() });
    const second = renderHook(() => useAuth(), { wrapper: createWrapper() });
    await waitFor(() => {
      expect(first.result.current.isInitializing).toBe(false);
      expect(second.result.current.isInitializing).toBe(false);
    });

    let refreshPromise: Promise<void> = Promise.resolve();
    act(() => {
      refreshPromise = first.result.current.refreshTokens();
    });
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    let logoutPromise: Promise<void> = Promise.resolve();
    act(() => {
      logoutPromise = second.result.current.logout();
    });
    expect(second.result.current.isAuthenticated).toBe(false);
    await act(async () => {
      resolveRefresh?.(jsonResponse({
        ...authResponse,
        accessToken: 'rotated-access-token',
        refreshToken: 'rotated-refresh-token'
      }));
      await expect(refreshPromise).rejects.toBeInstanceOf(AuthenticationSupersededError);
      await logoutPromise;
    });

    expect(requestLock).toHaveBeenCalledTimes(2);
    expect(revokedRefreshTokens).toEqual(['rotated-refresh-token', 'refresh-token']);
    expect(first.result.current.isAuthenticated).toBe(false);
    expect(second.result.current.isAuthenticated).toBe(false);
    expect(window.localStorage.getItem(AUTH_SESSION_STORAGE_KEY)).toBeNull();
  });

  it('rejects a login that began before another provider logged out', async () => {
    writeAuthSession(createAuthSession(authResponse), window.localStorage);
    let resolveLogin: ((response: Response) => void) | undefined;
    const revokedRefreshTokens: string[] = [];
    const loginResponse = new Promise<Response>((resolve) => {
      resolveLogin = resolve;
    });
    const nextUser: UserProfile = {
      ...user,
      id: '5189c865-94aa-41a8-96e5-d9a398647761',
      email: 'next@example.com'
    };
    const nextAuthResponse: AuthResponse = {
      user: nextUser,
      accessToken: 'next-access-token',
      refreshToken: 'next-refresh-token',
      expiresInSeconds: 900
    };
    fetchMock.mockImplementation((input, init) => {
      if (String(input).endsWith('/auth/login')) {
        return loginResponse;
      }
      revokedRefreshTokens.push(JSON.parse(init?.body?.toString() ?? '{}').refreshToken);
      return Promise.resolve(new Response(null, { status: 204 }));
    });
    const first = renderHook(() => useAuth(), { wrapper: createWrapper() });
    const second = renderHook(() => useAuth(), { wrapper: createWrapper() });
    await waitFor(() => {
      expect(first.result.current.isInitializing).toBe(false);
      expect(second.result.current.isInitializing).toBe(false);
    });

    let loginPromise: Promise<UserProfile> = Promise.resolve(user);
    act(() => {
      loginPromise = first.result.current.login({
        email: 'next@example.com',
        password: 'strong-password'
      });
    });
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    await act(async () => {
      await second.result.current.logout();
    });
    await act(async () => {
      resolveLogin?.(jsonResponse(nextAuthResponse));
      await expect(loginPromise).rejects.toBeInstanceOf(AuthenticationSupersededError);
    });

    expect(revokedRefreshTokens).toEqual(['refresh-token', 'next-refresh-token']);
    expect(first.result.current.isAuthenticated).toBe(false);
    expect(second.result.current.isAuthenticated).toBe(false);
    expect(window.localStorage.getItem(AUTH_SESSION_STORAGE_KEY)).toBeNull();
  });

  it('revokes a superseded login response and keeps the latest login session', async () => {
    let resolveFirstLogin: ((response: Response) => void) | undefined;
    let revokedRefreshToken: string | undefined;
    const firstLoginResponse = new Promise<Response>((resolve) => {
      resolveFirstLogin = resolve;
    });
    const nextUser: UserProfile = {
      ...user,
      id: '5189c865-94aa-41a8-96e5-d9a398647761',
      email: 'next@example.com'
    };
    const nextAuthResponse: AuthResponse = {
      user: nextUser,
      accessToken: 'next-access-token',
      refreshToken: 'next-refresh-token',
      expiresInSeconds: 900
    };
    let loginCalls = 0;
    fetchMock.mockImplementation((input, init) => {
      const url = String(input);
      if (url.endsWith('/auth/logout')) {
        revokedRefreshToken = JSON.parse(init?.body?.toString() ?? '{}').refreshToken;
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      loginCalls += 1;
      return loginCalls === 1
        ? firstLoginResponse
        : Promise.resolve(jsonResponse(nextAuthResponse));
    });
    const { result } = renderHook(() => useAuth(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isInitializing).toBe(false));

    let firstLoginPromise: Promise<UserProfile> = Promise.resolve(user);
    act(() => {
      firstLoginPromise = result.current.login({
        email: 'viewer@example.com',
        password: 'strong-password'
      });
    });
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    await act(async () => {
      await result.current.login({
        email: 'next@example.com',
        password: 'strong-password'
      });
    });
    await act(async () => {
      resolveFirstLogin?.(jsonResponse(authResponse));
      await expect(firstLoginPromise).rejects.toBeInstanceOf(AuthenticationSupersededError);
    });

    expect(revokedRefreshToken).toBe('refresh-token');
    expect(result.current.user).toEqual(nextUser);
    expect(result.current.refreshToken).toBe('next-refresh-token');
    expect(result.current.isLoading).toBe(false);
  });

  it('clears private query cache whenever the authenticated user changes', async () => {
    writeAuthSession(createAuthSession(authResponse), window.localStorage);
    const queryClient = new QueryClient();
    queryClient.setQueryData(['private'], { owner: user.id });
    const nextUser: UserProfile = {
      ...user,
      id: '5189c865-94aa-41a8-96e5-d9a398647761',
      email: 'next@example.com'
    };
    const nextAuthResponse: AuthResponse = {
      user: nextUser,
      accessToken: 'next-access-token',
      refreshToken: 'next-refresh-token',
      expiresInSeconds: 900
    };
    fetchMock.mockResolvedValue(jsonResponse(nextAuthResponse));
    const { result } = renderHook(() => useAuth(), {
      wrapper: createWrapper({ queryClient })
    });
    await waitFor(() => expect(result.current.isInitializing).toBe(false));

    await act(async () => {
      await result.current.login({
        email: 'next@example.com',
        password: 'strong-password'
      });
    });
    expect(queryClient.getQueryData(['private'])).toBeUndefined();

    queryClient.setQueryData(['private'], { owner: nextUser.id });
    writeAuthSession(createAuthSession(authResponse), window.localStorage);
    act(() => {
      window.dispatchEvent(new StorageEvent('storage', {
        key: AUTH_SESSION_STORAGE_KEY,
        storageArea: window.localStorage
      }));
    });
    await waitFor(() => expect(result.current.user).toEqual(user));
    expect(queryClient.getQueryData(['private'])).toBeUndefined();
  });

  it('serializes refresh across providers with a same-origin Web Lock', async () => {
    writeAuthSession(createAuthSession(authResponse), window.localStorage);
    const requestLock = installSequentialWebLocks();
    fetchMock.mockResolvedValue(jsonResponse({
      ...authResponse,
      accessToken: 'rotated-access-token',
      refreshToken: 'rotated-refresh-token'
    }));
    const first = renderHook(() => useAuth(), { wrapper: createWrapper() });
    const second = renderHook(() => useAuth(), { wrapper: createWrapper() });
    await waitFor(() => {
      expect(first.result.current.isInitializing).toBe(false);
      expect(second.result.current.isInitializing).toBe(false);
    });

    await act(async () => {
      await Promise.all([
        first.result.current.refreshTokens(),
        second.result.current.refreshTokens()
      ]);
    });

    expect(requestLock).toHaveBeenCalledTimes(2);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(first.result.current.refreshToken).toBe('rotated-refresh-token');
    expect(second.result.current.refreshToken).toBe('rotated-refresh-token');
  });

  it('adopts a refresh session rotated by another tab instead of clearing it', async () => {
    writeAuthSession(createAuthSession(authResponse), window.localStorage);
    let resolveRefresh: ((response: Response) => void) | undefined;
    fetchMock.mockReturnValue(new Promise<Response>((resolve) => {
      resolveRefresh = resolve;
    }));
    const redirectToLogin = vi.fn();
    const { result } = renderHook(() => useAuth(), {
      wrapper: createWrapper({ redirectToLogin })
    });
    await waitFor(() => expect(result.current.isInitializing).toBe(false));

    let refreshPromise: Promise<void> = Promise.resolve();
    act(() => {
      refreshPromise = result.current.refreshTokens();
    });
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const otherTabResponse: AuthResponse = {
      ...authResponse,
      accessToken: 'other-tab-access-token',
      refreshToken: 'other-tab-refresh-token'
    };
    writeAuthSession(createAuthSession(otherTabResponse), window.localStorage);
    await act(async () => {
      resolveRefresh?.(jsonResponse({ code: 'INVALID_REFRESH_TOKEN' }, 401));
      await refreshPromise;
    });

    expect(result.current.accessToken).toBe('other-tab-access-token');
    expect(result.current.refreshToken).toBe('other-tab-refresh-token');
    expect(redirectToLogin).not.toHaveBeenCalled();
  });
});

interface WrapperOptions {
  queryClient?: QueryClient;
  redirectToLogin?: () => void;
  strict?: boolean;
}

function createWrapper(options: WrapperOptions = {}) {
  const queryClient = options.queryClient ?? new QueryClient({
    defaultOptions: { queries: { retry: false } }
  });
  return function Wrapper({ children }: PropsWithChildren) {
    const content = (
      <QueryClientProvider client={queryClient}>
        <AuthProvider
          storage={window.localStorage}
          redirectToLogin={options.redirectToLogin ?? vi.fn()}
        >
          {children}
        </AuthProvider>
      </QueryClientProvider>
    );
    return options.strict ? <StrictMode>{content}</StrictMode> : content;
  };
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' }
  });
}

function installSequentialWebLocks() {
  let lockTail = Promise.resolve();
  const requestLock = vi.fn((
    _name: string,
    optionsOrTask: unknown,
    possibleTask?: () => Promise<unknown>
  ) => {
    const task = typeof optionsOrTask === 'function'
      ? optionsOrTask as () => Promise<unknown>
      : possibleTask;
    if (!task) {
      throw new Error('A lock callback is required');
    }
    const result = lockTail.then(task);
    lockTail = result.then(() => undefined, () => undefined);
    return result;
  });
  Object.defineProperty(navigator, 'locks', {
    configurable: true,
    value: { request: requestLock }
  });
  return requestLock;
}
