/** How long the watch page waits at most for the saves of a player that just closed. */
export const CLOSING_SAVE_WAIT_MS = 3_000;

/**
 * The saves still running or queued in players that closed, per viewer and movie. A player sends its last
 * save as it unmounts, possibly queued behind a save in flight; a new visit of the same movie that asked for
 * the playback info before those saves land would resume at an older position (ADR-011 D11, D13).
 */
const closingSaves = new Map<string, Set<Promise<void>>>();

/** Called by a player as it unmounts, with the promise that its saves are done. */
export function registerClosingPlayerSaves(movieId: string, userId: string, saved: Promise<void>): void {
  const key = closingKey(movieId, userId);
  const saves = closingSaves.get(key) ?? new Set<Promise<void>>();
  saves.add(saved);
  closingSaves.set(key, saves);
  const release = () => {
    saves.delete(saved);
    if (saves.size === 0 && closingSaves.get(key) === saves) {
      closingSaves.delete(key);
    }
  };
  saved.then(release, release);
}

/**
 * Waits until the closed players' saves for this viewer and movie are done, for at most
 * `CLOSING_SAVE_WAIT_MS`, so a stalled save never blocks playback. Another viewer never waits, and an
 * aborted request stops waiting at once.
 */
export async function waitForClosingPlayerSaves(
  movieId: string,
  userId: string | null,
  signal?: AbortSignal
): Promise<void> {
  const saves = userId === null ? undefined : closingSaves.get(closingKey(movieId, userId));
  if (!saves || saves.size === 0 || signal?.aborted) {
    return;
  }
  let timer: ReturnType<typeof setTimeout> | undefined;
  let stopWaiting: () => void = () => undefined;
  const limit = new Promise<void>((resolve) => {
    stopWaiting = () => resolve();
    timer = setTimeout(stopWaiting, CLOSING_SAVE_WAIT_MS);
    signal?.addEventListener('abort', stopWaiting, { once: true });
  });
  try {
    await Promise.race([Promise.allSettled([...saves]), limit]);
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', stopWaiting);
  }
}

function closingKey(movieId: string, userId: string): string {
  return `${userId}:${movieId}`;
}
