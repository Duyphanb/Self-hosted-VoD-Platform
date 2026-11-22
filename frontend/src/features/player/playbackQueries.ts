import { useQuery } from '@tanstack/react-query';
import { isCanonicalUuid } from '../../lib/uuid';
import { useApiClient } from '../auth/AuthContext';
import { fetchPlaybackInfo } from './playbackApi';

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
    queryFn: ({ signal }) => fetchPlaybackInfo(apiClient, movieId, signal),
    enabled: isCanonicalUuid(movieId),
    staleTime: 0,
    gcTime: 0,
    refetchOnMount: 'always'
  });
}
