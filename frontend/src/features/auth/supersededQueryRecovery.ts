import type { Query, QueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useLayoutEffect, useState } from 'react';
import { AuthenticationSupersededError } from '../../lib/api/client';

/**
 * Re-runs queries that failed with `AuthenticationSupersededError` once the session that replaced
 * theirs is in place, so a page does not wait forever for data that a sign-in change cancelled. The
 * retry predicate never retries that error, because the request belonged to the session being
 * replaced; a new fetch under the current session is the right answer instead.
 *
 * A query is re-run only when the current session differs from the one its failed fetch started
 * with, so a re-run that fails the same way cannot loop. This covers the same user's new session
 * (rotated tokens, or a new sign-in); a different user also clears the query cache and remounts the
 * protected routes (`AuthRouteGuard`), which fetch again on mount.
 *
 * @param session the committed session; a change triggers a recovery pass
 * @param currentSession reads the latest session, which ApiClient also authenticates with
 */
export function useSupersededQueryRecovery(
  queryClient: QueryClient,
  session: object | null,
  currentSession: () => object | null
): void {
  // The session each query's latest fetch started with.
  const [fetchSessions] = useState(() => new WeakMap<Query, object | null>());

  const recover = useCallback(() => {
    const current = currentSession();
    if (!current) {
      return;
    }
    const lostQueries = queryClient.getQueryCache().findAll({
      type: 'active',
      fetchStatus: 'idle',
      predicate: (query) => query.state.error instanceof AuthenticationSupersededError
        && fetchSessions.get(query) !== current
    });
    for (const query of lostQueries) {
      void queryClient.refetchQueries({ queryKey: query.queryKey, exact: true, type: 'active' });
    }
  }, [currentSession, fetchSessions, queryClient]);

  // A layout effect subscribes before the children's passive effects start their first fetches.
  useLayoutEffect(() => queryClient.getQueryCache().subscribe((event) => {
    if (event.type !== 'updated') {
      return;
    }
    if (event.action.type === 'fetch') {
      fetchSessions.set(event.query, currentSession());
    } else if (event.action.type === 'error' && event.action.error instanceof AuthenticationSupersededError) {
      // A fetch that started unobserved counts as started with the current session, so it waits
      // for the next session instead of running again at once.
      if (!fetchSessions.has(event.query)) {
        fetchSessions.set(event.query, currentSession());
      }
      // The new session may already be in place when the error arrives. Recover after the cache has
      // finished notifying, never from inside its dispatch.
      void Promise.resolve().then(recover);
    }
  }), [currentSession, fetchSessions, queryClient, recover]);

  useEffect(() => {
    if (session) {
      recover();
    }
  }, [recover, session]);
}
