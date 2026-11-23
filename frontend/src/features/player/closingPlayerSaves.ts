/** How long, from the moment a player closes, a new visit may wait for that player's saves. */
export const CLOSING_SAVE_WAIT_MS = 3_000;

/**
 * The saves still running or queued in players that closed, per viewer and movie. A player sends its last
 * save as it unmounts, possibly queued behind a save in flight; a new visit of the same movie that asked for
 * the playback info before those saves land would resume at an older position (ADR-011 D11, D13).
 *
 * Each entry settles when its saves are done, or `CLOSING_SAVE_WAIT_MS` after the player closed, whichever
 * comes first, and then leaves the registry. Every later wait of the same visit (Retry, Check again, the
 * automatic recheck) therefore uses only the time that is left.
 */
const closingSaves = new Map<string, Set<Promise<void>>>();

/** Called by a player as it unmounts, with the promise that its saves are done. */
export function registerClosingPlayerSaves(movieId: string, userId: string, saved: Promise<void>): void {
  const key = closingKey(movieId, userId);
  const saves = closingSaves.get(key) ?? new Set<Promise<void>>();
  const doneOrExpired = new Promise<void>((resolve) => {
    const expiry = setTimeout(resolve, CLOSING_SAVE_WAIT_MS);
    const finish = () => {
      clearTimeout(expiry);
      resolve();
    };
    saved.then(finish, finish);
  });
  saves.add(doneOrExpired);
  closingSaves.set(key, saves);
  void doneOrExpired.then(() => {
    saves.delete(doneOrExpired);
    if (saves.size === 0 && closingSaves.get(key) === saves) {
      closingSaves.delete(key);
    }
  });
}

/**
 * Waits until the closed players' saves for this viewer and movie are done or have expired. Another viewer
 * never waits, and an aborted request stops waiting at once.
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
  let stopWaiting: () => void = () => undefined;
  const aborted = new Promise<void>((resolve) => {
    stopWaiting = () => resolve();
    signal?.addEventListener('abort', stopWaiting, { once: true });
  });
  try {
    await Promise.race([Promise.all([...saves]), aborted]);
  } finally {
    signal?.removeEventListener('abort', stopWaiting);
  }
}

function closingKey(movieId: string, userId: string): string {
  return `${userId}:${movieId}`;
}
