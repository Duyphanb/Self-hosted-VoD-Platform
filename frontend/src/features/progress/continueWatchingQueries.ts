import { useQuery } from '@tanstack/react-query';
import { useApiClient } from '../auth/AuthContext';
import { getContinueWatching } from './continueWatchingApi';

/** Every progress query key starts with `progressKeys.all`. */
export const progressKeys = {
  all: ['progress'] as const,
  continueWatching: () => [...progressKeys.all, 'continue-watching'] as const
};

/**
 * The continue-watching list. Positions change while the viewer watches, so every visit fetches it
 * again; the player marks it stale after each save, so an open list refreshes too.
 */
export function useContinueWatching() {
  const apiClient = useApiClient();
  return useQuery({
    queryKey: progressKeys.continueWatching(),
    queryFn: ({ signal }) => getContinueWatching(apiClient, signal),
    refetchOnMount: 'always'
  });
}
