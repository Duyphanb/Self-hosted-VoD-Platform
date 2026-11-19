import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, screen } from '@testing-library/react';
import { StrictMode, type PropsWithChildren } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider, useAuth } from './AuthContext';
import { AUTH_GENERATION_STORAGE_KEY, emptyMarker } from './authStorage';

const user = { id: 'timeout-viewer', email: 'timeout@example.com', displayName: 'Viewer', roles: ['ROLE_USER'] };
const authentication = { user, accessToken: 'fixture-memory-only', expiresInSeconds: 900 };

function setup() {
  const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: PropsWithChildren) => (
    <StrictMode>
      <QueryClientProvider client={cache}>
        <AuthProvider redirectToLogin={vi.fn()}>{children}</AuthProvider>
      </QueryClientProvider>
    </StrictMode>
  );
  return renderHook(() => useAuth(), { wrapper });
}

function signedInMarker() {
  window.localStorage.setItem(AUTH_GENERATION_STORAGE_KEY,
    JSON.stringify({ ...emptyMarker(), userId: user.id }));
}

describe('real auth transport timeouts', () => {
  const fetchMock = vi.fn<typeof fetch>();

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    fetchMock.mockReset().mockImplementation(() => new Promise<Response>(() => undefined));
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it.each(['login', 'refresh', 'logout'] as const)('aborts the actual %s fetch before its lock is released', async (operation) => {
    if (operation !== 'login') {
      signedInMarker();
      fetchMock.mockResolvedValueOnce(new Response(JSON.stringify(authentication), {
        status: 200, headers: { 'Content-Type': 'application/json' }
      }));
    }
    const { result } = setup();
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    expect(result.current.isAuthenticated).toBe(operation !== 'login');

    await act(async () => {
      const pending = operation === 'login'
        ? result.current.login({ email: user.email, password: 'fixture-password' })
        : operation === 'refresh' ? result.current.refreshTokens() : result.current.logout();
      const rejected = expect(pending).rejects.toThrow('timed out');
      await vi.advanceTimersByTimeAsync(10_001);
      await rejected;
    });

    const [url, init] = fetchMock.mock.calls.at(-1)!;
    expect(url).toBe(`/api/v1/auth/${operation}`);
    expect(init?.signal).toBeInstanceOf(AbortSignal);
    expect(init?.signal?.aborted).toBe(true);
    if (operation === 'refresh') {
      expect(result.current.isAuthenticated).toBe(true);
      expect(result.current.error?.name).toBe('TimeoutError');
      expect(JSON.parse(window.localStorage.getItem(AUTH_GENERATION_STORAGE_KEY)!).userId).toBe(user.id);
      expect(screen.getByRole('button', { name: 'Retry sign-in' })).toBeInTheDocument();
    } else if (operation === 'logout') {
      expect(result.current.isAuthenticated).toBe(false);
      expect(JSON.parse(window.localStorage.getItem(AUTH_GENERATION_STORAGE_KEY)!).logoutPending).toBe(true);
    }
  });

  it('keeps a timed-out bootstrap retryable without writing a signed-out marker', async () => {
    signedInMarker();
    const { result } = setup();
    await act(async () => { await vi.advanceTimersByTimeAsync(10_001); });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][1]?.signal?.aborted).toBe(true);
    expect(result.current.isInitializing).toBe(true);
    expect(result.current.error?.name).toBe('TimeoutError');
    expect(JSON.parse(window.localStorage.getItem(AUTH_GENERATION_STORAGE_KEY)!).userId).toBe(user.id);
    expect(screen.getByRole('button', { name: 'Retry sign-in' })).toBeInTheDocument();
  });
});
