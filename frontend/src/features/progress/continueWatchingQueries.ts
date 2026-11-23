import { type QueryClient, useQuery } from '@tanstack/react-query';
import { useApiClient } from '../auth/AuthContext';
import { getContinueWatching } from './continueWatchingApi';

/** Every progress query key starts with `progressKeys.all`. */
export const progressKeys = {
  all: ['progress'] as const,
  continueWatching: () => [...progressKeys.all, 'continue-watching'] as const
};

/**
 * The continue-watching list. Positions change while the viewer watches, so every visit fetches it
 * again, and the player calls `refreshContinueWatching` after each save, so an open list refreshes too.
 */
export function useContinueWatching() {
  const apiClient = useApiClient();
  return useQuery({
    queryKey: progressKeys.continueWatching(),
    queryFn: ({ signal }) => getContinueWatching(apiClient, signal),
    refetchOnMount: 'always'
  });
}

/**
 * After a progress save. A list request already running may have read the position before the save, so it
 * is cancelled and the list is marked stale: an open page fetches it again, a closed one on its next visit.
 * Without the cancel, TanStack Query reuses a first request that has no data yet, and its old answer would
 * clear the stale mark.
 */
export async function refreshContinueWatching(queryClient: QueryClient): Promise<void> {
  const queryKey = progressKeys.continueWatching();
  await queryClient.cancelQueries({ queryKey });
  await queryClient.invalidateQueries({ queryKey });
}
