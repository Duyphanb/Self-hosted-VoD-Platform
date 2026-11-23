import { useQuery } from '@tanstack/react-query';
import type { ApiClient } from '../../lib/api/client';
import { isCanonicalUuid } from '../../lib/uuid';
import { useApiClient } from '../auth/AuthContext';
import { waitForClosingPlayerSaves } from './closingPlayerSaves';
import { fetchPlaybackInfo, type PlaybackInfo } from './playbackApi';

/** Every playback query key starts with `playbackKeys.all`. */
export const playbackKeys = {
  all: ['playback'] as const,
  info: (movieId: string) => [...playbackKeys.all, movieId] as const
};

/**
 * Playback info for the watch page. The resume position changes while the viewer watches (ADR-011 D11), so
 * every visit fetches it again and no answer is kept after the page closes.
 */
export function usePlaybackInfo(movieId: string) {
  const apiClient = useApiClient();
  return useQuery({
    queryKey: playbackKeys.info(movieId),
    queryFn: ({ signal }) => fetchPlaybackInfoForVisit(apiClient, movieId, signal),
    enabled: isCanonicalUuid(movieId),
    staleTime: 0,
    gcTime: 0,
    refetchOnMount: 'always',
    // The mounted player handles later asset changes itself; a reconnect must not swap it out.
    refetchOnReconnect: false
  });
}

/**
 * The playback info that starts a visit of the watch page. A player of the same movie that just closed may
 * still be sending its last save, so the request waits for it (bounded) and resumes at that position.
 * The player's own asset recovery calls `fetchPlaybackInfo` directly and never waits.
 */
export async function fetchPlaybackInfoForVisit(
  client: ApiClient,
  movieId: string,
  signal?: AbortSignal
): Promise<PlaybackInfo> {
  const viewer = client.getAuthenticationSnapshot();
  await waitForClosingPlayerSaves(movieId, viewer?.userId ?? null, signal);
  return fetchPlaybackInfo(client, movieId, signal);
}
