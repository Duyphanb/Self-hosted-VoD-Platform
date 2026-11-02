import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import contract from '../../../../docs/architecture/API-CONTRACT.yaml?raw';
import { ApiClient, ApiRequestError } from '../../lib/api/client';
import { FakeXhr } from '../../test/fakeXhr';
import { decodeVideoAssetStatusResponse, UnexpectedUploadResponseError, uploadMovieVideo } from './adminMoviesApi';
import { VIDEO_ASSET_STATUSES } from './moviesApi';

const movieId = '6f1c0f55-2d4a-4b64-9a43-1d0f3c7b8e21';
const assetId = '3fcf25c2-f096-4462-ae63-4ba4702a9078';
const accepted = { videoAssetId: assetId, status: 'QUEUED' };

describe('admin movie video upload API', () => {
  beforeEach(() => {
    FakeXhr.instances = [];
    vi.stubGlobal('XMLHttpRequest', FakeXhr);
  });
  afterEach(() => { vi.unstubAllGlobals(); });

  it('posts exactly one file to the canonical lowercase upload route and keeps the original name and MIME', async () => {
    const file = new File(['synthetic'], 'Original.MOV', { type: 'video/mp4' });
    const pending = uploadMovieVideo(client(), movieId.toUpperCase(), file, 'video/quicktime');
    const xhr = FakeXhr.instances[0];
    expect(xhr.url).toBe(`/api/v1/admin/movies/${movieId}/video`);
    expect(xhr.method).toBe('POST');
    const body = xhr.body as FormData;
    expect([...body.keys()]).toEqual(['file']);
    const uploaded = body.get('file') as File;
    expect(uploaded.name).toBe('Original.MOV');
    expect(uploaded.type).toBe('video/mp4');
    xhr.respond(202, JSON.stringify(accepted));
    await expect(pending).resolves.toEqual({ response: accepted });
  });

  it.each([
    ['film.mp4', 'video/mp4'], ['film.mov', 'video/quicktime'],
    ['film.mkv', 'video/x-matroska'], ['film.webm', 'video/webm']
  ])('fills an empty MIME declaration for %s while preserving its name', async (name, declaredType) => {
    const file = new File(['synthetic'], name);
    const pending = uploadMovieVideo(client(), movieId, file, declaredType);
    const xhr = FakeXhr.instances[0];
    const uploaded = (xhr.body as FormData).get('file') as File;
    expect(uploaded.name).toBe(name);
    expect(uploaded.type).toBe(declaredType);
    expect(file.type).toBe('');
    xhr.respond(202, JSON.stringify(accepted));
    await pending;
  });

  it('accepts 202 without optional message and preserves a valid optional message', async () => {
    const first = uploadMovieVideo(client(), movieId, file(), 'video/mp4');
    FakeXhr.instances[0].respond(202, JSON.stringify(accepted));
    await expect(first).resolves.toEqual({ response: accepted });
    const second = uploadMovieVideo(client(), movieId, file(), 'video/mp4');
    FakeXhr.instances[1].respond(202, JSON.stringify({ ...accepted, message: 'Video queued for encoding' }));
    await expect(second).resolves.toEqual({ response: { ...accepted, message: 'Video queued for encoding' } });
  });

  it.each(invalidResponses())('keeps 202 accepted when its body is undecodable: %s', async (_label, payload) => {
    const pending = uploadMovieVideo(client(), movieId, file(), 'video/mp4');
    FakeXhr.instances[0].respond(202, typeof payload === 'string' ? payload : JSON.stringify(payload));
    await expect(pending).resolves.toEqual({ response: null });
  });

  it.each([200, 201, 204])('does not mistake unexpected %s for committed acceptance', async (status) => {
    const pending = uploadMovieVideo(client(), movieId, file(), 'video/mp4');
    FakeXhr.instances[0].respond(status, JSON.stringify(accepted));
    await expect(pending).rejects.toBeInstanceOf(UnexpectedUploadResponseError);
  });

  it('rejects invalid movie identities before creating an upload transport', async () => {
    await expect(uploadMovieVideo(client(), '../movies', file(), 'video/mp4')).rejects.toThrow('valid movie ID');
    expect(FakeXhr.instances.length).toBe(0);
  });

  it('preserves an ApiError for outcome classification without replaying', async () => {
    const pending = uploadMovieVideo(client(), movieId, file(), 'video/mp4');
    FakeXhr.instances[0].respond(409, JSON.stringify({ timestamp: '2026-11-02T00:00:00Z', status: 409,
      code: 'VIDEO_UPLOAD_IN_PROGRESS', message: 'Another upload is in progress', requestId: 'request-1' }));
    await expect(pending).rejects.toBeInstanceOf(ApiRequestError);
    expect(FakeXhr.instances.length).toBe(1);
  });

  it.each(VIDEO_ASSET_STATUSES)('decodes the contract video-asset status %s', (status) => {
    expect(decodeVideoAssetStatusResponse({ ...accepted, status })).toEqual({ ...accepted, status });
  });

  it('safely represents a future status rather than rendering an unsupported enum value', () => {
    expect(decodeVideoAssetStatusResponse({ ...accepted, status: 'FUTURE_STATUS' }))
      .toEqual({ ...accepted, status: 'UNKNOWN' });
  });

  it('pins every required response field from the contract and rejects its missing or wrongly typed variants', () => {
    expect(requiredFields()).toEqual(['videoAssetId', 'status']);
    for (const field of requiredFields()) {
      const { [field]: _omitted, ...without } = accepted as Record<string, unknown>;
      expect(decodeVideoAssetStatusResponse(without)).toBeNull();
      for (const wrong of [null, 1, false, [], {}]) {
        expect(decodeVideoAssetStatusResponse({ ...accepted, [field]: wrong })).toBeNull();
      }
    }
  });
});

function client(): ApiClient {
  return new ApiClient('/api/v1', {
    getAuthenticationSnapshot: () => ({ userId: 'admin', accessToken: 'synthetic-access', generation: 0 }),
    refreshAccessToken: vi.fn(), onAuthenticationFailure: vi.fn()
  });
}

function file(): File { return new File(['synthetic'], 'film.mp4', { type: 'video/mp4' }); }

function requiredFields(): string[] {
  const match = /\n {4}VideoAssetStatusResponse:\n {6}type: object\n {6}required: \[([^\]]*)\]/
    .exec(contract.replace(/\r\n/g, '\n'));
  if (!match) throw new Error('VideoAssetStatusResponse contract required list is missing');
  return match[1].split(',').map((field) => field.trim());
}

function invalidResponses(): [string, unknown][] {
  const rows: [string, unknown][] = [['non-JSON body', 'not JSON'], ['empty body', ''], ['null', null], ['array', []],
    ['optional message wrong type', { ...accepted, message: null }]];
  for (const id of ['invalid', assetId.toUpperCase(), `${assetId}/extra`, '']) {
    rows.push(['non-canonical asset id', { ...accepted, videoAssetId: id }]);
  }
  for (const field of requiredFields()) {
    const { [field]: _omitted, ...without } = accepted as Record<string, unknown>;
    rows.push([`missing ${field}`, without]);
    rows.push([`wrong type ${field}`, { ...accepted, [field]: 42 }]);
  }
  return rows;
}
