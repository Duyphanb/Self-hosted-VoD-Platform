import { QueryClient, QueryObserver } from '@tanstack/react-query';
import { afterEach, describe, expect, it } from 'vitest';
import { progressKeys, refreshContinueWatching } from './continueWatchingQueries';

const queryKey = progressKeys.continueWatching();

describe('refreshContinueWatching', () => {
  let stopObserving: () => void = () => undefined;

  afterEach(() => {
    stopObserving();
  });

  it('restarts a first list request that is still running, so its old answer is not kept', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const answers = observeList(queryClient);
    expect(answers).toHaveLength(1);

    // A progress save lands while the first request, which read the position before it, is still running.
    const refreshed = refreshContinueWatching(queryClient);
    await flushMicrotasks();
    expect(answers).toHaveLength(2);
    answers[0]('position before the save');
    answers[1]('position after the save');
    await refreshed;

    expect(queryClient.getQueryData(queryKey)).toBe('position after the save');
    expect(queryClient.getQueryState(queryKey)?.isInvalidated).toBe(false);
  });

  it('fetches an open list again after its answer arrived', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const answers = observeList(queryClient);
    answers[0]('position before the save');
    await flushMicrotasks();

    const refreshed = refreshContinueWatching(queryClient);
    await flushMicrotasks();
    answers[1]('position after the save');
    await refreshed;

    expect(answers).toHaveLength(2);
    expect(queryClient.getQueryData(queryKey)).toBe('position after the save');
  });

  it('only marks a list that no page shows as stale, without fetching it', async () => {
    const queryClient = new QueryClient();
    queryClient.setQueryData(queryKey, 'position before the save');

    await refreshContinueWatching(queryClient);

    expect(queryClient.getQueryState(queryKey)?.isInvalidated).toBe(true);
    expect(queryClient.getQueryState(queryKey)?.fetchStatus).toBe('idle');
  });

  /** An open list, as the page keeps it. Each request waits until the test answers it, in request order. */
  function observeList(queryClient: QueryClient): Array<(answer: string) => void> {
    const answers: Array<(answer: string) => void> = [];
    const observer = new QueryObserver(queryClient, {
      queryKey,
      queryFn: () => new Promise<string>((resolve) => {
        answers.push(resolve);
      })
    });
    stopObserving = observer.subscribe(() => undefined);
    return answers;
  }
});

async function flushMicrotasks(): Promise<void> {
  for (let step = 0; step < 20; step += 1) {
    await Promise.resolve();
  }
}
