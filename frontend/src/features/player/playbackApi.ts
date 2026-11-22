import type { ApiClient, AuthenticationSnapshot } from '../../lib/api/client';
import { isCanonicalUuid } from '../../lib/uuid';

/** `GET /movies/{movieId}/playback` (API contract `PlaybackInfo`). */
export interface PlaybackInfo {
  movieId: string;
  videoAssetId: string;
  /** Same-origin path of the HLS playlist, for example `/hls/video-assets/{id}/master.m3u8`. */
  hlsManifestUrl: string;
  /** Where playback resumes, in whole seconds; 0 starts from the beginning (ADR-011 D11). */
  resumeSeconds: number;
  durationSeconds: number | null;
}

/** One progress save (API contract `ProgressRequest`); `durationSeconds` is ignored by the server and omitted. */
export interface ProgressSave {
  videoAssetId: string;
  currentSeconds: number;
  finished: boolean;
}

export class InvalidPlaybackResponseError extends Error {
  constructor() {
    super('Server returned invalid playback information');
    this.name = 'InvalidPlaybackResponseError';
  }
}

/**
 * Playback information for a visible movie. The API answers 404 MOVIE_NOT_FOUND for a missing or hidden
 * movie and 409 VIDEO_NOT_READY when the movie has no current READY asset.
 */
export async function fetchPlaybackInfo(
  client: ApiClient,
  movieId: string,
  signal?: AbortSignal,
  identity?: AuthenticationSnapshot
): Promise<PlaybackInfo> {
  // With `identity`, the request belongs to that viewer and is never sent with another account's token.
  const response = await client.request<unknown>(`/movies/${encodeURIComponent(movieId)}/playback`, {
    authenticated: true,
    identity,
    signal
  });
  const playbackInfo = decodePlaybackInfo(response);
  if (!playbackInfo) {
    throw new InvalidPlaybackResponseError();
  }
  return playbackInfo;
}

/**
 * A regular save through the shared authentication recovery (ADR-011 D13). It is sent only while `identity`
 * is signed in, so a save of one viewer never lands in another account. The stored state is not needed.
 */
export async function saveProgress(
  client: ApiClient,
  movieId: string,
  save: ProgressSave,
  identity: AuthenticationSnapshot
): Promise<void> {
  await client.request<unknown>(progressPath(movieId), {
    method: 'PUT',
    authenticated: true,
    identity,
    json: save
  });
}

/** A best-effort save at page unload: `keepalive`, the current token, no refresh or retry (ADR-011 D13). */
export function saveProgressOnUnload(
  client: ApiClient,
  movieId: string,
  save: ProgressSave,
  identity: AuthenticationSnapshot
): void {
  client.sendOnUnload(progressPath(movieId), { method: 'PUT', json: save }, identity);
}

/** The same-origin path of an asset's HLS playlist (API contract `PlaybackInfo.hlsManifestUrl`). */
export function hlsManifestPath(videoAssetId: string): string {
  return `/hls/video-assets/${videoAssetId}/master.m3u8`;
}

function progressPath(movieId: string): string {
  return `/movies/${encodeURIComponent(movieId)}/progress`;
}

export function decodePlaybackInfo(value: unknown): PlaybackInfo | null {
  if (!value || typeof value !== 'object') {
    return null;
  }
  const candidate = value as Record<string, unknown>;
  const resumeSeconds = candidate.resumeSeconds ?? 0;
  const durationSeconds = candidate.durationSeconds ?? null;
  const validResume = Number.isInteger(resumeSeconds) && (resumeSeconds as number) >= 0;
  const validDuration = durationSeconds === null || Number.isInteger(durationSeconds);
  if (!isCanonicalUuid(candidate.movieId) || !isCanonicalUuid(candidate.videoAssetId)) {
    return null;
  }
  // The bearer token goes only to the same-origin HLS proxy of this asset (ADR-011 D3, D4).
  const validManifest = candidate.hlsManifestUrl === hlsManifestPath(candidate.videoAssetId);
  if (!validManifest || !validResume || !validDuration) {
    return null;
  }
  return {
    movieId: candidate.movieId,
    videoAssetId: candidate.videoAssetId,
    hlsManifestUrl: candidate.hlsManifestUrl as string,
    resumeSeconds: resumeSeconds as number,
    durationSeconds: durationSeconds as number | null
  };
}
