import {
  XhrLoader,
  type HlsConfig,
  type LoaderCallbacks,
  type LoaderConfiguration,
  type LoaderContext,
  type LoaderResponse,
  type LoaderStats
} from 'hls.js';
import {
  ApiRequestError,
  AuthenticationSupersededError,
  type ApiClient,
  type AuthenticationSnapshot
} from '../../lib/api/client';

/** Why authentication ended an HLS load. */
export type HlsAuthenticationFailure =
  /** The refresh, or the resend with the recovered token, was rejected with 401: the session ended. */
  | 'sessionEnded'
  /** The refresh failed by a network error, a timeout or a 5xx, or the request left the HLS proxy. */
  | 'unavailable'
  /** The signed-in identity changed; this player must stop. */
  | 'superseded';

type LoaderErrorCallback = LoaderCallbacks<LoaderContext>['onError'];

/**
 * The authentication of one player run (ADR-011 D2 and its 2026-11-22 addendum): the identity that was
 * signed in when the run started, the shared ApiClient recovery, and the failure of every load that
 * authentication ended. hls.js loads playlists and fragments through `AuthenticatedHlsLoader`.
 */
export class HlsAuthentication {
  private readonly failures = new WeakMap<object, HlsAuthenticationFailure>();
  private disposed = false;

  constructor(
    private readonly client: ApiClient,
    private readonly identity: AuthenticationSnapshot
  ) {}

  /** The authentication an attempt sends now. Throws when the player stopped or the identity changed. */
  currentAuthentication(): AuthenticationSnapshot {
    if (this.disposed) {
      throw new Error('HLS loading stopped');
    }
    const authentication = this.client.getAuthenticationSnapshot();
    if (!authentication || !sameIdentity(authentication, this.identity)) {
      throw new AuthenticationSupersededError();
    }
    return authentication;
  }

  /** The shared ApiClient recovery after a 401 of an attempt that sent `rejected`. */
  recover(rejected: AuthenticationSnapshot): Promise<AuthenticationSnapshot> {
    return this.client.recoverAuthentication(rejected);
  }

  /** Why a recovery failed: a refresh 401 ended the session; a network error, timeout or 5xx keeps it. */
  classifyRecoveryFailure(failure: unknown): HlsAuthenticationFailure {
    if (this.identityChanged() || failure instanceof AuthenticationSupersededError) {
      return 'superseded';
    }
    if (failure instanceof ApiRequestError && failure.status === 401) {
      return 'sessionEnded';
    }
    return 'unavailable';
  }

  /**
   * The resend after the recovery still got 401 with `attempted`. The session ends only while `attempted`
   * is the current authentication: a 401 of a token that has since been replaced proves nothing.
   */
  rejectResend(attempted: AuthenticationSnapshot): HlsAuthenticationFailure {
    const current = this.client.getAuthenticationSnapshot();
    if (!current || !sameIdentity(current, this.identity)) {
      return 'superseded';
    }
    if (current.accessToken !== attempted.accessToken) {
      return 'unavailable';
    }
    // The application's authentication-failure path ends a session only for an ApiRequestError 401.
    this.client.rejectAuthentication(attempted, new ApiRequestError(401, null));
    return 'sessionEnded';
  }

  recordFailure(networkDetails: unknown, failure: HlsAuthenticationFailure): void {
    if (typeof networkDetails === 'object' && networkDetails !== null) {
      this.failures.set(networkDetails, failure);
    }
  }

  /** How authentication ended the load whose last request is `networkDetails`, if it did. */
  failureFor(networkDetails: unknown): HlsAuthenticationFailure | null {
    if (typeof networkDetails !== 'object' || networkDetails === null) {
      return null;
    }
    return this.failures.get(networkDetails) ?? null;
  }

  /** Stop: no further attempt is sent for this run. */
  dispose(): void {
    this.disposed = true;
  }

  private identityChanged(): boolean {
    const current = this.client.getAuthenticationSnapshot();
    return !current || !sameIdentity(current, this.identity);
  }
}

/**
 * An hls.js XHR loader (`pLoader` and `fLoader`) that sends the bearer token (ADR-011 D2, D3).
 *
 * hls.js creates one loader for each logical load and calls `load()` once, so the D2 budget lives here:
 * - every attempt reads the current token when it is sent, only to the same-origin HLS proxy, never in a
 *   URL or a cookie;
 * - the first 401 joins the shared ApiClient recovery and the load sends once more with the recovered
 *   token, through the loader's own `loadInternal()` as for its transport retries, so `stats` and the
 *   callbacks stay those hls.js holds;
 * - hls.js sees only the final outcome: a 401 on the resend, or a failed recovery, is reported with the
 *   failure recorded on `HlsAuthentication`, and a resend never happens twice in one load, also after a
 *   transport timeout retry of the same load;
 * - after `abort()` or `destroy()` nothing more is sent, also when a recovery settles later.
 *
 * A later load of the same playlist or fragment, for example after a seek or after hls.js gave up a timed-out
 * load and loads it again, is a new loader with its own budget.
 */
export class AuthenticatedHlsLoader extends XhrLoader {
  private readonly authentication: HlsAuthentication;
  /** The authentication that the latest attempt of this load sent. */
  private attempt: AuthenticationSnapshot | null = null;
  /** The one recovery of this load; the resend waits for it in `xhrSetup`. */
  private recovery: Promise<AuthenticationSnapshot> | null = null;
  /** Why this load cannot continue with authentication, once known. */
  private failure: HlsAuthenticationFailure | null = null;

  constructor(config: HlsConfig, authentication: HlsAuthentication) {
    const setUpHolder: { loader: AuthenticatedHlsLoader | null } = { loader: null };
    super({
      ...config,
      xhrSetup: (xhr: XMLHttpRequest, url: string) => setUpHolder.loader?.prepareAttempt(xhr, url)
    });
    setUpHolder.loader = this;
    this.authentication = authentication;
  }

  load(context: LoaderContext, config: LoaderConfiguration, callbacks: LoaderCallbacks<LoaderContext>): void {
    const reportError = callbacks.onError;
    super.load(context, config, {
      ...callbacks,
      onError: (error, errorContext, networkDetails, stats) => {
        this.handleError(error, errorContext, networkDetails, stats, reportError);
      }
    });
  }

  private async prepareAttempt(xhr: XMLHttpRequest, url: string): Promise<void> {
    if (!isHlsProxyUrl(url)) {
      // A playlist naming another origin or path never receives the bearer token (ADR-011 D3, D4).
      this.failure = 'unavailable';
      throw new Error('HLS request outside the HLS proxy');
    }
    if (this.recovery) {
      await this.recovery;
    }
    if (this.isFinished()) {
      throw new Error('HLS load cancelled');
    }
    try {
      this.attempt = this.authentication.currentAuthentication();
    } catch (failure) {
      if (failure instanceof AuthenticationSupersededError) {
        this.failure = 'superseded';
      }
      throw failure;
    }
    xhr.open('GET', url, true);
    xhr.withCredentials = false;
    xhr.setRequestHeader('Authorization', `Bearer ${this.attempt.accessToken}`);
  }

  private handleError(
    error: { code: number; text: string },
    context: LoaderContext,
    networkDetails: unknown,
    stats: LoaderStats,
    reportError: LoaderErrorCallback
  ): void {
    if (error.code === 401 && this.attempt && !this.recovery && !this.isFinished()) {
      this.startRecovery(this.attempt);
      return;
    }
    const failure = this.failure ?? this.resendFailure(error.code);
    if (failure) {
      this.authentication.recordFailure(networkDetails, failure);
    }
    reportError(error, context, networkDetails as XMLHttpRequest | null, stats);
  }

  /** ADR-011 D2: join the shared recovery, then send this load once more, after the recovery settles. */
  private startRecovery(rejected: AuthenticationSnapshot): void {
    const recovery = this.authentication.recover(rejected);
    this.recovery = recovery;
    recovery.catch((failure: unknown) => {
      this.failure = this.authentication.classifyRecoveryFailure(failure);
    });
    this.abortInternal();
    this.resetInternalLoader();
    this.loadInternal();
  }

  /** A 401 after the recovery: the resend was rejected. */
  private resendFailure(status: number): HlsAuthenticationFailure | null {
    if (status !== 401 || !this.recovery || !this.attempt) {
      return null;
    }
    return this.authentication.rejectResend(this.attempt);
  }

  /** Whether hls.js aborted or destroyed this load; `destroy()` sets `stats` and `context` to null. */
  private isFinished(): boolean {
    const stats = this.stats as LoaderStats | null;
    return stats === null || stats.aborted || this.context === null;
  }
}

/** The loader class for hls.js `pLoader` and `fLoader`, bound to one player run's authentication. */
export function authenticatedLoaderClass(
  authentication: HlsAuthentication
): new (config: HlsConfig) => AuthenticatedHlsLoader {
  return class extends AuthenticatedHlsLoader {
    constructor(config: HlsConfig) {
      super(config, authentication);
    }
  };
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

/** Whether `url` is a request to this origin's authenticated HLS proxy. */
function isHlsProxyUrl(url: string): boolean {
  try {
    const parsed = new URL(url, window.location.href);
    return parsed.origin === window.location.origin && parsed.pathname.startsWith('/hls/video-assets/');
  } catch {
    return false;
  }
}

function sameIdentity(left: AuthenticationSnapshot, right: AuthenticationSnapshot): boolean {
  return left.userId === right.userId && left.generation === right.generation;
}
