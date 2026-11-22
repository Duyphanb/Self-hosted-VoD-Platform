import type { ApiError } from './types';

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api/v1';

export interface AuthenticationSnapshot {
  userId: string;
  accessToken: string;
  generation: number;
}

export interface AuthRequestController {
  getAuthenticationSnapshot(): AuthenticationSnapshot | null;
  refreshAccessToken(): Promise<string>;
  onAuthenticationFailure(error: unknown): void;
}

export interface ApiRequestOptions extends Omit<RequestInit, 'body'> {
  authenticated?: boolean;
  json?: unknown;
  /**
   * For an authenticated request that belongs to one identity, such as a player's progress save: when
   * another identity is signed in, nothing is sent and AuthenticationSupersededError is thrown.
   */
  identity?: AuthenticationSnapshot;
}

export interface UploadOptions {
  signal?: AbortSignal;
  onProgress?: (loaded: number, total: number | null) => void;
  onBodySent?: () => void;
}

export interface UploadResponse {
  status: number;
  payload: unknown;
}

export class UploadTransportError extends Error {
  constructor(readonly kind: 'network' | 'abort' | 'timeout', readonly bodySent: boolean) {
    super(`Upload ${kind}`);
    this.name = 'UploadTransportError';
  }
}

export class ApiRequestError extends Error {
  readonly status: number;
  readonly apiError: ApiError | null;

  constructor(status: number, payload: unknown) {
    const apiError = isApiError(payload) ? payload : null;
    super(apiError?.message ?? `Request failed with status ${status}`);
    this.name = 'ApiRequestError';
    this.status = status;
    this.apiError = apiError;
  }
}

export class AuthenticationSupersededError extends Error {
  constructor() {
    super('Authentication changed while the operation was in progress');
    this.name = 'AuthenticationSupersededError';
  }
}

/** Preserve the transport's body-completion fact when an upload loses its session. */
export class UploadAuthenticationSupersededError extends AuthenticationSupersededError {
  constructor(readonly bodySent: boolean) {
    super();
    this.name = 'UploadAuthenticationSupersededError';
  }
}

export class ApiClient {
  private readonly baseUrl: string;
  private readonly authController: AuthRequestController;
  private refreshPromise: Promise<string> | null = null;

  constructor(baseUrl: string, authController: AuthRequestController) {
    this.baseUrl = baseUrl.replace(/\/$/, '');
    this.authController = authController;
  }

  async request<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
    const authenticated = options.authenticated ?? false;
    const initialAuthentication = authenticated
      ? this.authController.getAuthenticationSnapshot()
      : null;
    const accessToken = initialAuthentication?.accessToken ?? null;
    if (authenticated && !initialAuthentication) throw new AuthenticationSupersededError();
    if (options.identity && !sameIdentity(options.identity, initialAuthentication)) {
      throw new AuthenticationSupersededError();
    }
    let response = await this.send(path, options, accessToken);

    if (initialAuthentication && response.status === 401) {
      await this.recoverAuthentication(initialAuthentication);
      // Read the session again right before sending: it may have changed while the recovery settled.
      const retryAuthentication = this.authController.getAuthenticationSnapshot();
      if (!retryAuthentication || !sameIdentity(initialAuthentication, retryAuthentication)) {
        throw new AuthenticationSupersededError();
      }
      response = await this.send(path, options, retryAuthentication.accessToken);
      if (response.status === 401) {
        await this.throwAuthenticationFailure(response, retryAuthentication);
      }
    }

    if (authenticated) {
      this.assertCurrentIdentity(initialAuthentication);
    }

    if (!response.ok) {
      const error = await responseError(response);
      if (authenticated) {
        this.assertCurrentIdentity(initialAuthentication);
      }
      throw error;
    }

    const payload = await responsePayload<T>(response);
    if (authenticated) {
      this.assertCurrentIdentity(initialAuthentication);
    }
    return payload;
  }

  /** The authentication a request sent now must use; null when signed out or while the session changes. */
  getAuthenticationSnapshot(): AuthenticationSnapshot | null {
    return this.authController.getAuthenticationSnapshot();
  }

  /**
   * The one authentication recovery after a 401 (ADR-011 D2), shared by JSON requests and by callers that
   * send their own requests, such as the HLS player. `rejected` is the authentication the 401 answered.
   *
   * - A late 401 of an older token resolves at once with the current authentication, without a refresh.
   * - Otherwise the caller joins the single-flight refresh and gets the refreshed authentication.
   * - A refresh rejected with 401 ends the session (when `rejected` is still current) and is rethrown.
   * - Any other refresh failure (network error, timeout, 5xx) is rethrown and keeps the session.
   * - Throws AuthenticationSupersededError when the signed-in identity changed.
   *
   * The caller retries its request once with the returned authentication and reports a second 401
   * through `rejectAuthentication`.
   */
  async recoverAuthentication(rejected: AuthenticationSnapshot): Promise<AuthenticationSnapshot> {
    const currentAuthentication = this.authController.getAuthenticationSnapshot();
    if (!currentAuthentication || !sameIdentity(rejected, currentAuthentication)) {
      throw new AuthenticationSupersededError();
    }
    if (currentAuthentication.accessToken !== rejected.accessToken) {
      return currentAuthentication;
    }

    try {
      await this.refreshAccessTokenOnce();
    } catch (error) {
      const refreshRejected = error instanceof ApiRequestError && error.status === 401;
      if (refreshRejected && sameAuthentication(rejected, this.authController.getAuthenticationSnapshot())) {
        this.authController.onAuthenticationFailure(error);
      }
      throw error;
    }

    const refreshedAuthentication = this.authController.getAuthenticationSnapshot();
    if (!refreshedAuthentication || !sameIdentity(rejected, refreshedAuthentication)) {
      throw new AuthenticationSupersededError();
    }
    return refreshedAuthentication;
  }

  /**
   * The retry after `recoverAuthentication` still got 401 with `attempted`: the session has expired.
   * It ends only while `attempted` is still the current authentication, so a late callback of an older
   * token or identity never ends a newer session.
   */
  rejectAuthentication(attempted: AuthenticationSnapshot, error: unknown): void {
    if (sameAuthentication(attempted, this.authController.getAuthenticationSnapshot())) {
      this.authController.onAuthenticationFailure(error);
    }
  }

  /**
   * A best-effort request at page unload (ADR-011 D13): the current token, `keepalive`, and no recovery
   * or retry. It is sent only while `identity` is still the signed-in identity, and its result is ignored.
   * Returns whether the request was sent.
   */
  sendOnUnload(
    path: string,
    options: { method: string; json: unknown },
    identity: AuthenticationSnapshot
  ): boolean {
    const currentAuthentication = this.authController.getAuthenticationSnapshot();
    if (!currentAuthentication || !sameIdentity(identity, currentAuthentication)) {
      return false;
    }
    const requestOptions: ApiRequestOptions = {
      method: options.method,
      json: options.json,
      keepalive: true
    };
    this.send(path, requestOptions, currentAuthentication.accessToken).catch(() => undefined);
    return true;
  }

  /** A single, non-replayable body transfer. Only the preceding GET may refresh authentication. */
  upload(path: string, body: FormData, options: UploadOptions = {}): Promise<UploadResponse> {
    const authentication = this.authController.getAuthenticationSnapshot();
    if (!authentication?.accessToken) return Promise.reject(new UploadAuthenticationSupersededError(false));
    if (options.signal?.aborted) return Promise.reject(new UploadTransportError('abort', false));

    return new Promise((resolve, reject) => {
      let xhr: XMLHttpRequest;
      try {
        xhr = new XMLHttpRequest();
      } catch {
        reject(new UploadTransportError('network', false));
        return;
      }
      let bodySent = false;
      let settled = false;

      const cleanup = () => {
        options.signal?.removeEventListener('abort', abort);
        xhr.upload.removeEventListener('progress', progress);
        xhr.upload.removeEventListener('load', markBodySent);
        xhr.removeEventListener('load', load);
        xhr.removeEventListener('error', networkError);
        xhr.removeEventListener('timeout', timeout);
        xhr.removeEventListener('abort', aborted);
      };
      const settle = (result: UploadResponse | Error) => {
        if (settled) return;
        settled = true;
        cleanup();
        try {
          this.assertCurrentIdentity(authentication);
        } catch {
          reject(new UploadAuthenticationSupersededError(bodySent));
          return;
        }
        if (result instanceof Error) reject(result);
        else resolve(result);
      };
      const markBodySent = () => {
        if (bodySent || settled) return;
        bodySent = true;
        options.onBodySent?.();
      };
      const progress = (event: ProgressEvent) => {
        if (settled) return;
        // Establish transport completion before notifying a callback that could cancel.
        if (event.lengthComputable && event.total > 0 && event.loaded >= event.total) markBodySent();
        if (settled) return;
        options.onProgress?.(event.loaded, event.lengthComputable ? event.total : null);
      };
      const load = () => {
        const status = xhr.status;
        const payload = parsePayload(xhr.responseText);
        settle(status >= 200 && status < 300
          ? { status, payload }
          : status === 0 ? new UploadTransportError('network', bodySent) : new ApiRequestError(status, payload));
      };
      const networkError = () => settle(new UploadTransportError('network', bodySent));
      const timeout = () => settle(new UploadTransportError('timeout', bodySent));
      const aborted = () => settle(new UploadTransportError('abort', bodySent));
      const abort = () => xhr.abort();

      xhr.upload.addEventListener('progress', progress);
      xhr.upload.addEventListener('load', markBodySent);
      xhr.addEventListener('load', load);
      xhr.addEventListener('error', networkError);
      xhr.addEventListener('timeout', timeout);
      xhr.addEventListener('abort', aborted);
      options.signal?.addEventListener('abort', abort);
      try {
        xhr.open('POST', this.url(path));
        xhr.timeout = 0;
        xhr.withCredentials = false;
        xhr.setRequestHeader('Authorization', `Bearer ${authentication.accessToken}`);
        this.assertCurrentIdentity(authentication);
        xhr.send(body);
      } catch (error) {
        settle(error instanceof AuthenticationSupersededError
          ? new UploadAuthenticationSupersededError(bodySent) : new UploadTransportError('network', bodySent));
      }
    });
  }

  private send(
    path: string,
    options: ApiRequestOptions,
    accessToken: string | null
  ): Promise<Response> {
    const {
      authenticated: _authenticated,
      identity: _identity,
      json,
      headers: initialHeaders,
      ...requestInit
    } = options;
    const headers = new Headers(initialHeaders);
    if (json !== undefined) {
      headers.set('Content-Type', 'application/json');
    }
    if (accessToken) {
      headers.set('Authorization', `Bearer ${accessToken}`);
    }

    return fetch(this.url(path), {
      ...requestInit,
      headers,
      body: json === undefined ? undefined : JSON.stringify(json)
    });
  }

  private url(path: string): string {
    return `${this.baseUrl}/${path.replace(/^\//, '')}`;
  }

  private refreshAccessTokenOnce(): Promise<string> {
    if (!this.refreshPromise) {
      const refreshPromise = Promise.resolve().then(() => this.authController.refreshAccessToken());
      this.refreshPromise = refreshPromise;
      refreshPromise.then(
        () => {
          if (this.refreshPromise === refreshPromise) {
            this.refreshPromise = null;
          }
        },
        () => {
          if (this.refreshPromise === refreshPromise) {
            this.refreshPromise = null;
          }
        }
      );
    }
    return this.refreshPromise;
  }

  private async throwAuthenticationFailure(
    response: Response,
    attemptedAuthentication: AuthenticationSnapshot
  ): Promise<never> {
    const error = await responseError(response);
    const currentAuthentication = this.authController.getAuthenticationSnapshot();
    if (!sameIdentity(attemptedAuthentication, currentAuthentication)) {
      throw new AuthenticationSupersededError();
    }
    this.rejectAuthentication(attemptedAuthentication, error);
    throw error;
  }

  private assertCurrentIdentity(initialAuthentication: AuthenticationSnapshot | null): void {
    if (!sameIdentity(
      initialAuthentication,
      this.authController.getAuthenticationSnapshot()
    )) {
      throw new AuthenticationSupersededError();
    }
  }
}

async function responseError(response: Response): Promise<ApiRequestError> {
  return new ApiRequestError(response.status, await readPayload(response));
}

async function responsePayload<T>(response: Response): Promise<T> {
  if (response.status === 204) {
    return undefined as T;
  }
  return (await readPayload(response)) as T;
}

async function readPayload(response: Response): Promise<unknown> {
  return parsePayload(await response.text());
}

function parsePayload(text: string): unknown {
  if (!text) {
    return null;
  }
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return text;
  }
}

function isApiError(value: unknown): value is ApiError {
  if (!value || typeof value !== 'object') {
    return false;
  }
  const candidate = value as Partial<ApiError>;
  return typeof candidate.timestamp === 'string'
    && Number.isInteger(candidate.status)
    && typeof candidate.code === 'string'
    && typeof candidate.message === 'string'
    && (candidate.requestId === undefined
      || candidate.requestId === null
      || typeof candidate.requestId === 'string')
    && (candidate.fieldErrors === undefined
      || (Array.isArray(candidate.fieldErrors)
        && candidate.fieldErrors.every(isApiFieldError)));
}

function isApiFieldError(value: unknown): boolean {
  if (!value || typeof value !== 'object') {
    return false;
  }
  const candidate = value as { field?: unknown; message?: unknown };
  return typeof candidate.field === 'string' && typeof candidate.message === 'string';
}

function sameIdentity(
  left: AuthenticationSnapshot | null,
  right: AuthenticationSnapshot | null
): boolean {
  return left?.userId === right?.userId && left?.generation === right?.generation;
}

function sameAuthentication(
  left: AuthenticationSnapshot | null,
  right: AuthenticationSnapshot | null
): boolean {
  return sameIdentity(left, right) && left?.accessToken === right?.accessToken;
}
