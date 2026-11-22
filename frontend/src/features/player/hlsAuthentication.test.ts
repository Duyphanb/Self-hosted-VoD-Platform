import type { LoaderCallbacks, LoaderConfiguration, LoaderContext } from 'hls.js';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ApiClient,
  ApiRequestError,
  type AuthenticationSnapshot,
  type AuthRequestController
} from '../../lib/api/client';
import { FakeLoaderXhr } from '../../test/fakeLoaderXhr';
import { AuthenticatedHlsLoader, HlsAuthentication } from './hlsAuthentication';

const MANIFEST_URL = `${window.location.origin}/hls/video-assets/asset-1/master.m3u8`;
const SEGMENT_URL = `${window.location.origin}/hls/video-assets/asset-1/segments/seg_00001.ts`;

/** The real hls.js 1.7.4 XHR loader underneath, driven through a controllable XMLHttpRequest. */
describe('AuthenticatedHlsLoader', () => {
  let current: AuthenticationSnapshot | null;
  let refresh: ReturnType<typeof vi.fn<() => Promise<string>>>;
  let onAuthenticationFailure: ReturnType<typeof vi.fn<(error: unknown) => void>>;
  let authentication: HlsAuthentication;

  beforeEach(() => {
    FakeLoaderXhr.reset();
    vi.stubGlobal('XMLHttpRequest', FakeLoaderXhr);
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
    authentication = new HlsAuthentication(new ApiClient('/api/v1', controller), snapshot('alice', 'token-1', 1));
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  describe('each attempt', () => {
    it('sends the current token in the Authorization header and never in the URL or a cookie', async () => {
      current = snapshot('alice', 'token-7', 1);

      const load = startLoad(SEGMENT_URL);
      await settle();

      const request = FakeLoaderXhr.latestSent();
      expect(request.method).toBe('GET');
      expect(request.url).toBe(SEGMENT_URL);
      expect(request.headers.authorization).toBe('Bearer token-7');
      expect(request.withCredentials).toBe(false);
      request.respond(200, 'segment');
      expect(load.callbacks.onSuccess).toHaveBeenCalledTimes(1);
    });

    it.each([
      ['another origin', 'https://media.example.net/hls/video-assets/asset-1/segments/seg_00001.ts'],
      ['another path on this origin', `${window.location.origin}/api/v1/users/me`]
    ])('never sends the token to %s and ends the load', async (_name, url) => {
      const load = startLoad(url);
      await settle();

      expect(FakeLoaderXhr.sentRequests()).toEqual([]);
      expect(load.callbacks.onError).toHaveBeenCalledTimes(1);
      expect(authentication.failureFor(load.lastError()?.networkDetails)).toBe('unavailable');
    });
  });

  describe('one recovery and one resend per load (ADR-011 D2)', () => {
    it('recovers a 401 through the shared refresh and resends once with the new token', async () => {
      const load = startLoad(MANIFEST_URL);
      await settle();

      FakeLoaderXhr.latestSent().respond(401);
      await settle();
      const resend = FakeLoaderXhr.latestSent();
      resend.respond(200, '#EXTM3U');

      expect(refresh).toHaveBeenCalledTimes(1);
      expect(FakeLoaderXhr.sentRequests()).toHaveLength(2);
      expect(resend.headers.authorization).toBe('Bearer token-2');
      expect(load.callbacks.onSuccess).toHaveBeenCalledTimes(1);
      expect(load.callbacks.onError).not.toHaveBeenCalled();
    });

    it('resends a late 401 of an older token with the current token and no refresh', async () => {
      startLoad(SEGMENT_URL);
      await settle();
      current = snapshot('alice', 'token-3', 1);

      FakeLoaderXhr.latestSent().respond(401);
      await settle();

      expect(refresh).not.toHaveBeenCalled();
      expect(FakeLoaderXhr.latestSent().headers.authorization).toBe('Bearer token-3');
    });

    it('reports a 401 on the resend once and ends the session, after exactly two sends', async () => {
      const load = startLoad(SEGMENT_URL);
      await settle();
      FakeLoaderXhr.latestSent().respond(401);
      await settle();

      FakeLoaderXhr.latestSent().respond(401);
      await settle();

      expect(FakeLoaderXhr.sentRequests()).toHaveLength(2);
      expect(refresh).toHaveBeenCalledTimes(1);
      expect(load.callbacks.onError).toHaveBeenCalledTimes(1);
      expect(load.lastError()?.error.code).toBe(401);
      expect(authentication.failureFor(load.lastError()?.networkDetails)).toBe('sessionEnded');
      expect(onAuthenticationFailure).toHaveBeenCalledTimes(1);
      expect(onAuthenticationFailure.mock.calls[0][0]).toMatchObject({ status: 401 });
    });

    it('keeps the session when a newer token replaced the one the resend sent', async () => {
      const load = startLoad(SEGMENT_URL);
      await settle();
      FakeLoaderXhr.latestSent().respond(401);
      await settle();
      current = snapshot('alice', 'token-9', 1);

      FakeLoaderXhr.latestSent().respond(401);
      await settle();

      expect(authentication.failureFor(load.lastError()?.networkDetails)).toBe('unavailable');
      expect(onAuthenticationFailure).not.toHaveBeenCalled();
    });

    it('keeps the one budget across a transport timeout retry of the same load', async () => {
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
      const load = startLoad(MANIFEST_URL, { timeoutRetries: 1 });
      await settle();
      FakeLoaderXhr.latestSent().respond(401);
      await settle();

      // The resend gets no answer, so the loader's own timeout retries it within this load.
      vi.advanceTimersByTime(TIME_TO_FIRST_BYTE_MS + 1);
      await settle();
      FakeLoaderXhr.latestSent().respond(401);
      await settle();

      expect(FakeLoaderXhr.sentRequests()).toHaveLength(3);
      expect(refresh).toHaveBeenCalledTimes(1);
      expect(authentication.failureFor(load.lastError()?.networkDetails)).toBe('sessionEnded');
    });

    it('gives a new load of the same URL its own budget', async () => {
      startLoad(SEGMENT_URL);
      await settle();
      FakeLoaderXhr.latestSent().respond(401);
      await settle();
      FakeLoaderXhr.latestSent().respond(200, 'segment');

      const laterLoad = startLoad(SEGMENT_URL);
      await settle();
      FakeLoaderXhr.latestSent().respond(401);
      await settle();

      expect(refresh).toHaveBeenCalledTimes(2);
      expect(laterLoad.callbacks.onError).not.toHaveBeenCalled();
    });

    it('reports a 404 at once, without a recovery', async () => {
      const load = startLoad(SEGMENT_URL);
      await settle();

      FakeLoaderXhr.latestSent().respond(404);
      await settle();

      expect(refresh).not.toHaveBeenCalled();
      expect(load.lastError()?.error.code).toBe(404);
      expect(authentication.failureFor(load.lastError()?.networkDetails)).toBeNull();
    });
  });

  describe('refresh failures', () => {
    it.each([
      ['503', new ApiRequestError(503, null)],
      ['a network error', new TypeError('Failed to fetch')],
      ['a timeout', new DOMException('Authentication request timed out', 'TimeoutError')]
    ])('keeps the session and sends no resend when the refresh fails with %s', async (_name, refreshFailure) => {
      refresh.mockRejectedValueOnce(refreshFailure);
      const load = startLoad(SEGMENT_URL);
      await settle();

      FakeLoaderXhr.latestSent().respond(401);
      await settle();

      expect(FakeLoaderXhr.sentRequests()).toHaveLength(1);
      expect(load.callbacks.onError).toHaveBeenCalledTimes(1);
      expect(authentication.failureFor(load.lastError()?.networkDetails)).toBe('unavailable');
      expect(onAuthenticationFailure).not.toHaveBeenCalled();
    });

    it('reports the session as ended when the refresh is rejected with 401', async () => {
      refresh.mockRejectedValueOnce(new ApiRequestError(401, null));
      const load = startLoad(SEGMENT_URL);
      await settle();

      FakeLoaderXhr.latestSent().respond(401);
      await settle();

      expect(authentication.failureFor(load.lastError()?.networkDetails)).toBe('sessionEnded');
    });

    it('never ends the new session when another account signs in during the refresh', async () => {
      refresh.mockImplementationOnce(async () => {
        current = snapshot('bob', 'bob-token', 2);
        throw new ApiRequestError(401, null);
      });
      const load = startLoad(SEGMENT_URL);
      await settle();

      FakeLoaderXhr.latestSent().respond(401);
      await settle();

      expect(authentication.failureFor(load.lastError()?.networkDetails)).toBe('superseded');
      expect(onAuthenticationFailure).not.toHaveBeenCalled();
      expect(current?.userId).toBe('bob');
    });
  });

  describe('abort and destroy', () => {
    it('sends no resend and reports no error when hls.js aborts the load during the recovery', async () => {
      const finishRefresh = holdRefresh();
      const load = startLoad(SEGMENT_URL);
      await settle();
      FakeLoaderXhr.latestSent().respond(401);
      await settle();

      load.loader.abort();
      finishRefresh();
      await settle();

      expect(FakeLoaderXhr.sentRequests()).toHaveLength(1);
      expect(requestsCarryingToken()).toHaveLength(1);
      expect(load.callbacks.onAbort).toHaveBeenCalledTimes(1);
      expect(load.callbacks.onError).not.toHaveBeenCalled();
    });

    it('sends nothing more and calls nothing after hls.js destroys the load during the recovery', async () => {
      const finishRefresh = holdRefresh();
      const load = startLoad(SEGMENT_URL);
      await settle();
      FakeLoaderXhr.latestSent().respond(401);
      await settle();

      load.loader.destroy();
      finishRefresh();
      await settle();

      expect(FakeLoaderXhr.sentRequests()).toHaveLength(1);
      expect(requestsCarryingToken()).toHaveLength(1);
      expect(load.callbacks.onError).not.toHaveBeenCalled();
      expect(load.callbacks.onSuccess).not.toHaveBeenCalled();
    });

    it('reports nothing when hls.js aborts the load while a failed recovery rejects its setup', async () => {
      const failRefresh = holdFailingRefresh();
      const load = startLoad(MANIFEST_URL);
      await settle();
      FakeLoaderXhr.latestSent().respond(401);
      await settle();
      const resend = FakeLoaderXhr.instances.at(-1)!;

      failRefresh(new ApiRequestError(503, null));
      // hls.js opens the resend for its second xhrSetup call after the first one rejected; abort then.
      await stepUntil(() => resend.method === 'GET');
      load.loader.abort();
      await settle();

      expect(load.callbacks.onAbort).toHaveBeenCalledTimes(1);
      expect(load.callbacks.onError).not.toHaveBeenCalled();
    });

    it('sends nothing for a run that stopped', async () => {
      authentication.dispose();

      const load = startLoad(SEGMENT_URL);
      await settle();

      expect(FakeLoaderXhr.sentRequests()).toEqual([]);
      expect(authentication.failureFor(load.lastError()?.networkDetails)).toBeNull();
    });
  });

  interface StartedLoad {
    loader: AuthenticatedHlsLoader;
    callbacks: {
      onSuccess: ReturnType<typeof vi.fn>;
      onError: ReturnType<typeof vi.fn>;
      onTimeout: ReturnType<typeof vi.fn>;
      onAbort: ReturnType<typeof vi.fn>;
    };
    lastError(): { error: { code: number }; networkDetails: unknown } | undefined;
  }

  function startLoad(url: string, { timeoutRetries = 0 } = {}): StartedLoad {
    const loader = new AuthenticatedHlsLoader({} as never, authentication);
    const callbacks = {
      onSuccess: vi.fn(),
      onError: vi.fn(),
      onTimeout: vi.fn(),
      onAbort: vi.fn()
    };
    const context = { url, responseType: 'text', type: 'manifest' } as LoaderContext;
    loader.load(context, loaderConfiguration(timeoutRetries), callbacks as unknown as LoaderCallbacks<LoaderContext>);
    return {
      loader,
      callbacks,
      lastError: () => {
        const call = callbacks.onError.mock.calls.at(-1);
        return call ? { error: call[0], networkDetails: call[2] } : undefined;
      }
    };
  }

  /** Hold the next refresh open; the returned function rejects it with `failure`. */
  function holdFailingRefresh(): (failure: unknown) => void {
    let fail: (failure: unknown) => void = () => undefined;
    refresh.mockImplementationOnce(() => new Promise<string>((_resolve, reject) => {
      fail = reject;
    }));
    return (failure) => fail(failure);
  }

  /** Hold the next refresh open; the returned function completes it with token-2. */
  function holdRefresh(): () => void {
    let finish: () => void = () => undefined;
    refresh.mockImplementationOnce(() => new Promise<string>((resolve) => {
      finish = () => {
        current = snapshot('alice', 'token-2', 1);
        resolve('token-2');
      };
    }));
    return () => finish();
  }
});

describe('HlsAuthentication', () => {
  it('knows no failure for a request it never ended', () => {
    const authentication = new HlsAuthentication({} as ApiClient, snapshot('alice', 'token-1', 1));

    expect(authentication.failureFor(new FakeLoaderXhr())).toBeNull();
    expect(authentication.failureFor(null)).toBeNull();
  });
});

const TIME_TO_FIRST_BYTE_MS = 10_000;

function loaderConfiguration(timeoutRetries: number): LoaderConfiguration {
  const timeoutRetry = timeoutRetries > 0
    ? { maxNumRetry: timeoutRetries, retryDelayMs: 0, maxRetryDelayMs: 0 }
    : null;
  return {
    loadPolicy: {
      maxTimeToFirstByteMs: TIME_TO_FIRST_BYTE_MS,
      maxLoadTimeMs: 20_000,
      timeoutRetry,
      errorRetry: null
    },
    timeout: 20_000,
    maxRetry: 0,
    retryDelay: 0,
    maxRetryDelay: 0
  };
}

/** Every XMLHttpRequest that received the bearer token, sent or not: an abandoned load gets none. */
function requestsCarryingToken(): FakeLoaderXhr[] {
  return FakeLoaderXhr.instances.filter((xhr) => xhr.headers.authorization !== undefined);
}

function snapshot(userId: string, accessToken: string, generation: number): AuthenticationSnapshot {
  return { userId, accessToken, generation };
}

/** Run microtasks one at a time until `condition` holds, to act between two steps of the loader. */
async function stepUntil(condition: () => boolean): Promise<void> {
  for (let step = 0; step < 50; step += 1) {
    if (condition()) {
      return;
    }
    await Promise.resolve();
  }
  throw new Error('The condition never held');
}

/** Let the loader's promise chain and the shared recovery run to completion. */
async function settle(): Promise<void> {
  for (let step = 0; step < 30; step += 1) {
    await Promise.resolve();
  }
}
