import {
  ApiRequestError, AuthenticationSupersededError, UploadAuthenticationSupersededError,
  UploadTransportError
} from '../../lib/api/client';
import { MAX_UPLOAD_SIZE_BYTES, MAX_UPLOAD_SIZE_MB } from './uploadLimits';

export const VIDEO_MIME_BY_EXTENSION = {
  mp4: 'video/mp4',
  mov: 'video/quicktime',
  mkv: 'video/x-matroska',
  webm: 'video/webm'
} as const;

export const VIDEO_FILE_ACCEPT = Object.keys(VIDEO_MIME_BY_EXTENSION).map((extension) => `.${extension}`).join(',');

export type VideoFileCheck = { valid: true; declaredType: string } | { valid: false; message: string };

/** Validate metadata only. Content-family validation remains authoritative on the server. */
export function checkVideoFile(file: File): VideoFileCheck {
  if (file.size === 0) return { valid: false, message: 'The selected file is empty.' };
  if (file.size > MAX_UPLOAD_SIZE_BYTES) {
    return { valid: false, message: `The selected file is larger than the ${MAX_UPLOAD_SIZE_MB} MiB limit.` };
  }
  const extension = file.name.slice(file.name.lastIndexOf('.') + 1).toLowerCase();
  if (!file.name.includes('.') || !Object.prototype.hasOwnProperty.call(VIDEO_MIME_BY_EXTENSION, extension)) {
    return { valid: false, message: 'Choose an MP4, MOV, MKV or WebM video file.' };
  }
  const declaredType = file.type === ''
    ? VIDEO_MIME_BY_EXTENSION[extension as keyof typeof VIDEO_MIME_BY_EXTENSION]
    : file.type;
  if (!Object.values(VIDEO_MIME_BY_EXTENSION).some((allowed) => allowed === declaredType)) {
    return { valid: false, message: 'The selected file has an unsupported video MIME type.' };
  }
  return { valid: true, declaredType };
}

/** A sending phase never says 100%; the transport's body-sent event selects the saving phase. */
export function uploadPercent(loaded: number, total: number | null): number | null {
  if (total === null || !Number.isFinite(total) || total <= 0 || !Number.isFinite(loaded)) return null;
  return Math.max(0, Math.min(99, Math.floor(loaded * 100 / total)));
}

export type UploadFailureKind = 'unknown' | 'cancelled' | 'incomplete' | 'sessionExpired' | 'sessionChanged'
  | 'forbidden' | 'notFound' | 'inProgress' | 'tooLarge' | 'rejected' | 'notAccepted';

export interface UploadFailure {
  kind: UploadFailureKind;
  message: string;
  requestId: string | null;
  serverMessage: string | null;
}

/** Classify a settled operation, using only transport-owned evidence about whether its body was sent. */
export function classifyUploadFailure(error: unknown, options: { userCancelled?: boolean } = {}): UploadFailure {
  const failure = (kind: UploadFailureKind, message: string): UploadFailure => ({
    kind, message,
    requestId: error instanceof ApiRequestError ? error.apiError?.requestId || null : null,
    serverMessage: error instanceof ApiRequestError ? error.apiError?.message ?? null : null
  });
  const unknown = () => failure('unknown', 'Upload result unknown');

  if (error instanceof AuthenticationSupersededError) {
    return error instanceof UploadAuthenticationSupersededError && error.bodySent
      ? unknown()
      : failure('sessionChanged', 'Your session changed. Select Upload to try again.');
  }
  if (error instanceof UploadTransportError) {
    if (error.bodySent) return unknown();
    if (error.kind === 'abort' && options.userCancelled) {
      return failure('cancelled', 'Upload cancelled before the file was fully sent.');
    }
    return failure('incomplete', 'File too large or connection lost');
  }
  if (!(error instanceof ApiRequestError)) return unknown();

  const { status, apiError } = error;
  const trustedCode = apiError?.status === status ? apiError.code : null;
  if (status >= 500 && status <= 599) {
    if (status === 503 && trustedCode === 'UPLOAD_STORAGE_UNAVAILABLE') {
      return failure('notAccepted', 'Upload not accepted (no committed asset/job pair). Storage was unavailable. Select Upload to retry manually.');
    }
    if (status === 500 && trustedCode === 'UPLOAD_DATABASE_ERROR') {
      return failure('notAccepted', 'Upload not accepted (no committed asset/job pair). The upload could not be recorded. Select Upload to retry manually.');
    }
    return unknown();
  }
  if (status === 401) {
    return failure('sessionExpired', 'Your session expired. The file was not sent again automatically; select Upload to retry or sign in again.');
  }
  if (status === 403) return failure('forbidden', 'You do not have permission to upload a video for this movie.');
  if (status === 404) return failure('notFound', 'Movie not found');
  if (status === 409) return failure('inProgress', 'An upload for this movie is already in progress.');
  if (status === 413) {
    return failure('tooLarge', `File exceeds the ${MAX_UPLOAD_SIZE_MB} MiB upload limit (including multipart overhead).`);
  }
  if (status === 400 && trustedCode === 'UNSUPPORTED_VIDEO_TYPE') {
    return failure('rejected', 'The file is not a supported video or its content does not match its type (MP4, MOV, MKV or WebM).');
  }
  if (status >= 400 && status <= 499) return failure('rejected', 'The server rejected the upload request. Check the selected file.');
  return unknown();
}
