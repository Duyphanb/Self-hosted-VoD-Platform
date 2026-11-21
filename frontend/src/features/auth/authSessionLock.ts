const SESSION_LOCK_NAME = 'vod.auth.session.v1';
let singleTabTail: Promise<unknown> = Promise.resolve();

/** Timeout/abort/response handling all finish before releasing the cookie-writer lock. */
export function withAuthSessionLock<T>(operation: (signal: AbortSignal) => Promise<T>, timeout = 10_000): Promise<T> {
  const run = async () => {
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), timeout);
    const aborted = new Promise<never>((_, reject) => {
      controller.signal.addEventListener('abort', () => {
        reject(new DOMException('Authentication request timed out', 'TimeoutError'));
      }, { once: true });
    });
    try {
      return await Promise.race([operation(controller.signal), aborted]);
    } finally {
      controller.abort();
      window.clearTimeout(timer);
    }
  };
  if (navigator.locks?.request) return navigator.locks.request(SESSION_LOCK_NAME, run);
  // A single-tab fallback, not a cross-tab mutex. The provider shows this limitation.
  const result = singleTabTail.then(run, run);
  singleTabTail = result.catch(() => undefined);
  return result;
}
