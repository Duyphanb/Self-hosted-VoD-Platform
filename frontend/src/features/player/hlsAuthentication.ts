import type { LoaderResponse, LoadPolicy, RetryConfig } from 'hls.js';
import {
  ApiRequestError,
  AuthenticationSupersededError,
  type ApiClient,
  type AuthenticationSnapshot
} from '../../lib/api/client';

/** Why an HLS request could not continue with authentication. */
export type HlsAuthenticationFailure =
  /** The refresh, or the retry with the recovered token, was rejected with 401: the session ended. */
  | 'sessionEnded'
  /** The refresh failed by a network error, a timeout or a 5xx, or no further recovery is allowed. */
  | 'unavailable'
  /** The signed-in identity changed; this player must stop. */
  | 'superseded';

/** What the player does with a load failure that stopped loading. */
export type LoadFailureDecision =
  | { kind: 'notAuthentication' }
  | { kind: 'failed'; failure: HlsAuthenticationFailure }
  | { kind: 'restartLoading' };

/** One sent attempt of a request, keyed by its XMLHttpRequest. */
interface RequestAttempt {
  authentication: AuthenticationSnapshot;
  /** True when the attempt was sent after the request's one recovery: it is the D2 retry. */
  afterRecovery: boolean;
}

interface RequestBudget {
  /** The latest attempt; the `shouldRetry` hook sees only the URL, so it recovers with this attempt's token. */
  latestAttempt: RequestAttempt | null;
  /** True once this request has used its one recovery. */
  recoveryUsed: boolean;
  /** The recovery that the retried attempt waits for in `xhrSetup`. */
  recovery: Promise<AuthenticationSnapshot> | null;
  /** When the recovery settled (succeeded or failed), in milliseconds since the epoch. */
  settledAt: number | null;
  failure: HlsAuthenticationFailure | null;
}

/**
 * How long a settled recovery stays with its request. hls.js schedules the retry when the 401 arrives, at
 * most `maxRetryDelayMs` (8 seconds in 1.7.4) later, so the D2 retry follows its recovery within that time.
 * A later attempt gets a new budget: a load after the viewer seeked away while the retry was scheduled and
 * came back, or one after a failed refresh, which then recovers again instead of failing at once.
 *
 * Inside the window, a 401 for the token the recovery returned ends the session even when hls.js abandoned
 * the original retry. When that token came from the shared refresh it is at most 15 seconds old, far below
 * the shortest access-token lifetime in use (one minute in the #125 stack, five minutes by default, ADR-012
 * D5), so the 401 is a rejection, not an expiry. A late 401 returns the current token without a refresh; a
 * 401 for it ends the session exactly as D2 and the JSON requests already do. After a longer stall, for
 * example a suspended tab, a retry may recover once more: one extra refresh, never a loop.
 */
export const RETRY_WINDOW_MS = 15_000;

type ShouldRetry = NonNullable<RetryConfig['shouldRetry']>;

/**
 * Bearer authentication for the requests hls.js sends (ADR-011 D2 and its 2026-11-22 addendum).
 *
 * - Every attempt reads the current access token in `xhrSetup`; no cookie and no token in a URL. The
 *   token goes only to the same-origin HLS proxy (`/hls/video-assets/...`).
 * - A request, identified by its URL, has one budget: one shared ApiClient recovery and one retry. The
 *   budget lives as long as hls.js loads that URL: a response with 2xx, an aborted attempt (for example
 *   after a seek) or the end of `RETRY_WINDOW_MS` clears it, so a later load of the same segment gets a
 *   new one.
 * - The load-policy `shouldRetry` hook spends the budget on the first 401, and the retried attempt
 *   waits for the recovery in `xhrSetup`.
 * - `isTerminalLoadError` names the failures that must stop loading at once, although hls.js 1.7.4 would
 *   still retry a fragment of a single-level stream: a 404, a 401 on the retry, and a failed recovery.
 * - `resolveLoadFailure` decides such a failure, and any fatal network error: a 401 on the retry ends the
 *   session through `ApiClient.rejectAuthentication` only while the token that attempt sent is current.
 *
 * One instance serves one hls.js instance and the identity that was signed in when it was created.
 */
export class HlsRequestAuthenticator {
  private readonly budgets = new Map<string, RequestBudget>();
  private readonly attempts = new WeakMap<object, RequestAttempt>();
  private disposed = false;

  constructor(
    private readonly client: ApiClient,
    private readonly identity: AuthenticationSnapshot
  ) {}

  /**
   * hls.js `xhrSetup`. A rejection makes hls.js report a network error for the request. `loader` is the
   * hls.js loader that called it, which tells whether hls.js gave the attempt up while it waited for the
   * recovery, for example after a seek.
   */
  readonly setUpRequest = async (xhr: XMLHttpRequest, url: string, loader?: unknown): Promise<void> => {
    const isCancelled = () => isAttemptAbandoned(loader, xhr);
    this.throwIfDisposed();
    let budget = this.budgetFor(url);
    if (!isHlsProxyUrl(url)) {
      // A playlist naming another origin or path never receives the bearer token (ADR-011 D3, D4).
      budget.failure = 'unavailable';
      throw new Error('HLS request outside the HLS proxy');
    }
    if (budget.settledAt !== null && Date.now() - budget.settledAt > RETRY_WINDOW_MS) {
      this.budgets.delete(url);
      budget = this.budgetFor(url);
    }
    if (budget.recovery) {
      try {
        await budget.recovery;
      } catch (failure) {
        this.forgetIfCancelled(url, budget, isCancelled);
        throw failure;
      }
    }
    // The player may have stopped, or hls.js may have given the load up, while the attempt waited.
    this.throwIfDisposed();
    if (this.forgetIfCancelled(url, budget, isCancelled)) {
      throw new Error('HLS load cancelled');
    }
    // An abandoned attempt may have ended this budget while this one waited: then this is a new load.
    if (this.budgets.get(url) !== budget) {
      budget = this.budgetFor(url);
    }
    const authentication = this.currentAuthentication(budget);
    const attempt: RequestAttempt = { authentication, afterRecovery: budget.recoveryUsed };
    budget.latestAttempt = attempt;
    this.attempts.set(xhr, attempt);
    xhr.open('GET', url, true);
    xhr.withCredentials = false;
    xhr.setRequestHeader('Authorization', `Bearer ${authentication.accessToken}`);
    xhr.addEventListener('load', () => {
      const succeeded = xhr.status >= 200 && xhr.status < 300;
      if (succeeded) {
        this.releaseBudget(url, budget);
      }
    });
    // hls.js aborts an attempt when it stops loading that URL, for example after a seek or a timeout.
    xhr.addEventListener('abort', () => this.releaseBudget(url, budget));
  };

  /** The `shouldRetry` hook of the manifest, playlist and fragment load policies. */
  readonly shouldRetry: ShouldRetry = (_retryConfig, _retryCount, _isTimeout, response, retry) => {
    if (response?.code !== 401) {
      return retry;
    }
    return this.startRecovery(response.url);
  };

  /** The hls.js load policy `policy` with the 401 hook on its error retries. */
  withAuthenticationRetry(policy: LoadPolicy): LoadPolicy {
    const errorRetry: RetryConfig = policy.default.errorRetry
      ? { ...policy.default.errorRetry, shouldRetry: this.shouldRetry }
      : { maxNumRetry: 0, retryDelayMs: 0, maxRetryDelayMs: 0, shouldRetry: this.shouldRetry };
    return { default: { ...policy.default, errorRetry } };
  }

  /**
   * Whether a load error must stop loading now, even when hls.js reports it as non-fatal and would retry it:
   * a 404 (the asset is not current or not visible, ADR-011 D4), a 401 on the request's retry after its
   * recovery, or a recovery that already failed. `networkDetails` is the XMLHttpRequest of the attempt.
   */
  isTerminalLoadError(url: string | undefined, status: number | undefined, networkDetails?: unknown): boolean {
    if (status === 404) {
      return true;
    }
    const budget = url === undefined ? undefined : this.budgets.get(url);
    if (budget?.failure) {
      return true;
    }
    const attempt = this.attemptFor(networkDetails);
    return status === 401 && attempt?.afterRecovery === true;
  }

  /**
   * Decide a load failure that stopped loading, for the request for `url` that ended with HTTP `status`:
   * a recorded recovery failure, a 401 on the retry (which ends the session), a 401 whose retry has not
   * happened yet (recover once, then the caller restarts loading), or not an authentication failure at all.
   */
  async resolveLoadFailure(
    url: string | undefined,
    status: number | undefined,
    networkDetails?: unknown
  ): Promise<LoadFailureDecision> {
    const budget = url === undefined ? undefined : this.budgets.get(url);
    if (budget?.recovery) {
      await budget.recovery.catch(() => undefined);
    }
    if (budget?.failure) {
      return { kind: 'failed', failure: budget.failure };
    }
    if (status !== 401) {
      return { kind: 'notAuthentication' };
    }
    const attempt = this.attemptFor(networkDetails) ?? budget?.latestAttempt;
    if (!url || !budget || !attempt) {
      // Without the attempt's token the 401 can neither be recovered nor prove that the session expired.
      return { kind: 'failed', failure: 'unavailable' };
    }
    if (attempt.afterRecovery) {
      return { kind: 'failed', failure: this.rejectRetriedRequest(attempt.authentication) };
    }

    // The retry has not happened: recover now unless the hook already did, and never a second time.
    if (!budget.recoveryUsed) {
      this.startRecovery(url);
    }
    await budget.recovery?.catch(() => undefined);
    if (budget.failure) {
      return { kind: 'failed', failure: budget.failure };
    }
    return { kind: 'restartLoading' };
  }

  /** Stop answering hls.js; queued attempts are not sent and pending recoveries no longer affect this player. */
  dispose(): void {
    this.disposed = true;
    this.budgets.clear();
  }

  /** A load that hls.js gave up ends its budget, even after a failed recovery: a later load starts afresh. */
  private forgetIfCancelled(url: string, budget: RequestBudget, isCancelled: () => boolean): boolean {
    if (!isCancelled()) {
      return false;
    }
    if (this.budgets.get(url) === budget) {
      this.budgets.delete(url);
    }
    return true;
  }

  private releaseBudget(url: string, budget: RequestBudget): void {
    if (this.budgets.get(url) === budget && !budget.failure) {
      this.budgets.delete(url);
    }
  }

  private budgetFor(url: string): RequestBudget {
    let budget = this.budgets.get(url);
    if (!budget) {
      budget = { latestAttempt: null, recoveryUsed: false, recovery: null, settledAt: null, failure: null };
      this.budgets.set(url, budget);
    }
    return budget;
  }

  private attemptFor(networkDetails: unknown): RequestAttempt | undefined {
    if (typeof networkDetails !== 'object' || networkDetails === null) {
      return undefined;
    }
    return this.attempts.get(networkDetails);
  }

  private throwIfDisposed(): void {
    if (this.disposed) {
      throw new Error('HLS loading stopped');
    }
  }

  private currentAuthentication(budget: RequestBudget): AuthenticationSnapshot {
    const authentication = this.client.getAuthenticationSnapshot();
    if (!authentication || !sameIdentity(authentication, this.identity)) {
      budget.failure = 'superseded';
      throw new AuthenticationSupersededError();
    }
    return authentication;
  }

  private startRecovery(url: string): boolean {
    const budget = this.budgets.get(url);
    if (this.disposed || !budget?.latestAttempt || budget.recoveryUsed) {
      return false;
    }
    budget.recoveryUsed = true;
    const recovery = this.client.recoverAuthentication(budget.latestAttempt.authentication);
    budget.recovery = recovery;
    recovery.then(
      () => {
        budget.settledAt = Date.now();
      },
      (failure: unknown) => {
        budget.failure = this.identityChanged() ? 'superseded' : classifyRecoveryFailure(failure);
        budget.settledAt = Date.now();
      }
    );
    return true;
  }

  private identityChanged(): boolean {
    const current = this.client.getAuthenticationSnapshot();
    return !current || !sameIdentity(current, this.identity);
  }

  private rejectRetriedRequest(attempted: AuthenticationSnapshot): HlsAuthenticationFailure {
    const current = this.client.getAuthenticationSnapshot();
    if (!current || !sameIdentity(current, this.identity)) {
      return 'superseded';
    }
    if (current.accessToken !== attempted.accessToken) {
      // A newer token exists, so this 401 proves nothing; the budget allows no second recovery.
      return 'unavailable';
    }
    // The application's authentication-failure path ends a session only for an ApiRequestError 401.
    this.client.rejectAuthentication(attempted, new ApiRequestError(401, null));
    return 'sessionEnded';
  }
}

/** The response of a failed hls.js request, as its error data reports it. */
export interface FailedRequest {
  url: string | undefined;
  status: number | undefined;
}

export function failedRequest(data: {
  response?: LoaderResponse;
  url?: string;
  context?: { url: string };
  frag?: { url: string } | null;
}): FailedRequest {
  return {
    url: data.response?.url ?? data.context?.url ?? data.frag?.url ?? data.url,
    status: data.response?.code
  };
}

/**
 * Whether hls.js gave up the attempt on `xhr`. It is the check that the hls.js 1.7.4 XHR loader makes itself
 * after `xhrSetup` (`this.loader !== xhr || this.stats.aborted`), plus a destroyed loader, whose `stats` is
 * null. `loader` is the `this` of the `xhrSetup` call; without one nothing counts as abandoned.
 */
export function isAttemptAbandoned(loader: unknown, xhr: XMLHttpRequest): boolean {
  if (typeof loader !== 'object' || loader === null) {
    return false;
  }
  const xhrLoader = loader as { loader?: unknown; stats?: { aborted?: boolean } | null };
  if (xhrLoader.stats === null || xhrLoader.stats?.aborted === true) {
    return true;
  }
  return 'loader' in xhrLoader && xhrLoader.loader !== xhr;
}

/** Whether `url` is a request to this origin's authenticated HLS proxy. */
function isHlsProxyUrl(url: string): boolean {
  try {
    const parsed = new URL(url, window.location.href);
    return parsed.origin === window.location.origin && parsed.pathname.startsWith('/hls/video-assets/');
  } catch {
    return false;
  }
}

function classifyRecoveryFailure(failure: unknown): HlsAuthenticationFailure {
  if (failure instanceof AuthenticationSupersededError) {
    return 'superseded';
  }
  if (failure instanceof ApiRequestError && failure.status === 401) {
    return 'sessionEnded';
  }
  return 'unavailable';
}

function sameIdentity(left: AuthenticationSnapshot, right: AuthenticationSnapshot): boolean {
  return left.userId === right.userId && left.generation === right.generation;
}
