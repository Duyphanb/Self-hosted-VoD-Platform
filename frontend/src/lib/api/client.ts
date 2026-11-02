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
    let response = await this.send(path, options, accessToken);

    if (authenticated && response.status === 401) {
      const currentAuthentication = this.authController.getAuthenticationSnapshot();
      if (!sameIdentity(initialAuthentication, currentAuthentication)) {
        throw new AuthenticationSupersededError();
      }

      if (currentAuthentication && currentAuthentication.accessToken !== accessToken) {
        response = await this.send(path, options, currentAuthentication.accessToken);
        if (response.status === 401) {
          await this.throwAuthenticationFailure(response, currentAuthentication);
        }
      } else {
        try {
          await this.refreshAccessTokenOnce();
        } catch (error) {
          if (sameAuthentication(
            initialAuthentication,
            this.authController.getAuthenticationSnapshot()
          )) {
            this.authController.onAuthenticationFailure(error);
          }
          throw error;
        }

        const refreshedAuthentication = this.authController.getAuthenticationSnapshot();
        if (!sameIdentity(initialAuthentication, refreshedAuthentication)) {
          throw new AuthenticationSupersededError();
        }
        if (!refreshedAuthentication) {
          const error = new AuthenticationSupersededError();
          this.authController.onAuthenticationFailure(error);
          throw error;
        }

        response = await this.send(path, options, refreshedAuthentication.accessToken);
        if (response.status === 401) {
          await this.throwAuthenticationFailure(response, refreshedAuthentication);
        }
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
    const { authenticated: _authenticated, json, headers: initialHeaders, ...requestInit } = options;
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
    if (sameAuthentication(
      attemptedAuthentication,
      currentAuthentication
    )) {
      this.authController.onAuthenticationFailure(error);
    }
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
