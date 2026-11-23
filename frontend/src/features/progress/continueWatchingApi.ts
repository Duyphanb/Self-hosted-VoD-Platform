import type { ApiClient } from '../../lib/api/client';
import {
  decodeMovieSummary,
  decodePlaybackProgress,
  type MovieSummary,
  type PlaybackProgress
} from '../movies/moviesApi';

/** One movie the viewer can resume (API contract `ContinueWatchingItem`). */
export interface ContinueWatchingItem {
  movie: MovieSummary;
  /** Normalized against the movie's current video (ADR-011 D11), so it matches where playback resumes. */
  progress: PlaybackProgress;
}

export class InvalidContinueWatchingResponseError extends Error {
  constructor() {
    super('Server returned an invalid continue watching list');
    this.name = 'InvalidContinueWatchingResponseError';
  }
}

/**
 * The caller's unfinished movies, most recently watched first: at most 50, as one array without
 * pagination (ADR-011 D11). Only published movies with a ready video are listed.
 */
export async function getContinueWatching(
  client: ApiClient,
  signal?: AbortSignal
): Promise<ContinueWatchingItem[]> {
  const response = await client.request<unknown>('/me/continue-watching', {
    authenticated: true,
    signal
  });
  return decodeContinueWatching(response);
}

export function decodeContinueWatching(value: unknown): ContinueWatchingItem[] {
  if (!Array.isArray(value)) {
    throw new InvalidContinueWatchingResponseError();
  }
  return value.map(decodeContinueWatchingItem);
}

function decodeContinueWatchingItem(value: unknown): ContinueWatchingItem {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new InvalidContinueWatchingResponseError();
  }
  const record = value as Record<string, unknown>;
  const movie = decodeMovieSummary(record.movie);
  const progress = decodePlaybackProgress(record.progress);
  if (!movie || !progress || progress.movieId !== movie.id) {
    throw new InvalidContinueWatchingResponseError();
  }
  return { movie, progress };
}
