import { describe, expect, it } from 'vitest';
import {
  ApiRequestError, AuthenticationSupersededError, UploadAuthenticationSupersededError,
  UploadTransportError
} from '../../lib/api/client';
import { MAX_UPLOAD_SIZE_BYTES } from './uploadLimits';
import { checkVideoFile, classifyUploadFailure, uploadPercent, VIDEO_FILE_ACCEPT, VIDEO_MIME_BY_EXTENSION } from './videoUpload';

function file(name = 'movie.mp4', type = 'video/mp4', size = 8): File {
  const selected = new File(['contents'], name, { type });
  Object.defineProperty(selected, 'size', { value: size });
  return selected;
}

function apiFailure(status: number, code: string, overrides: Record<string, unknown> = {}): ApiRequestError {
  return new ApiRequestError(status, {
    timestamp: '2026-11-02T00:00:00Z', status, code, message: 'Safe server detail', requestId: 'request-175', ...overrides
  });
}

describe('upload file pre-checks', () => {
  it.each([
    ['mp4', 'video/mp4'], ['mov', 'video/quicktime'], ['mkv', 'video/x-matroska'], ['webm', 'video/webm']
  ])('pins the %s extension/MIME and fills only an empty type', (extension, mime) => {
    expect(VIDEO_MIME_BY_EXTENSION[extension as keyof typeof VIDEO_MIME_BY_EXTENSION]).toBe(mime);
    const original = file(`Movie.Final.${extension.toUpperCase()}`, '');
    expect(checkVideoFile(original)).toEqual({ valid: true, declaredType: mime });
    expect(original.type).toBe('');
    expect(VIDEO_FILE_ACCEPT.split(',')).toContain(`.${extension}`);
  });

  it('keeps the allowlist to four extensions and accepts an allowlisted MIME mismatch without rewriting it', () => {
    expect(Object.keys(VIDEO_MIME_BY_EXTENSION)).toEqual(['mp4', 'mov', 'mkv', 'webm']);
    expect(checkVideoFile(file('movie.mp4', 'video/webm'))).toEqual({ valid: true, declaredType: 'video/webm' });
    expect(checkVideoFile(file('movie.mkv', 'video/quicktime'))).toEqual({ valid: true, declaredType: 'video/quicktime' });
  });

  it.each(['movie', 'mp4', 'WEBM', 'movie.avi', 'movie.mp4.exe', 'movie.', 'movie.__proto__'])('rejects a missing/unsupported extension: %s', (name) => {
    expect(checkVideoFile(file(name))).toMatchObject({ valid: false });
  });

  it.each(['application/octet-stream', 'text/plain', 'video/avi', 'video/mp4; codecs=avc1'])('rejects a non-empty unsupported MIME instead of filling %s', (type) => {
    expect(checkVideoFile(file('movie.mp4', type))).toMatchObject({ valid: false });
  });

  it('rejects empty and oversized files but permits the exact 2048 MiB boundary without allocating the body', () => {
    expect(checkVideoFile(file('empty.mp4', 'video/mp4', 0))).toEqual({ valid: false, message: 'The selected file is empty.' });
    expect(checkVideoFile(file('large.mp4', 'video/mp4', MAX_UPLOAD_SIZE_BYTES + 1))).toMatchObject({ valid: false, message: expect.stringContaining('2048 MiB') });
    expect(checkVideoFile(file('boundary.mp4', 'video/mp4', MAX_UPLOAD_SIZE_BYTES))).toEqual({ valid: true, declaredType: 'video/mp4' });
  });
});

describe('upload transfer percent', () => {
  it.each([[0, 100, 0], [1, 3, 33], [99, 100, 99], [100, 100, 99], [101, 100, 99], [-1, 100, 0]])(
    'floors and bounds sending progress %s/%s to %s', (loaded, total, expected) => expect(uploadPercent(loaded, total)).toBe(expected)
  );
  it.each([null, 0, -1, Number.NaN, Number.POSITIVE_INFINITY])('keeps indeterminate total %s indeterminate', (total) => {
    expect(uploadPercent(12, total)).toBeNull();
  });
  it('does not expose an invalid loaded counter', () => expect(uploadPercent(Number.NaN, 100)).toBeNull());
});

describe('upload failure classification', () => {
  it.each([[503, 'UPLOAD_STORAGE_UNAVAILABLE'], [500, 'UPLOAD_DATABASE_ERROR']])('permits manual retry only for exact %s/%s', (status, code) => {
    expect(classifyUploadFailure(apiFailure(status as number, code as string))).toMatchObject({
      kind: 'notAccepted', message: expect.stringContaining('not accepted (no committed asset/job pair)'), requestId: 'request-175', serverMessage: 'Safe server detail'
    });
  });

  it.each([
    [500, 'UPLOAD_OUTCOME_UNKNOWN'], [500, 'INTERNAL_ERROR'], [500, 'SOMETHING_ELSE'],
    [502, 'UPSTREAM_UNAVAILABLE'], [503, 'UPSTREAM_UNAVAILABLE'], [504, 'UPSTREAM_TIMEOUT'],
    [503, 'UPLOAD_DATABASE_ERROR'], [500, 'UPLOAD_STORAGE_UNAVAILABLE']
  ])('treats %s/%s as unknown irrespective of transfer phase', (status, code) => {
    expect(classifyUploadFailure(apiFailure(status as number, code as string))).toMatchObject({ kind: 'unknown', message: 'Upload result unknown' });
  });

  it('does not trust malformed, non-ApiError or inconsistent-status 5xx bodies for the not-accepted exception', () => {
    for (const error of [new ApiRequestError(503, 'unavailable'), apiFailure(503, 'UPLOAD_STORAGE_UNAVAILABLE', { timestamp: null }),
      apiFailure(503, 'UPLOAD_STORAGE_UNAVAILABLE', { status: 500 }), new ApiRequestError(500, null)]) {
      expect(classifyUploadFailure(error).kind).toBe('unknown');
    }
  });

  it.each([
    [400, 'UNSUPPORTED_VIDEO_TYPE', 'rejected', 'content does not match'],
    [400, 'VALIDATION_ERROR', 'rejected', 'server rejected'],
    [401, 'UNAUTHORIZED', 'sessionExpired', 'session expired'], [403, 'FORBIDDEN', 'forbidden', 'permission'],
    [404, 'MOVIE_NOT_FOUND', 'notFound', 'Movie not found'],
    [409, 'VIDEO_UPLOAD_IN_PROGRESS', 'inProgress', 'already in progress'],
    [413, 'PAYLOAD_TOO_LARGE', 'tooLarge', '2048 MiB'], [422, 'OTHER', 'rejected', 'server rejected']
  ])('maps HTTP %s/%s to %s', (status, code, kind, wording) => {
    expect(classifyUploadFailure(apiFailure(status as number, code as string))).toMatchObject({ kind, message: expect.stringContaining(wording as string) });
  });

  it('renders container non-ApiError 400 safely without a request ID or raw body', () => {
    expect(classifyUploadFailure(new ApiRequestError(400, '<html>container rejection</html>'))).toEqual({
      kind: 'rejected', message: 'The server rejected the upload request. Check the selected file.', requestId: null, serverMessage: null
    });
  });

  it.each(['network', 'timeout'] as const)('maps status-zero %s by transport bodySent only', (kind) => {
    expect(classifyUploadFailure(new UploadTransportError(kind, false))).toEqual({ kind: 'incomplete', message: 'File too large or connection lost', requestId: null, serverMessage: null });
    expect(classifyUploadFailure(new UploadTransportError(kind, true))).toMatchObject({ kind: 'unknown', message: 'Upload result unknown', requestId: null });
  });

  it('calls a user abort cancelled only when it settles before the body was sent', () => {
    expect(classifyUploadFailure(new UploadTransportError('abort', false), { userCancelled: true })).toMatchObject({ kind: 'cancelled' });
    expect(classifyUploadFailure(new UploadTransportError('abort', true), { userCancelled: true })).toMatchObject({ kind: 'unknown' });
    expect(classifyUploadFailure(new UploadTransportError('abort', false))).toMatchObject({ kind: 'incomplete' });
  });

  it('makes superseded authentication terminal before or after the transport body was sent', () => {
    expect(classifyUploadFailure(new AuthenticationSupersededError())).toMatchObject({ kind: 'sessionChanged', message: 'Your session changed. Select Upload to try again.' });
    expect(classifyUploadFailure(new UploadAuthenticationSupersededError(false))).toMatchObject({ kind: 'sessionChanged' });
    expect(classifyUploadFailure(new UploadAuthenticationSupersededError(true))).toMatchObject({ kind: 'unknown' });
  });

  it('keeps unexpected responses and generic failures unknown', () => {
    expect(classifyUploadFailure(new Error('invalid response'))).toMatchObject({ kind: 'unknown' });
    expect(classifyUploadFailure(new ApiRequestError(302, null))).toMatchObject({ kind: 'unknown' });
  });
});
