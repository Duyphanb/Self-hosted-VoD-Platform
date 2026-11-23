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

/** How long a playback request waits at most for the movie's running progress saves. */
export const RUNNING_SAVE_WAIT_MS = 3_000;

/**
 * Regular progress saves still running, per movie. A player that closes sends its last save as it unmounts;
 * a playback request for the same movie that starts before that save lands would read the older position.
 */
const runningSaves = new Map<string, Set<Promise<unknown>>>();

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
  // The resume position must include the last save of a player that just closed (ADR-011 D11).
  await waitForRunningSaves(movieId, signal);
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
  const request = client.request<unknown>(progressPath(movieId), {
    method: 'PUT',
    authenticated: true,
    identity,
    json: save
  });
  trackRunningSave(movieId, request);
  await request;
}

function trackRunningSave(movieId: string, request: Promise<unknown>): void {
  const saves = runningSaves.get(movieId) ?? new Set<Promise<unknown>>();
  saves.add(request);
  runningSaves.set(movieId, saves);
  const release = () => {
    saves.delete(request);
    if (saves.size === 0 && runningSaves.get(movieId) === saves) {
      runningSaves.delete(movieId);
    }
  };
  request.then(release, release);
}

/**
 * Waits until the movie's running saves settle, whether they succeed or fail, for at most
 * `RUNNING_SAVE_WAIT_MS`, so a stalled save never blocks playback. An aborted request stops waiting at once.
 */
async function waitForRunningSaves(movieId: string, signal?: AbortSignal): Promise<void> {
  const saves = runningSaves.get(movieId);
  if (!saves || saves.size === 0 || signal?.aborted) {
    return;
  }
  let timer: ReturnType<typeof setTimeout> | undefined;
  let stopWaiting: () => void = () => undefined;
  const limit = new Promise<void>((resolve) => {
    stopWaiting = () => resolve();
    timer = setTimeout(stopWaiting, RUNNING_SAVE_WAIT_MS);
    signal?.addEventListener('abort', stopWaiting, { once: true });
  });
  try {
    await Promise.race([Promise.allSettled([...saves]), limit]);
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', stopWaiting);
  }
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
