import type { ApiClient } from '../../lib/api/client';
import {
  decodeMovieSummary,
  decodeEpisodeSummary,
  decodePlaybackProgress,
  type EpisodeSummary,
  type MovieSummary,
  type PlaybackProgress
} from '../movies/moviesApi';

/** One movie the viewer can resume (API contract `ContinueWatchingItem`). */
export interface ContinueWatchingItem {
  kind: 'RESUME' | 'NEXT_EPISODE';
  movie: MovieSummary;
  episode: EpisodeSummary;
  progress: PlaybackProgress | null;
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
  const movie = decodeMovieSummary(record.title);
  const episode = decodeEpisodeSummary(record.episode);
  const progress = record.progress === null ? null : decodePlaybackProgress(record.progress);
  if (!movie || !episode || episode.titleId !== movie.id || progress === undefined
    || (progress && progress.episodeId !== episode.id)
    || (record.kind !== 'RESUME' && record.kind !== 'NEXT_EPISODE')
    || (record.kind === 'RESUME' && !progress)) {
    throw new InvalidContinueWatchingResponseError();
  }
  return { kind: record.kind, movie, episode, progress };
}
