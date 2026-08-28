import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ApiClient,
  ApiRequestError,
  AuthenticationSupersededError,
  type AuthenticationSnapshot
} from './client';

describe('ApiClient', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('attaches the bearer token to authenticated requests', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ status: 'ok' }));
    const client = new ApiClient('/api/v1', {
      getAuthenticationSnapshot: () => authentication('user-1', 'access-token'),
      refreshAccessToken: vi.fn(),
      onAuthenticationFailure: vi.fn()
    });

    await client.request('/users/me', { authenticated: true });

    const headers = new Headers(fetchMock.mock.calls[0][1]?.headers);
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/users/me');
    expect(headers.get('Authorization')).toBe('Bearer access-token');
  });

  it('single-flights rotating refresh and retries concurrent requests once', async () => {
    let token = 'expired-token';
    const refreshAccessToken = vi.fn(async () => {
      token = 'rotated-token';
      return token;
    });
    fetchMock.mockImplementation(async (_input, init) => {
      const authorization = new Headers(init?.headers).get('Authorization');
      return authorization === 'Bearer rotated-token'
        ? jsonResponse({ status: 'ok' })
        : jsonResponse({ code: 'UNAUTHORIZED' }, 401);
    });
    const client = new ApiClient('/api/v1', {
      getAuthenticationSnapshot: () => authentication('user-1', token),
      refreshAccessToken,
      onAuthenticationFailure: vi.fn()
    });

    await Promise.all([
      client.request('/movies', { authenticated: true }),
      client.request('/users/me', { authenticated: true })
    ]);

    expect(refreshAccessToken).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it('retries a delayed old-token 401 with the already-rotated token', async () => {
    let token = 'expired-token';
    let expiredTokenCalls = 0;
    let resolveDelayedResponse: ((response: Response) => void) | undefined;
    const refreshAccessToken = vi.fn(async () => {
      token = 'rotated-token';
      return token;
    });
    fetchMock.mockImplementation((_input, init) => {
      const authorization = new Headers(init?.headers).get('Authorization');
      if (authorization === 'Bearer rotated-token') {
        return Promise.resolve(jsonResponse({ status: 'ok' }));
      }
      expiredTokenCalls += 1;
      if (expiredTokenCalls === 2) {
        return new Promise<Response>((resolve) => {
          resolveDelayedResponse = resolve;
        });
      }
      return Promise.resolve(jsonResponse({ code: 'UNAUTHORIZED' }, 401));
    });
    const client = new ApiClient('/api/v1', {
      getAuthenticationSnapshot: () => authentication('user-1', token),
      refreshAccessToken,
      onAuthenticationFailure: vi.fn()
    });

    const firstRequest = client.request('/movies', { authenticated: true });
    const delayedRequest = client.request('/users/me', { authenticated: true });
    await firstRequest;
    resolveDelayedResponse?.(jsonResponse({ code: 'UNAUTHORIZED' }, 401));
    await delayedRequest;

    expect(refreshAccessToken).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it('does not replay a request under a different user identity', async () => {
    let currentAuthentication = authentication('user-a', 'token-a');
    let resolveInitialResponse: ((response: Response) => void) | undefined;
    fetchMock.mockReturnValue(new Promise<Response>((resolve) => {
      resolveInitialResponse = resolve;
    }));
    const refreshAccessToken = vi.fn();
    const onAuthenticationFailure = vi.fn();
    const client = new ApiClient('/api/v1', {
      getAuthenticationSnapshot: () => currentAuthentication,
      refreshAccessToken,
      onAuthenticationFailure
    });

    const request = client.request('/watchlist', {
      method: 'POST',
      authenticated: true,
      json: { movieId: 'movie-1' }
    });
    currentAuthentication = authentication('user-b', 'token-b');
    resolveInitialResponse?.(jsonResponse(apiError(), 401));

    await expect(request).rejects.toBeInstanceOf(AuthenticationSupersededError);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(refreshAccessToken).not.toHaveBeenCalled();
    expect(onAuthenticationFailure).not.toHaveBeenCalled();
  });

  it('rejects a delayed successful response after the user identity changes', async () => {
    let currentAuthentication = authentication('user-a', 'token-a');
    let resolveResponse: ((response: Response) => void) | undefined;
    fetchMock.mockReturnValue(new Promise<Response>((resolve) => {
      resolveResponse = resolve;
    }));
    const client = new ApiClient('/api/v1', {
      getAuthenticationSnapshot: () => currentAuthentication,
      refreshAccessToken: vi.fn(),
      onAuthenticationFailure: vi.fn()
    });

    const request = client.request('/users/me', { authenticated: true });
    currentAuthentication = authentication('user-b', 'token-b');
    resolveResponse?.(jsonResponse({ id: 'user-a' }));

    await expect(request).rejects.toBeInstanceOf(AuthenticationSupersededError);
  });

  it('rejects a delayed successful mutation after the user identity changes', async () => {
    let currentAuthentication = authentication('user-a', 'token-a');
    let resolveResponse: ((response: Response) => void) | undefined;
    fetchMock.mockReturnValue(new Promise<Response>((resolve) => {
      resolveResponse = resolve;
    }));
    const client = new ApiClient('/api/v1', {
      getAuthenticationSnapshot: () => currentAuthentication,
      refreshAccessToken: vi.fn(),
      onAuthenticationFailure: vi.fn()
    });

    const request = client.request('/watchlist/movie-1', {
      method: 'DELETE',
      authenticated: true
    });
    currentAuthentication = authentication('user-b', 'token-b');
    resolveResponse?.(new Response(null, { status: 204 }));

    await expect(request).rejects.toBeInstanceOf(AuthenticationSupersededError);
  });

  it('rechecks user identity after a delayed response body is consumed', async () => {
    let currentAuthentication = authentication('user-a', 'token-a');
    let resolveBody: ((body: string) => void) | undefined;
    let markBodyRead: (() => void) | undefined;
    const bodyRead = new Promise<void>((resolve) => {
      markBodyRead = resolve;
    });
    const response = new Response(null, {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    });
    vi.spyOn(response, 'text').mockImplementation(() => {
      markBodyRead?.();
      return new Promise<string>((resolve) => {
        resolveBody = resolve;
      });
    });
    fetchMock.mockResolvedValue(response);
    const client = new ApiClient('/api/v1', {
      getAuthenticationSnapshot: () => currentAuthentication,
      refreshAccessToken: vi.fn(),
      onAuthenticationFailure: vi.fn()
    });

    const request = client.request('/users/me', { authenticated: true });
    await bodyRead;
    currentAuthentication = authentication('user-b', 'token-b');
    resolveBody?.(JSON.stringify({ id: 'user-a' }));

    await expect(request).rejects.toBeInstanceOf(AuthenticationSupersededError);
  });

  it('does not clear a newer token when an earlier retry returns 401', async () => {
    let currentAuthentication = authentication('user-a', 'token-0');
    let resolveRetryResponse: ((response: Response) => void) | undefined;
    const retryResponse = new Promise<Response>((resolve) => {
      resolveRetryResponse = resolve;
    });
    fetchMock.mockImplementation((_input, init) => {
      const authorization = new Headers(init?.headers).get('Authorization');
      return authorization === 'Bearer token-0'
        ? Promise.resolve(jsonResponse(apiError(), 401))
        : retryResponse;
    });
    const refreshAccessToken = vi.fn(async () => {
      currentAuthentication = authentication('user-a', 'token-1');
      return 'token-1';
    });
    const onAuthenticationFailure = vi.fn();
    const client = new ApiClient('/api/v1', {
      getAuthenticationSnapshot: () => currentAuthentication,
      refreshAccessToken,
      onAuthenticationFailure
    });

    const request = client.request('/users/me', { authenticated: true });
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    currentAuthentication = authentication('user-a', 'token-2');
    resolveRetryResponse?.(jsonResponse(apiError(), 401));

    await expect(request).rejects.toBeInstanceOf(ApiRequestError);
    expect(refreshAccessToken).toHaveBeenCalledTimes(1);
    expect(onAuthenticationFailure).not.toHaveBeenCalled();
  });

  it('clears authentication after refresh failure or a retried 401', async () => {
    let token = 'expired-token';
    const onAuthenticationFailure = vi.fn();
    fetchMock.mockResolvedValue(jsonResponse(apiError(), 401));
    const client = new ApiClient('/api/v1', {
      getAuthenticationSnapshot: () => authentication('user-1', token),
      refreshAccessToken: vi.fn(async () => {
        token = 'still-invalid-token';
        return token;
      }),
      onAuthenticationFailure
    });

    await expect(client.request('/users/me', { authenticated: true }))
      .rejects.toBeInstanceOf(ApiRequestError);

    expect(onAuthenticationFailure).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('treats malformed optional API error fields as an unstructured error', async () => {
    fetchMock.mockResolvedValue(jsonResponse({
      ...apiError(),
      fieldErrors: [null]
    }, 400));
    const client = new ApiClient('/api/v1', {
      getAuthenticationSnapshot: () => null,
      refreshAccessToken: vi.fn(),
      onAuthenticationFailure: vi.fn()
    });

    const request = client.request('/auth/register', { method: 'POST' });

    await expect(request).rejects.toMatchObject({
      message: 'Request failed with status 400',
      apiError: null
    });
  });
});

function authentication(
  userId: string,
  accessToken: string,
  generation = 0
): AuthenticationSnapshot {
  return { userId, accessToken, generation };
}

function apiError(): object {
  return {
    timestamp: '2026-08-21T15:00:00Z',
    status: 401,
    code: 'UNAUTHORIZED',
    message: 'Authentication required'
  };
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' }
  });
}
