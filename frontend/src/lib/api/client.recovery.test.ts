import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ApiClient,
  ApiRequestError,
  AuthenticationSupersededError,
  type AuthenticationSnapshot,
  type AuthRequestController
} from './client';

/** The recovery API that callers sending their own requests (the HLS player) share with JSON requests. */
describe('ApiClient shared authentication recovery', () => {
  const fetchMock = vi.fn<typeof fetch>();
  let current: AuthenticationSnapshot | null;
  let controller: AuthRequestController & {
    refreshAccessToken: ReturnType<typeof vi.fn<() => Promise<string>>>;
    onAuthenticationFailure: ReturnType<typeof vi.fn<(error: unknown) => void>>;
  };
  let client: ApiClient;

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
    current = snapshot('alice', 'token-1', 1);
    controller = {
      getAuthenticationSnapshot: () => current,
      refreshAccessToken: vi.fn<() => Promise<string>>(async () => {
        current = snapshot('alice', 'token-2', 1);
        return 'token-2';
      }),
      onAuthenticationFailure: vi.fn<(error: unknown) => void>()
    };
    client = new ApiClient('/api/v1', controller);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  describe('recoverAuthentication', () => {
    it('refreshes once and returns the refreshed authentication', async () => {
      const recovered = await client.recoverAuthentication(snapshot('alice', 'token-1', 1));

      expect(recovered.accessToken).toBe('token-2');
      expect(controller.refreshAccessToken).toHaveBeenCalledTimes(1);
    });

    it('joins one single-flight refresh for concurrent callers', async () => {
      const rejected = snapshot('alice', 'token-1', 1);

      const results = await Promise.all([
        client.recoverAuthentication(rejected),
        client.recoverAuthentication(rejected)
      ]);

      expect(results.map((result) => result.accessToken)).toEqual(['token-2', 'token-2']);
      expect(controller.refreshAccessToken).toHaveBeenCalledTimes(1);
    });

    it('answers a late 401 of an older token with the current token and no refresh', async () => {
      current = snapshot('alice', 'token-3', 1);

      const recovered = await client.recoverAuthentication(snapshot('alice', 'token-1', 1));

      expect(recovered.accessToken).toBe('token-3');
      expect(controller.refreshAccessToken).not.toHaveBeenCalled();
    });

    it('ends the session when the refresh is rejected with 401', async () => {
      const rejection = new ApiRequestError(401, null);
      controller.refreshAccessToken.mockRejectedValueOnce(rejection);

      await expect(client.recoverAuthentication(snapshot('alice', 'token-1', 1))).rejects.toBe(rejection);

      expect(controller.onAuthenticationFailure).toHaveBeenCalledWith(rejection);
    });

    it('keeps the session when the refresh fails with 503', async () => {
      controller.refreshAccessToken.mockRejectedValueOnce(new ApiRequestError(503, null));

      await expect(client.recoverAuthentication(snapshot('alice', 'token-1', 1)))
        .rejects.toMatchObject({ status: 503 });

      expect(controller.onAuthenticationFailure).not.toHaveBeenCalled();
    });

    it('keeps the session when the refresh fails by a network error', async () => {
      controller.refreshAccessToken.mockRejectedValueOnce(new TypeError('Failed to fetch'));

      await expect(client.recoverAuthentication(snapshot('alice', 'token-1', 1))).rejects.toThrow(TypeError);

      expect(controller.onAuthenticationFailure).not.toHaveBeenCalled();
    });

    it('is superseded when another account signed in before the 401 was handled', async () => {
      current = snapshot('bob', 'bob-token', 2);

      await expect(client.recoverAuthentication(snapshot('alice', 'token-1', 1)))
        .rejects.toBeInstanceOf(AuthenticationSupersededError);

      expect(controller.refreshAccessToken).not.toHaveBeenCalled();
    });

    it('is superseded, without ending the new session, when the account changes during the refresh', async () => {
      controller.refreshAccessToken.mockImplementationOnce(async () => {
        current = snapshot('bob', 'bob-token', 2);
        throw new ApiRequestError(401, null);
      });

      await expect(client.recoverAuthentication(snapshot('alice', 'token-1', 1))).rejects.toMatchObject({
        status: 401
      });

      expect(controller.onAuthenticationFailure).not.toHaveBeenCalled();
    });
  });

  describe('rejectAuthentication', () => {
    it('ends the session when the retry was sent with the current authentication', () => {
      const error = new ApiRequestError(401, null);

      client.rejectAuthentication(snapshot('alice', 'token-1', 1), error);

      expect(controller.onAuthenticationFailure).toHaveBeenCalledWith(error);
    });

    it('ignores a retry that was sent with an older token', () => {
      current = snapshot('alice', 'token-2', 1);

      client.rejectAuthentication(snapshot('alice', 'token-1', 1), new ApiRequestError(401, null));

      expect(controller.onAuthenticationFailure).not.toHaveBeenCalled();
    });

    it('ignores a retry of an older identity', () => {
      current = snapshot('bob', 'token-1', 2);

      client.rejectAuthentication(snapshot('alice', 'token-1', 1), new ApiRequestError(401, null));

      expect(controller.onAuthenticationFailure).not.toHaveBeenCalled();
    });
  });

  describe('identity of a request', () => {
    it('sends nothing for a request bound to another identity', async () => {
      current = snapshot('bob', 'bob-token', 2);

      const request = client.request('/episodes/movie-1/progress', {
        method: 'PUT',
        authenticated: true,
        identity: snapshot('alice', 'token-1', 1),
        json: { currentSeconds: 42 }
      });

      await expect(request).rejects.toBeInstanceOf(AuthenticationSupersededError);
      expect(fetchMock).not.toHaveBeenCalled();
    });

    it('checks the identity again right before the retry is sent', async () => {
      fetchMock.mockResolvedValue(new Response(null, { status: 401 }));
      let snapshotReads = 0;
      controller.getAuthenticationSnapshot = () => {
        snapshotReads += 1;
        // The refresh succeeded for Alice, then Bob signed in before the retry left.
        if (snapshotReads <= 3) {
          return current;
        }
        return snapshot('bob', 'bob-token', 2);
      };

      await expect(client.request('/users/me', { authenticated: true }))
        .rejects.toBeInstanceOf(AuthenticationSupersededError);

      expect(fetchMock).toHaveBeenCalledTimes(1);
    });
  });

  describe('sendOnUnload', () => {
    it('sends a keepalive request with the current token in the Authorization header', () => {
      fetchMock.mockResolvedValue(new Response(null, { status: 200 }));
      current = snapshot('alice', 'token-2', 1);

      const sent = client.sendOnUnload(
        '/episodes/movie-1/progress',
        { method: 'PUT', json: { currentSeconds: 42 } },
        snapshot('alice', 'token-1', 1)
      );

      expect(sent).toBe(true);
      const [url, init] = fetchMock.mock.calls[0];
      expect(url).toBe('/api/v1/episodes/movie-1/progress');
      expect(String(url)).not.toContain('token');
      expect(init?.method).toBe('PUT');
      expect(init?.keepalive).toBe(true);
      expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer token-2');
      expect(JSON.parse(String(init?.body))).toEqual({ currentSeconds: 42 });
    });

    it('sends nothing for another identity', () => {
      current = snapshot('bob', 'bob-token', 2);

      const sent = client.sendOnUnload('/x', { method: 'PUT', json: {} }, snapshot('alice', 'token-1', 1));

      expect(sent).toBe(false);
      expect(fetchMock).not.toHaveBeenCalled();
    });

    it('never refreshes or retries a rejected unload request', async () => {
      fetchMock.mockResolvedValue(new Response(null, { status: 401 }));

      client.sendOnUnload('/x', { method: 'PUT', json: {} }, snapshot('alice', 'token-1', 1));
      await Promise.resolve();
      await Promise.resolve();

      expect(fetchMock).toHaveBeenCalledTimes(1);
      expect(controller.refreshAccessToken).not.toHaveBeenCalled();
      expect(controller.onAuthenticationFailure).not.toHaveBeenCalled();
    });

    it('swallows a network failure', async () => {
      fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));

      expect(client.sendOnUnload('/x', { method: 'PUT', json: {} }, snapshot('alice', 'token-1', 1))).toBe(true);
      await Promise.resolve();
    });
  });
});

function snapshot(userId: string, accessToken: string, generation: number): AuthenticationSnapshot {
  return { userId, accessToken, generation };
}
