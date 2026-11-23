import { afterEach, describe, expect, it, vi } from 'vitest';
import { CLOSING_SAVE_WAIT_MS, registerClosingPlayerSaves, waitForClosingPlayerSaves } from './closingPlayerSaves';

const MOVIE_ID = '6f1c0f55-2d4a-4b64-9a43-1d0f3c7b8e21';
const OTHER_MOVIE_ID = '7334b2f1-664f-4bc1-b8f1-10de58c1d74a';
const VIEWER_ID = '3fcf25c2-f096-4462-ae63-4ba4702a9078';
const OTHER_VIEWER_ID = 'b5f7c0f4-1d2e-4c3b-9a8f-7e6d5c4b3a21';

describe('waitForClosingPlayerSaves', () => {
  const releases: Array<() => void> = [];

  afterEach(() => {
    // Settle every registered save, so no test leaves a wait behind for the next one.
    for (const release of releases.splice(0)) {
      release();
    }
    vi.useRealTimers();
  });

  it('waits until the closed player of the same viewer and movie has saved', async () => {
    registerSave(MOVIE_ID, VIEWER_ID);
    let waited = false;

    const wait = waitForClosingPlayerSaves(MOVIE_ID, VIEWER_ID).then(() => {
      waited = true;
    });
    await flushMicrotasks();
    expect(waited).toBe(false);

    releases[0]();
    await wait;
    expect(waited).toBe(true);
  });

  it('does not wait for another movie or another viewer', async () => {
    registerSave(OTHER_MOVIE_ID, VIEWER_ID);
    registerSave(MOVIE_ID, OTHER_VIEWER_ID);

    expect(await resolvesAtOnce(waitForClosingPlayerSaves(MOVIE_ID, VIEWER_ID))).toBe(true);
  });

  it('does not wait without a signed-in viewer', async () => {
    registerSave(MOVIE_ID, VIEWER_ID);

    expect(await resolvesAtOnce(waitForClosingPlayerSaves(MOVIE_ID, null))).toBe(true);
  });

  it('stops waiting for a save that stalls, after the wait limit', async () => {
    vi.useFakeTimers();
    registerSave(MOVIE_ID, VIEWER_ID);
    let waited = false;

    void waitForClosingPlayerSaves(MOVIE_ID, VIEWER_ID).then(() => {
      waited = true;
    });
    await vi.advanceTimersByTimeAsync(CLOSING_SAVE_WAIT_MS - 1);
    expect(waited).toBe(false);
    await vi.advanceTimersByTimeAsync(1);

    expect(waited).toBe(true);
  });

  it('stops waiting at once when the request is aborted', async () => {
    registerSave(MOVIE_ID, VIEWER_ID);
    const controller = new AbortController();

    const wait = waitForClosingPlayerSaves(MOVIE_ID, VIEWER_ID, controller.signal);
    controller.abort();

    await expect(wait).resolves.toBeUndefined();
  });

  it('forgets the saves once they are done', async () => {
    registerSave(MOVIE_ID, VIEWER_ID);
    releases[0]();
    await flushMicrotasks();
    vi.useFakeTimers();
    let waited = false;

    void waitForClosingPlayerSaves(MOVIE_ID, VIEWER_ID).then(() => {
      waited = true;
    });
    await vi.advanceTimersByTimeAsync(0);

    expect(waited).toBe(true);
  });

  function registerSave(movieId: string, userId: string): void {
    const saved = new Promise<void>((resolve) => {
      releases.push(resolve);
    });
    registerClosingPlayerSaves(movieId, userId, saved);
  }
});

/** Whether the promise settles within a few microtasks, long before the wait limit. */
async function resolvesAtOnce(promise: Promise<void>): Promise<boolean> {
  let settled = false;
  void promise.then(() => {
    settled = true;
  });
  await flushMicrotasks();
  return settled;
}

async function flushMicrotasks(): Promise<void> {
  for (let step = 0; step < 20; step += 1) {
    await Promise.resolve();
  }
}
