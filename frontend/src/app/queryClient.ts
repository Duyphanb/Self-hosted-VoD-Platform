import { QueryClient } from '@tanstack/react-query';
import { ApiRequestError, AuthenticationSupersededError } from '../lib/api/client';

const MAX_QUERY_RETRIES = 1;

/**
 * Retries a failed query once, except for answers that a retry cannot change: a 4xx such as
 * 404 MOVIE_NOT_FOUND or 403 shows at once, and a request whose session was replaced stays silent.
 */
export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  if (failureCount >= MAX_QUERY_RETRIES) {
    return false;
  }
  if (error instanceof AuthenticationSupersededError) {
    return false;
  }
  return !(error instanceof ApiRequestError && error.status < 500);
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: shouldRetryQuery,
        refetchOnWindowFocus: false
      }
    }
  });
}
