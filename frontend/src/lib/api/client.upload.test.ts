import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeXhr } from '../../test/fakeXhr';
import {
  ApiClient, ApiRequestError, AuthenticationSupersededError, UploadAuthenticationSupersededError,
  UploadTransportError, type AuthenticationSnapshot
} from './client';

const authentication: AuthenticationSnapshot = { userId: 'user-1', accessToken: 'synthetic-access', generation: 0 };

describe('ApiClient upload', () => {
  beforeEach(() => {
    FakeXhr.instances = [];
    vi.stubGlobal('XMLHttpRequest', FakeXhr);
  });
  afterEach(() => { vi.unstubAllGlobals(); });

  it('sends once to the shared URL with authentication and upload listeners, leaving the boundary to the browser', async () => {
    const { client } = setup();
    const body = new FormData();
    const pending = client.upload('/admin/movies/id/video', body);
    const xhr = onlyXhr();
    expect(xhr.url).toBe('/api/v1/admin/movies/id/video');
    expect(xhr.method).toBe('POST');
    expect(xhr.headers.authorization === `Bearer ${authentication.accessToken}`).toBe(true);
    expect(Object.keys(xhr.headers)).toEqual(['authorization']);
    expect(xhr.body === body).toBe(true);
    expect(xhr.uploadListenersAtSend).toBe(true);
    expect(xhr.timeout).toBe(0);
    expect(xhr.withCredentials).toBe(false);
    xhr.respond(202, '{"status":"QUEUED"}');
    await expect(pending).resolves.toEqual({ status: 202, payload: { status: 'QUEUED' } });
    expect(xhr.sendCount).toBe(1);
    expect(xhr.hasListeners()).toBe(false);
    expect(xhr.upload.hasListeners()).toBe(false);
  });

  it.each([202, 200, 204])('preserves successful HTTP status %s and undecodable payloads', async (status) => {
    const pending = setup().client.upload('/upload', new FormData());
    onlyXhr().respond(status, status === 204 ? '' : 'not JSON');
    await expect(pending).resolves.toEqual({ status, payload: status === 204 ? null : 'not JSON' });
  });

  it('marks body sent once on complete progress even before a delayed upload load event', async () => {
    const onBodySent = vi.fn();
    const onProgress = vi.fn();
    const pending = setup().client.upload('/upload', new FormData(), { onBodySent, onProgress });
    const xhr = onlyXhr();
    xhr.progress(500, 1000);
    expect(onBodySent).not.toHaveBeenCalled();
    xhr.finishBody(1000, { load: false });
    expect(onBodySent).toHaveBeenCalledTimes(1);
    xhr.uploadLoad(1000);
    xhr.respond(202);
    await pending;
    expect(onBodySent).toHaveBeenCalledTimes(1);
    expect(onProgress.mock.calls).toEqual([[500, 1000], [1000, 1000]]);
  });

  it('uses upload load when progress length is unknown and never uses upload loadend', async () => {
    const onBodySent = vi.fn();
    const onProgress = vi.fn();
    const pending = setup().client.upload('/upload', new FormData(), { onBodySent, onProgress });
    const xhr = onlyXhr();
    xhr.progress(100, 0, false);
    xhr.upload.fire('loadend');
    expect(onProgress).toHaveBeenCalledWith(100, null);
    expect(onBodySent).not.toHaveBeenCalled();
    xhr.upload.fire('load', { loaded: 100, lengthComputable: false });
    xhr.respond(202);
    await pending;
    expect(onBodySent).toHaveBeenCalledTimes(1);
  });

  it('records completed bytes before a progress callback can abort at 100 percent', async () => {
    const controller = new AbortController();
    const pending = setup().client.upload('/upload', new FormData(), {
      signal: controller.signal,
      onProgress: () => controller.abort()
    });
    onlyXhr().finishBody(100);
    await expect(pending).rejects.toMatchObject({ kind: 'abort', bodySent: true });
  });

  it.each([400, 401, 403, 409, 413, 500, 503])('preserves HTTP %s ApiError without refresh, replay or auth-failure callbacks', async (status) => {
    const { client, refreshAccessToken, onAuthenticationFailure } = setup();
    const pending = client.upload('/upload', new FormData());
    const xhr = onlyXhr();
    xhr.respond(status, JSON.stringify(apiError(status)));
    const error: unknown = await pending.catch((failure: unknown) => failure);
    expect(error).toBeInstanceOf(ApiRequestError);
    expect((error as ApiRequestError).status).toBe(status);
    expect((error as ApiRequestError).apiError?.requestId).toBe('request-1');
    expect(xhr.sendCount).toBe(1);
    expect(FakeXhr.instances.length).toBe(1);
    expect(refreshAccessToken).not.toHaveBeenCalled();
    expect(onAuthenticationFailure).not.toHaveBeenCalled();
  });

  it('allows a non-ApiError rejection body without inventing request details', async () => {
    const pending = setup().client.upload('/upload', new FormData());
    onlyXhr().respond(400, '<html>bad request</html>');
    await expect(pending).rejects.toMatchObject({ status: 400, apiError: null });
  });

  it.each(['error', 'timeout', 'abort'] as const)('preserves before/after-body completion on %s and ignores upload loadend', async (kind) => {
    for (const bodySent of [false, true]) {
      const pending = setup().client.upload('/upload', new FormData());
      const xhr = FakeXhr.instances[FakeXhr.instances.length - 1];
      if (bodySent) xhr.finishBody(100);
      xhr.fail(kind);
      await expect(pending).rejects.toMatchObject({
        name: 'UploadTransportError', kind: kind === 'error' ? 'network' : kind, bodySent
      });
    }
  });

  it('maps status-zero load to a transport failure', async () => {
    const pending = setup().client.upload('/upload', new FormData());
    onlyXhr().respond(0);
    await expect(pending).rejects.toBeInstanceOf(UploadTransportError);
  });

  it('does not create an XHR without a current authentication snapshot or for a pre-aborted signal', async () => {
    await expect(setup(null).client.upload('/upload', new FormData())).rejects.toBeInstanceOf(AuthenticationSupersededError);
    await expect(setup({ ...authentication, accessToken: '' }).client.upload('/upload', new FormData()))
      .rejects.toBeInstanceOf(AuthenticationSupersededError);
    const controller = new AbortController();
    controller.abort();
    await expect(setup().client.upload('/upload', new FormData(), { signal: controller.signal }))
      .rejects.toMatchObject({ kind: 'abort', bodySent: false });
    expect(FakeXhr.instances.length).toBe(0);
  });

  it('forwards cancellation once and removes the signal listener when settled', async () => {
    const controller = new AbortController();
    const pending = setup().client.upload('/upload', new FormData(), { signal: controller.signal });
    const xhr = onlyXhr();
    xhr.respond(202);
    await pending;
    controller.abort();
    expect(xhr.abortCount).toBe(0);
  });

  it('uses the body fact when abort settlement races upload completion', async () => {
    const controller = new AbortController();
    const pending = setup().client.upload('/upload', new FormData(), { signal: controller.signal });
    const xhr = onlyXhr();
    xhr.deferAbort = true;
    controller.abort();
    xhr.finishBody(100);
    xhr.fail('abort');
    await expect(pending).rejects.toMatchObject({ kind: 'abort', bodySent: true });
    expect(xhr.abortCount).toBe(1);
  });

  it.each([false, true])('ends superseded authentication with bodySent=%s instead of returning another session response', async (bodySent) => {
    const { client, state, refreshAccessToken, onAuthenticationFailure } = setup();
    const pending = client.upload('/upload', new FormData());
    const xhr = onlyXhr();
    if (bodySent) xhr.finishBody(100);
    state.current = { ...authentication, generation: 1 };
    xhr.respond(202, '{}');
    const error: unknown = await pending.catch((failure: unknown) => failure);
    expect(error).toBeInstanceOf(AuthenticationSupersededError);
    expect(error).toBeInstanceOf(UploadAuthenticationSupersededError);
    expect((error as UploadAuthenticationSupersededError).bodySent).toBe(bodySent);
    expect(refreshAccessToken).not.toHaveBeenCalled();
    expect(onAuthenticationFailure).not.toHaveBeenCalled();
  });
});

function setup(current: AuthenticationSnapshot | null = authentication) {
  const state = { current };
  const refreshAccessToken = vi.fn();
  const onAuthenticationFailure = vi.fn();
  return { state, refreshAccessToken, onAuthenticationFailure,
    client: new ApiClient('/api/v1/', {
      getAuthenticationSnapshot: () => state.current, refreshAccessToken, onAuthenticationFailure
    }) };
}

function onlyXhr(): FakeXhr {
  expect(FakeXhr.instances.length).toBe(1);
  return FakeXhr.instances[0];
}

function apiError(status: number) {
  return { timestamp: '2026-11-02T00:00:00Z', status, code: 'FAILURE', message: 'The upload failed.', requestId: 'request-1' };
}
