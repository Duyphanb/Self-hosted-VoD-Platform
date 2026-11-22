import Hls from 'hls.js';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ApiClient,
  ApiRequestError,
  type AuthenticationSnapshot,
  type AuthRequestController
} from '../../lib/api/client';
import { FakeXhr } from '../../test/fakeXhr';
import { HlsRequestAuthenticator, isAttemptAbandoned, RETRY_WINDOW_MS } from './hlsAuthentication';

const MANIFEST_URL = `${window.location.origin}/hls/video-assets/asset-1/master.m3u8`;
const SEGMENT_URL = `${window.location.origin}/hls/video-assets/asset-1/segments/seg_00001.ts`;
/** An hls.js loader that gave its attempt up, as `this` of the `xhrSetup` call. */
const ABORTED_LOADER = { stats: { aborted: true } };

describe('HlsRequestAuthenticator', () => {
  let current: AuthenticationSnapshot | null;
  let refresh: ReturnType<typeof vi.fn<() => Promise<string>>>;
  let onAuthenticationFailure: ReturnType<typeof vi.fn<(error: unknown) => void>>;
  let authenticator: HlsRequestAuthenticator;

  beforeEach(() => {
    current = snapshot('alice', 'token-1', 1);
    refresh = vi.fn<() => Promise<string>>(async () => {
      current = snapshot('alice', 'token-2', 1);
      return 'token-2';
    });
    onAuthenticationFailure = vi.fn<(error: unknown) => void>();
    const controller: AuthRequestController = {
      getAuthenticationSnapshot: () => current,
      refreshAccessToken: refresh,
      onAuthenticationFailure
    };
    const client = new ApiClient('/api/v1', controller);
    authenticator = new HlsRequestAuthenticator(client, snapshot('alice', 'token-1', 1));
  });

  describe('request setup', () => {
    it('sends the current token in the Authorization header and never in the URL or a cookie', async () => {
      current = snapshot('alice', 'token-7', 1);

      const xhr = await setUp(SEGMENT_URL);

      expect(xhr.method).toBe('GET');
      expect(xhr.url).toBe(SEGMENT_URL);
      expect(xhr.headers.authorization).toBe('Bearer token-7');
      expect(xhr.withCredentials).toBe(false);
    });

    it('rejects a request when another account signed in', async () => {
      current = snapshot('bob', 'bob-token', 2);

      await expect(setUp(SEGMENT_URL)).rejects.toThrow('Authentication changed');
    });
  });

  describe('one recovery and one retry per request', () => {
    it('recovers a 401 once and retries with the refreshed token', async () => {
      await failWith(await setUp(MANIFEST_URL), 401);

      const retryAllowed = authenticator.shouldRetry(null, 0, false, { url: MANIFEST_URL, code: 401 }, false);
      const retry = await setUp(MANIFEST_URL);

      expect(retryAllowed).toBe(true);
      expect(refresh).toHaveBeenCalledTimes(1);
      expect(retry.headers.authorization).toBe('Bearer token-2');
    });

    it('retries a late 401 of an older token with the current token and no refresh', async () => {
      await failWith(await setUp(SEGMENT_URL), 401);
      current = snapshot('alice', 'token-3', 1);

      authenticator.shouldRetry(null, 0, false, { url: SEGMENT_URL, code: 401 }, false);
      const retry = await setUp(SEGMENT_URL);

      expect(refresh).not.toHaveBeenCalled();
      expect(retry.headers.authorization).toBe('Bearer token-3');
    });

    it('allows no second retry and ends the session when the retry gets 401', async () => {
      await failWith(await setUp(SEGMENT_URL), 401);
      authenticator.shouldRetry(null, 0, false, { url: SEGMENT_URL, code: 401 }, false);
      await failWith(await setUp(SEGMENT_URL), 401);

      const secondRetry = authenticator.shouldRetry(null, 1, false, { url: SEGMENT_URL, code: 401 }, false);
      const decision = await authenticator.resolveLoadFailure(SEGMENT_URL, 401);

      expect(secondRetry).toBe(false);
      expect(refresh).toHaveBeenCalledTimes(1);
      expect(decision).toEqual({ kind: 'failed', failure: 'sessionEnded' });
      expect(onAuthenticationFailure).toHaveBeenCalledTimes(1);
      expect(onAuthenticationFailure.mock.calls[0][0]).toMatchObject({ status: 401 });
    });

    it('gives a request a new budget after it succeeded', async () => {
      await failWith(await setUp(SEGMENT_URL), 401);
      authenticator.shouldRetry(null, 0, false, { url: SEGMENT_URL, code: 401 }, false);
      await succeed(await setUp(SEGMENT_URL));
      await failWith(await setUp(SEGMENT_URL), 401);

      const retryAllowed = authenticator.shouldRetry(null, 0, false, { url: SEGMENT_URL, code: 401 }, false);

      expect(retryAllowed).toBe(true);
    });

    it('leaves the hls.js decision unchanged for any status other than 401', async () => {
      await failWith(await setUp(SEGMENT_URL), 503);

      expect(authenticator.shouldRetry(null, 0, false, { url: SEGMENT_URL, code: 503 }, true)).toBe(true);
      expect(authenticator.shouldRetry(null, 0, false, { url: SEGMENT_URL, code: 404 }, false)).toBe(false);
      expect(refresh).not.toHaveBeenCalled();
    });
  });

  describe('refresh failures', () => {
    it('keeps the session when the refresh fails with 503', async () => {
      refresh.mockRejectedValueOnce(new ApiRequestError(503, null));
      await failWith(await setUp(SEGMENT_URL), 401);

      authenticator.shouldRetry(null, 0, false, { url: SEGMENT_URL, code: 401 }, false);
      await expect(setUp(SEGMENT_URL)).rejects.toMatchObject({ status: 503 });
      const decision = await authenticator.resolveLoadFailure(SEGMENT_URL, 0);

      expect(decision).toEqual({ kind: 'failed', failure: 'unavailable' });
      expect(onAuthenticationFailure).not.toHaveBeenCalled();
    });

    it.each([
      ['a network error', new TypeError('Failed to fetch')],
      ['a timeout', new DOMException('Authentication request timed out', 'TimeoutError')]
    ])('keeps the session when the refresh fails by %s', async (_name, refreshFailure) => {
      refresh.mockRejectedValueOnce(refreshFailure);
      await failWith(await setUp(SEGMENT_URL), 401);

      authenticator.shouldRetry(null, 0, false, { url: SEGMENT_URL, code: 401 }, false);
      const decision = await authenticator.resolveLoadFailure(SEGMENT_URL, 0);

      expect(decision).toEqual({ kind: 'failed', failure: 'unavailable' });
      expect(onAuthenticationFailure).not.toHaveBeenCalled();
    });

    it('reports the session as ended when the refresh is rejected with 401', async () => {
      refresh.mockRejectedValueOnce(new ApiRequestError(401, null));
      await failWith(await setUp(SEGMENT_URL), 401);

      authenticator.shouldRetry(null, 0, false, { url: SEGMENT_URL, code: 401 }, false);
      const decision = await authenticator.resolveLoadFailure(SEGMENT_URL, 0);

      expect(decision).toEqual({ kind: 'failed', failure: 'sessionEnded' });
    });

    it('never ends the new session when the account changes during the refresh', async () => {
      refresh.mockImplementationOnce(async () => {
        current = snapshot('bob', 'bob-token', 2);
        throw new ApiRequestError(401, null);
      });
      await failWith(await setUp(SEGMENT_URL), 401);

      authenticator.shouldRetry(null, 0, false, { url: SEGMENT_URL, code: 401 }, false);
      const decision = await authenticator.resolveLoadFailure(SEGMENT_URL, 0);

      expect(decision).toEqual({ kind: 'failed', failure: 'superseded' });
      expect(onAuthenticationFailure).not.toHaveBeenCalled();
      expect(current?.userId).toBe('bob');
    });

    it('reports a superseded identity when the refresh signed in another account', async () => {
      refresh.mockImplementationOnce(async () => {
        current = snapshot('bob', 'bob-token', 2);
        return 'bob-token';
      });
      await failWith(await setUp(SEGMENT_URL), 401);

      authenticator.shouldRetry(null, 0, false, { url: SEGMENT_URL, code: 401 }, false);
      const decision = await authenticator.resolveLoadFailure(SEGMENT_URL, 0);

      expect(decision).toEqual({ kind: 'failed', failure: 'superseded' });
      expect(onAuthenticationFailure).not.toHaveBeenCalled();
    });
  });

  describe('budget shared with the fatal error handler', () => {
    it('recovers a fatal 401 whose budget the load policy did not use, then asks for a restart', async () => {
      await failWith(await setUp(MANIFEST_URL), 401);

      const decision = await authenticator.resolveLoadFailure(MANIFEST_URL, 401);

      expect(decision).toEqual({ kind: 'restartLoading' });
      expect(refresh).toHaveBeenCalledTimes(1);
    });

    it('never recovers twice when the load policy already used the budget', async () => {
      await failWith(await setUp(MANIFEST_URL), 401);
      authenticator.shouldRetry(null, 0, false, { url: MANIFEST_URL, code: 401 }, false);
      await failWith(await setUp(MANIFEST_URL), 401);

      const decision = await authenticator.resolveLoadFailure(MANIFEST_URL, 401);

      expect(decision).toEqual({ kind: 'failed', failure: 'sessionEnded' });
      expect(refresh).toHaveBeenCalledTimes(1);
    });

    it('never recovers twice when the fatal handler used the budget first', async () => {
      await failWith(await setUp(MANIFEST_URL), 401);
      await authenticator.resolveLoadFailure(MANIFEST_URL, 401);
      await failWith(await setUp(MANIFEST_URL), 401);

      const retryAllowed = authenticator.shouldRetry(null, 0, false, { url: MANIFEST_URL, code: 401 }, false);
      const decision = await authenticator.resolveLoadFailure(MANIFEST_URL, 401);

      expect(retryAllowed).toBe(false);
      expect(decision).toEqual({ kind: 'failed', failure: 'sessionEnded' });
      expect(refresh).toHaveBeenCalledTimes(1);
    });

    it('does not treat other fatal statuses as authentication failures', async () => {
      await failWith(await setUp(SEGMENT_URL), 404);

      expect(await authenticator.resolveLoadFailure(SEGMENT_URL, 404)).toEqual({ kind: 'notAuthentication' });
    });

    it('never ends the session for a 401 of a request it did not set up', async () => {
      const decision = await authenticator.resolveLoadFailure(undefined, 401);

      expect(decision).toEqual({ kind: 'failed', failure: 'unavailable' });
      expect(onAuthenticationFailure).not.toHaveBeenCalled();
    });

    it('keeps the session when a newer token exists after the retry got 401', async () => {
      await failWith(await setUp(SEGMENT_URL), 401);
      authenticator.shouldRetry(null, 0, false, { url: SEGMENT_URL, code: 401 }, false);
      await failWith(await setUp(SEGMENT_URL), 401);
      current = snapshot('alice', 'token-9', 1);

      const decision = await authenticator.resolveLoadFailure(SEGMENT_URL, 401);

      expect(decision).toEqual({ kind: 'failed', failure: 'unavailable' });
      expect(onAuthenticationFailure).not.toHaveBeenCalled();
    });
  });

  describe('budget lifetime', () => {
    it('gives a new load a new budget after hls.js aborted the retry, for example after a seek', async () => {
      await failWith(await setUp(SEGMENT_URL), 401);
      authenticator.shouldRetry(null, 0, false, { url: SEGMENT_URL, code: 401 }, false);
      const abortedRetry = await setUp(SEGMENT_URL);
      abortedRetry.fire('abort');

      const newLoad = await setUp(SEGMENT_URL);
      await failWith(newLoad, 401);

      expect(authenticator.isTerminalLoadError(SEGMENT_URL, 401, newLoad)).toBe(false);
      expect(authenticator.shouldRetry(null, 0, false, { url: SEGMENT_URL, code: 401 }, false)).toBe(true);
    });

    it('gives a load long after the recovery a new budget, because its token may have expired', async () => {
      vi.useFakeTimers({ toFake: ['Date'] });
      try {
        await failWith(await setUp(SEGMENT_URL), 401);
        authenticator.shouldRetry(null, 0, false, { url: SEGMENT_URL, code: 401 }, false);
        await setUp(SEGMENT_URL);
        vi.setSystemTime(Date.now() + RETRY_WINDOW_MS + 1);

        const laterLoad = await setUp(SEGMENT_URL);
        await failWith(laterLoad, 401);

        expect(authenticator.isTerminalLoadError(SEGMENT_URL, 401, laterLoad)).toBe(false);
      } finally {
        vi.useRealTimers();
      }
    });
  });

  describe('a failed recovery', () => {
    it('lets a load after the retry window recover again instead of failing at once', async () => {
      vi.useFakeTimers({ toFake: ['Date'] });
      try {
        refresh.mockRejectedValueOnce(new ApiRequestError(503, null));
        await failWith(await setUp(SEGMENT_URL), 401);
        authenticator.shouldRetry(null, 0, false, { url: SEGMENT_URL, code: 401 }, false);
        await expect(setUp(SEGMENT_URL)).rejects.toMatchObject({ status: 503 });
        vi.setSystemTime(Date.now() + RETRY_WINDOW_MS + 1);

        const laterLoad = await setUp(SEGMENT_URL);

        expect(laterLoad.headers.authorization).toBe('Bearer token-1');
        expect(authenticator.isTerminalLoadError(SEGMENT_URL, 0, laterLoad)).toBe(false);
      } finally {
        vi.useRealTimers();
      }
    });
  });

  describe('a retry that hls.js gave up', () => {
    it('ends the budget when hls.js cancelled the retry while it waited for the recovery', async () => {
      await failWith(await setUp(SEGMENT_URL), 401);
      authenticator.shouldRetry(null, 0, false, { url: SEGMENT_URL, code: 401 }, false);
      const abandoned = new FakeXhr();

      const abandonedRequest = abandoned as unknown as XMLHttpRequest;
      const cancelledRetry = authenticator.setUpRequest(abandonedRequest, SEGMENT_URL, ABORTED_LOADER);

      await expect(cancelledRetry).rejects.toThrow('HLS load cancelled');
      expect(abandoned.method).toBe('');
      const newLoad = await setUp(SEGMENT_URL);
      await failWith(newLoad, 401);
      expect(authenticator.isTerminalLoadError(SEGMENT_URL, 401, newLoad)).toBe(false);
    });

    it('does not keep a failed recovery for a later load when the retry was cancelled', async () => {
      refresh.mockRejectedValueOnce(new ApiRequestError(503, null));
      await failWith(await setUp(SEGMENT_URL), 401);
      authenticator.shouldRetry(null, 0, false, { url: SEGMENT_URL, code: 401 }, false);
      const abandoned = new FakeXhr();

      const abandonedRequest = abandoned as unknown as XMLHttpRequest;
      const cancelledRetry = authenticator.setUpRequest(abandonedRequest, SEGMENT_URL, ABORTED_LOADER);

      await expect(cancelledRetry).rejects.toMatchObject({ status: 503 });
      const newLoad = await setUp(SEGMENT_URL);
      expect(newLoad.headers.authorization).toBe('Bearer token-1');
    });
  });

  describe('isAttemptAbandoned', () => {
    it('keeps an attempt that the hls.js loader still owns', () => {
      const xhr = new FakeXhr() as unknown as XMLHttpRequest;

      expect(isAttemptAbandoned({ loader: xhr, stats: { aborted: false } }, xhr)).toBe(false);
    });

    it.each([
      ['the loader aborted it', (xhr: XMLHttpRequest) => ({ loader: xhr, stats: { aborted: true } })],
      ['the loader was destroyed', () => ({ loader: null, stats: null })],
      ['the loader cleared its stats', (xhr: XMLHttpRequest) => ({ loader: xhr, stats: null })],
      ['the loader moved on to another attempt', () => ({ loader: new FakeXhr(), stats: { aborted: false } })]
    ])('treats an attempt as abandoned when %s', (_name, loaderFor) => {
      const xhr = new FakeXhr() as unknown as XMLHttpRequest;

      expect(isAttemptAbandoned(loaderFor(xhr), xhr)).toBe(true);
    });

    it('never treats an attempt as abandoned without a loader', () => {
      const xhr = new FakeXhr() as unknown as XMLHttpRequest;

      expect(isAttemptAbandoned(undefined, xhr)).toBe(false);
    });
  });

  describe('HLS proxy only', () => {
    it.each([
      ['another origin', 'https://media.example.net/hls/video-assets/asset-1/segments/seg_00001.ts'],
      ['another path on this origin', `${window.location.origin}/api/v1/users/me`]
    ])('never sends the token to %s and stops loading', async (_name, url) => {
      const xhr = new FakeXhr();

      await expect(authenticator.setUpRequest(xhr as unknown as XMLHttpRequest, url)).rejects.toThrow();

      expect(xhr.headers.authorization).toBeUndefined();
      expect(authenticator.isTerminalLoadError(url, 0, xhr)).toBe(true);
    });
  });

  describe('load policies', () => {
    it('installs the 401 hook on the error retries and keeps the hls.js retry limits', () => {
      const defaults = Hls.DefaultConfig.fragLoadPolicy.default;

      const policy = authenticator.withAuthenticationRetry(Hls.DefaultConfig.fragLoadPolicy);

      expect(policy.default.errorRetry?.shouldRetry).toBe(authenticator.shouldRetry);
      expect(policy.default.errorRetry?.maxNumRetry).toBe(defaults.errorRetry?.maxNumRetry);
      expect(policy.default.timeoutRetry).toEqual(defaults.timeoutRetry);
    });
  });

  describe('load errors that stop loading at once (ADR-011 addendum 2026-11-22)', () => {
    it('stops at once for a 404', async () => {
      const xhr = await setUp(SEGMENT_URL);
      await failWith(xhr, 404);

      expect(authenticator.isTerminalLoadError(SEGMENT_URL, 404, xhr)).toBe(true);
    });

    it('keeps loading after the first 401, whose retry hls.js has scheduled', async () => {
      const first = await setUp(SEGMENT_URL);
      await failWith(first, 401);
      authenticator.shouldRetry(null, 0, false, { url: SEGMENT_URL, code: 401 }, false);

      expect(authenticator.isTerminalLoadError(SEGMENT_URL, 401, first)).toBe(false);
    });

    it('stops at once for a 401 on the retry, identified by its XMLHttpRequest', async () => {
      await failWith(await setUp(SEGMENT_URL), 401);
      authenticator.shouldRetry(null, 0, false, { url: SEGMENT_URL, code: 401 }, false);
      const retry = await setUp(SEGMENT_URL);
      await failWith(retry, 401);

      expect(authenticator.isTerminalLoadError(SEGMENT_URL, 401, retry)).toBe(true);
    });

    it('stops at once after a failed recovery, whatever status hls.js reports', async () => {
      refresh.mockRejectedValueOnce(new ApiRequestError(503, null));
      await failWith(await setUp(SEGMENT_URL), 401);
      authenticator.shouldRetry(null, 0, false, { url: SEGMENT_URL, code: 401 }, false);
      await expect(setUp(SEGMENT_URL)).rejects.toMatchObject({ status: 503 });

      expect(authenticator.isTerminalLoadError(SEGMENT_URL, 0, new FakeXhr())).toBe(true);
    });

    it('leaves a 503 to the hls.js backoff', async () => {
      const xhr = await setUp(SEGMENT_URL);
      await failWith(xhr, 503);

      expect(authenticator.isTerminalLoadError(SEGMENT_URL, 503, xhr)).toBe(false);
    });

    it('ends the session with the token that the retried attempt sent', async () => {
      await failWith(await setUp(SEGMENT_URL), 401);
      authenticator.shouldRetry(null, 0, false, { url: SEGMENT_URL, code: 401 }, false);
      const retry = await setUp(SEGMENT_URL);
      await failWith(retry, 401);

      const decision = await authenticator.resolveLoadFailure(SEGMENT_URL, 401, retry);

      expect(decision).toEqual({ kind: 'failed', failure: 'sessionEnded' });
      expect(onAuthenticationFailure).toHaveBeenCalledTimes(1);
    });

    it('sends no attempt that waited for a recovery when the player stopped meanwhile', async () => {
      let finishRefresh: () => void = () => undefined;
      refresh.mockImplementationOnce(() => new Promise<string>((resolve) => {
        finishRefresh = () => {
          current = snapshot('alice', 'token-2', 1);
          resolve('token-2');
        };
      }));
      await failWith(await setUp(SEGMENT_URL), 401);
      authenticator.shouldRetry(null, 0, false, { url: SEGMENT_URL, code: 401 }, false);
      const queuedXhr = new FakeXhr();
      const queued = authenticator.setUpRequest(queuedXhr as unknown as XMLHttpRequest, SEGMENT_URL);
      await vi.waitFor(() => expect(refresh).toHaveBeenCalledTimes(1));

      authenticator.dispose();
      finishRefresh();

      await expect(queued).rejects.toThrow('HLS loading stopped');
      expect(queuedXhr.method).toBe('');
    });
  });

  async function setUp(url: string): Promise<FakeXhr> {
    const xhr = new FakeXhr();
    await authenticator.setUpRequest(xhr as unknown as XMLHttpRequest, url);
    return xhr;
  }
});

async function failWith(xhr: FakeXhr, status: number): Promise<void> {
  xhr.status = status;
  xhr.fire('load');
}

async function succeed(xhr: FakeXhr): Promise<void> {
  xhr.status = 200;
  xhr.fire('load');
}

function snapshot(userId: string, accessToken: string, generation: number): AuthenticationSnapshot {
  return { userId, accessToken, generation };
}
