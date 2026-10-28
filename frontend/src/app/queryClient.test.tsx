import { QueryClientProvider, useQuery } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import type { PropsWithChildren } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { ApiRequestError, AuthenticationSupersededError } from '../lib/api/client';
import { createQueryClient, shouldRetryQuery } from './queryClient';

describe('shouldRetryQuery', () => {
  it.each([400, 401, 403, 404, 409, 499])('does not retry an API %i', (status) => {
    expect(shouldRetryQuery(0, apiError(status))).toBe(false);
  });

  it('does not retry a request whose session was replaced', () => {
    expect(shouldRetryQuery(0, new AuthenticationSupersededError())).toBe(false);
  });

  it.each([500, 502, 503])('retries an API %i once', (status) => {
    expect(shouldRetryQuery(0, apiError(status))).toBe(true);
    expect(shouldRetryQuery(1, apiError(status))).toBe(false);
  });

  it('retries a network failure once', () => {
    expect(shouldRetryQuery(0, new TypeError('Failed to fetch'))).toBe(true);
    expect(shouldRetryQuery(1, new TypeError('Failed to fetch'))).toBe(false);
  });
});

describe('createQueryClient', () => {
  it('shows a 404 after a single request', async () => {
    const queryFn = vi.fn(() => Promise.reject(apiError(404)));

    const { result } = renderQuery(queryFn);

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error).toBeInstanceOf(ApiRequestError);
    expect(queryFn).toHaveBeenCalledTimes(1);
  });

  it('tries a 503 twice before failing', async () => {
    const queryFn = vi.fn(() => Promise.reject(apiError(503)));

    const { result } = renderQuery(queryFn);

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(queryFn).toHaveBeenCalledTimes(2);
  });
});

function renderQuery(queryFn: () => Promise<never>) {
  const queryClient = createQueryClient();
  // Keep the production retry predicate; only skip the backoff delay.
  queryClient.setQueryDefaults(['retry-test'], { retryDelay: 0 });
  const wrapper = ({ children }: PropsWithChildren) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return renderHook(() => useQuery({ queryKey: ['retry-test'], queryFn }), { wrapper });
}

function apiError(status: number): ApiRequestError {
  return new ApiRequestError(status, {
    timestamp: '2026-10-28T00:00:00Z',
    status,
    code: status === 404 ? 'MOVIE_NOT_FOUND' : 'ERROR',
    message: `Status ${status}`
  });
}
