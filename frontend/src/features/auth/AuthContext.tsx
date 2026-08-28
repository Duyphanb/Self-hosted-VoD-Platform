import { useQueryClient } from '@tanstack/react-query';
import {
  createContext,
  type PropsWithChildren,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState
} from 'react';
import {
  API_BASE_URL,
  ApiClient,
  AuthenticationSupersededError
} from '../../lib/api/client';
import type { AuthResponse, UserProfile } from '../../lib/api/types';
import {
  login as loginRequest,
  logout as logoutRequest,
  refresh as refreshRequest,
  register as registerRequest,
  type LoginRequest,
  type RegisterRequest
} from './authApi';
import {
  AUTH_GENERATION_STORAGE_KEY,
  AUTH_SESSION_STORAGE_KEY,
  advanceAuthGeneration,
  clearAuthSession,
  createAuthSession,
  readAuthGeneration,
  type AuthSession,
  readAuthSession,
  writeAuthSession
} from './authStorage';

const REFRESH_WINDOW_MILLISECONDS = 30_000;
const AUTH_SESSION_LOCK_NAME = 'vod.auth.session.v1';
const REFRESH_TIMEOUT_MILLISECONDS = 10_000;
const TOKEN_REVOCATION_TIMEOUT_MILLISECONDS = 5_000;

export interface AuthContextValue {
  user: UserProfile | null;
  accessToken: string | null;
  refreshToken: string | null;
  isAuthenticated: boolean;
  isInitializing: boolean;
  isLoading: boolean;
  error: Error | null;
  login(request: LoginRequest): Promise<UserProfile>;
  register(request: RegisterRequest): Promise<UserProfile>;
  logout(): Promise<void>;
  refreshTokens(): Promise<void>;
  clearError(): void;
}

interface AuthProviderProps extends PropsWithChildren {
  storage?: Storage;
  redirectToLogin?: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);
const ApiClientContext = createContext<ApiClient | null>(null);

export function AuthProvider({
  children,
  storage = window.localStorage,
  redirectToLogin = defaultRedirectToLogin
}: AuthProviderProps) {
  const queryClient = useQueryClient();
  const [session, setSession] = useState<AuthSession | null>(null);
  const [isInitializing, setIsInitializing] = useState(true);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const sessionRef = useRef<AuthSession | null>(null);
  const sessionRevisionRef = useRef(0);
  const pendingOperationsRef = useRef(0);
  const latestOperationRef = useRef(0);
  const logoutInProgressRef = useRef(false);
  const refreshInFlightRef = useRef<Promise<AuthSession> | null>(null);
  const bootstrapStartedRef = useRef(false);
  const authenticationFailureHandledRef = useRef(false);
  const refreshAccessTokenRef = useRef<() => Promise<string>>(
    () => Promise.reject(new Error('Authentication provider is not ready'))
  );
  const authenticationFailureRef = useRef<(failure: unknown) => void>(() => undefined);
  const apiClientRef = useRef<ApiClient | null>(null);

  if (!apiClientRef.current) {
    apiClientRef.current = new ApiClient(API_BASE_URL, {
      getAuthenticationSnapshot: () => {
        const currentSession = sessionRef.current;
        const persistedSession = readAuthSession(storage);
        const currentGeneration = readAuthGeneration(storage);
        if (!currentSession
          || !persistedSession
          || currentSession.authGeneration !== currentGeneration
          || persistedSession.authGeneration !== currentGeneration
          || currentSession.user.id !== persistedSession.user.id
          || currentSession.accessToken !== persistedSession.accessToken
          || currentSession.refreshToken !== persistedSession.refreshToken) {
          return null;
        }
        return {
          userId: currentSession.user.id,
          accessToken: currentSession.accessToken,
          generation: currentSession.authGeneration
        };
      },
      refreshAccessToken: () => refreshAccessTokenRef.current(),
      onAuthenticationFailure: (failure) => authenticationFailureRef.current(failure)
    });
  }
  const apiClient = apiClientRef.current;

  const commitSession = useCallback((nextSession: AuthSession, expectedGeneration: number) => {
    if (readAuthGeneration(storage) !== expectedGeneration) {
      throw new AuthenticationSupersededError();
    }
    if (sessionRef.current && sessionRef.current.user.id !== nextSession.user.id) {
      queryClient.clear();
    }
    writeAuthSession(nextSession, storage);
    if (readAuthGeneration(storage) !== expectedGeneration) {
      clearAuthSession(storage);
      throw new AuthenticationSupersededError();
    }
    sessionRevisionRef.current += 1;
    sessionRef.current = nextSession;
    authenticationFailureHandledRef.current = false;
    setSession(nextSession);
  }, [queryClient, storage]);

  const restoreSession = useCallback((storedSession: AuthSession) => {
    if (storedSession.authGeneration !== readAuthGeneration(storage)) {
      clearAuthSession(storage);
      sessionRevisionRef.current += 1;
      sessionRef.current = null;
      setSession(null);
      queryClient.clear();
      return null;
    }
    if (sessionRef.current && sessionRef.current.user.id !== storedSession.user.id) {
      queryClient.clear();
    }
    sessionRevisionRef.current += 1;
    sessionRef.current = storedSession;
    authenticationFailureHandledRef.current = false;
    setSession(storedSession);
    return storedSession;
  }, [queryClient, storage]);

  const clearMemorySession = useCallback(() => {
    sessionRevisionRef.current += 1;
    sessionRef.current = null;
    setSession(null);
    queryClient.clear();
  }, [queryClient]);

  const clearSession = useCallback(() => {
    clearMemorySession();
    clearAuthSession(storage);
  }, [clearMemorySession, storage]);

  const beginOperation = useCallback(() => {
    const operationId = latestOperationRef.current + 1;
    latestOperationRef.current = operationId;
    pendingOperationsRef.current += 1;
    setIsLoading(true);
    setError(null);
    return operationId;
  }, []);

  const finishOperation = useCallback(() => {
    pendingOperationsRef.current = Math.max(0, pendingOperationsRef.current - 1);
    setIsLoading(pendingOperationsRef.current > 0);
  }, []);

  const setLatestOperationError = useCallback((operationId: number, failure: unknown) => {
    if (latestOperationRef.current === operationId) {
      setError(asError(failure));
    }
  }, []);

  const handleAuthenticationFailure = useCallback((failure: unknown) => {
    if (authenticationFailureHandledRef.current) {
      return;
    }
    authenticationFailureHandledRef.current = true;
    clearSession();
    setError(asError(failure));
    redirectToLogin();
  }, [clearSession, redirectToLogin]);

  const performRefresh = useCallback((): Promise<AuthSession> => {
    if (logoutInProgressRef.current) {
      return Promise.reject(new Error('Logout is already in progress'));
    }
    if (refreshInFlightRef.current) {
      return refreshInFlightRef.current;
    }
    const currentSession = sessionRef.current;
    if (!currentSession) {
      return Promise.reject(new Error('No refresh token is available'));
    }

    const startingRevision = sessionRevisionRef.current;
    const startingGeneration = currentSession.authGeneration;
    const attemptedRefreshToken = currentSession.refreshToken;
    const abortController = new AbortController();
    const timeoutId = window.setTimeout(
      () => abortController.abort(),
      REFRESH_TIMEOUT_MILLISECONDS
    );
    const refreshPromise = withAuthSessionLock(async () => {
      if (readAuthGeneration(storage) !== startingGeneration) {
        clearMemorySession();
        throw new AuthenticationSupersededError();
      }
      if (sessionRevisionRef.current !== startingRevision) {
        const latestSession = sessionRef.current;
        if (latestSession) {
          return latestSession;
        }
        throw new AuthenticationSupersededError();
      }

      const storedSession = readAuthSession(storage);
      if (!storedSession) {
        clearMemorySession();
        throw new AuthenticationSupersededError();
      }
      if (storedSession.refreshToken !== attemptedRefreshToken) {
        const restoredSession = restoreSession(storedSession);
        if (restoredSession) {
          return restoredSession;
        }
        throw new AuthenticationSupersededError();
      }

      let response: AuthResponse;
      try {
        response = await refreshRequest(
          apiClient,
          attemptedRefreshToken,
          abortController.signal
        );
      } catch (failure) {
        if (readAuthGeneration(storage) !== startingGeneration) {
          clearMemorySession();
          throw new AuthenticationSupersededError();
        }
        if (sessionRevisionRef.current !== startingRevision) {
          const latestSession = sessionRef.current;
          if (latestSession) {
            return latestSession;
          }
          throw new AuthenticationSupersededError();
        }

        const rotatedSession = readAuthSession(storage);
        if (!rotatedSession) {
          clearMemorySession();
          throw new AuthenticationSupersededError();
        }
        if (rotatedSession.refreshToken !== attemptedRefreshToken) {
          const restoredSession = restoreSession(rotatedSession);
          if (restoredSession) {
            return restoredSession;
          }
          throw new AuthenticationSupersededError();
        }
        throw failure;
      }

      const persistedSession = readAuthSession(storage);
      if (readAuthGeneration(storage) !== startingGeneration
        || !persistedSession
        || persistedSession.authGeneration !== startingGeneration
        || persistedSession.user.id !== currentSession.user.id
        || persistedSession.refreshToken !== attemptedRefreshToken) {
        await revokeUnusedRefreshToken(apiClient, response.refreshToken);
        if (readAuthGeneration(storage) === persistedSession?.authGeneration
          && persistedSession
          && persistedSession.refreshToken !== attemptedRefreshToken
          && sessionRef.current?.refreshToken !== persistedSession.refreshToken) {
          restoreSession(persistedSession);
        }
        const latestSession = sessionRef.current;
        if (latestSession && latestSession.refreshToken !== attemptedRefreshToken) {
          return latestSession;
        }
        clearMemorySession();
        throw new AuthenticationSupersededError();
      }

      const nextSession = createAuthSession(response, Date.now(), startingGeneration);
      try {
        commitSession(nextSession, startingGeneration);
      } catch (failure) {
        if (readAuthGeneration(storage) !== startingGeneration) {
          clearMemorySession();
        }
        await revokeUnusedRefreshToken(apiClient, response.refreshToken);
        throw failure;
      }
      return nextSession;
    }, abortController.signal).finally(() => window.clearTimeout(timeoutId));
    refreshInFlightRef.current = refreshPromise;
    refreshPromise.then(
      () => {
        if (refreshInFlightRef.current === refreshPromise) {
          refreshInFlightRef.current = null;
        }
      },
      () => {
        if (refreshInFlightRef.current === refreshPromise) {
          refreshInFlightRef.current = null;
        }
      }
    );
    return refreshPromise;
  }, [apiClient, clearMemorySession, commitSession, restoreSession, storage]);

  refreshAccessTokenRef.current = async () => (await performRefresh()).accessToken;
  authenticationFailureRef.current = handleAuthenticationFailure;

  const refreshTokens = useCallback(async () => {
    beginOperation();
    try {
      await performRefresh();
    } catch (failure) {
      handleAuthenticationFailure(failure);
      throw failure;
    } finally {
      finishOperation();
    }
  }, [beginOperation, finishOperation, handleAuthenticationFailure, performRefresh]);

  useEffect(() => {
    if (bootstrapStartedRef.current) {
      return;
    }
    bootstrapStartedRef.current = true;
    const storedSession = readAuthSession(storage);
    if (!storedSession) {
      setIsInitializing(false);
      return;
    }

    if (!restoreSession(storedSession)) {
      setIsInitializing(false);
      return;
    }
    if (storedSession.accessTokenExpiresAt > Date.now() + REFRESH_WINDOW_MILLISECONDS) {
      setIsInitializing(false);
      return;
    }

    void refreshTokens().catch(() => undefined).finally(() => setIsInitializing(false));
  }, [refreshTokens, restoreSession, storage]);

  useEffect(() => {
    const handleStorage = (event: StorageEvent) => {
      if (event.key !== null
        && event.key !== AUTH_SESSION_STORAGE_KEY
        && event.key !== AUTH_GENERATION_STORAGE_KEY) {
        return;
      }
      const externalSession = readAuthSession(storage);
      if (externalSession) {
        if (restoreSession(externalSession)) {
          setError(null);
        }
      } else {
        clearMemorySession();
      }
    };
    window.addEventListener('storage', handleStorage);
    return () => window.removeEventListener('storage', handleStorage);
  }, [clearMemorySession, restoreSession, storage]);

  const login = useCallback(async (request: LoginRequest) => {
    if (logoutInProgressRef.current) {
      throw new Error('Logout is already in progress');
    }
    const operationId = beginOperation();
    const operationGeneration = readAuthGeneration(storage);
    try {
      const response = await loginRequest(apiClient, request);
      try {
        await withAuthSessionLock(async () => {
          if (latestOperationRef.current !== operationId) {
            throw new AuthenticationSupersededError();
          }
          commitSession(
            createAuthSession(response, Date.now(), operationGeneration),
            operationGeneration
          );
        });
      } catch (failure) {
        if (readAuthGeneration(storage) !== operationGeneration) {
          clearMemorySession();
        }
        await revokeUnusedRefreshToken(apiClient, response.refreshToken);
        throw failure;
      }
      return response.user;
    } catch (failure) {
      setLatestOperationError(operationId, failure);
      throw failure;
    } finally {
      finishOperation();
    }
  }, [
    apiClient,
    beginOperation,
    clearMemorySession,
    commitSession,
    finishOperation,
    setLatestOperationError,
    storage
  ]);

  const register = useCallback(async (request: RegisterRequest) => {
    if (logoutInProgressRef.current) {
      throw new Error('Logout is already in progress');
    }
    const operationId = beginOperation();
    try {
      return await registerRequest(apiClient, request);
    } catch (failure) {
      setLatestOperationError(operationId, failure);
      throw failure;
    } finally {
      finishOperation();
    }
  }, [apiClient, beginOperation, finishOperation, setLatestOperationError]);

  const logout = useCallback(async () => {
    if (logoutInProgressRef.current) {
      return;
    }
    logoutInProgressRef.current = true;
    const operationId = beginOperation();
    let transportFailure: unknown;
    const memoryRefreshToken = sessionRef.current?.refreshToken;
    const beforeGenerationToken = readAuthSession(storage)?.refreshToken;
    const logoutGeneration = advanceAuthGeneration(storage);
    const afterGenerationToken = readAuthSession(storage)?.refreshToken;
    clearAuthSession(storage);
    authenticationFailureHandledRef.current = true;
    clearMemorySession();
    try {
      await revokeLatestSessionTokens(
        apiClient,
        storage,
        [memoryRefreshToken, beforeGenerationToken, afterGenerationToken],
        logoutGeneration
      );
    } catch (failure) {
      transportFailure = failure;
      setLatestOperationError(operationId, failure);
    } finally {
      logoutInProgressRef.current = false;
      finishOperation();
    }
    if (transportFailure) {
      throw transportFailure;
    }
  }, [apiClient, beginOperation, clearMemorySession, finishOperation, setLatestOperationError, storage]);

  const clearError = useCallback(() => setError(null), []);

  const value = useMemo<AuthContextValue>(() => ({
    user: session?.user ?? null,
    accessToken: session?.accessToken ?? null,
    refreshToken: session?.refreshToken ?? null,
    isAuthenticated: session !== null,
    isInitializing,
    isLoading,
    error,
    login,
    register,
    logout,
    refreshTokens,
    clearError
  }), [clearError, error, isInitializing, isLoading, login, logout, refreshTokens, register, session]);

  return (
    <ApiClientContext.Provider value={apiClient}>
      <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
    </ApiClientContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within AuthProvider');
  }
  return context;
}

export function useApiClient(): ApiClient {
  const context = useContext(ApiClientContext);
  if (!context) {
    throw new Error('useApiClient must be used within AuthProvider');
  }
  return context;
}

function defaultRedirectToLogin(): void {
  if (window.location.pathname !== '/login') {
    window.location.assign('/login');
  }
}

function asError(value: unknown): Error {
  return value instanceof Error ? value : new Error('Authentication request failed');
}

function withAuthSessionLock<T>(
  task: () => Promise<T>,
  signal?: AbortSignal
): Promise<T> {
  if (navigator.locks) {
    return signal
      ? navigator.locks.request(AUTH_SESSION_LOCK_NAME, { signal }, task)
      : navigator.locks.request(AUTH_SESSION_LOCK_NAME, task);
  }
  return task();
}

async function revokeRefreshToken(client: ApiClient, refreshToken: string): Promise<void> {
  const abortController = new AbortController();
  const timeoutId = window.setTimeout(
    () => abortController.abort(),
    TOKEN_REVOCATION_TIMEOUT_MILLISECONDS
  );
  try {
    await logoutRequest(client, refreshToken, abortController.signal);
  } finally {
    window.clearTimeout(timeoutId);
  }
}

async function revokeUnusedRefreshToken(client: ApiClient, refreshToken: string): Promise<void> {
  try {
    await revokeRefreshToken(client, refreshToken);
  } catch {
    // A superseded session must never replace current state, even when revocation is unavailable.
  }
}

async function revokeLatestSessionTokens(
  client: ApiClient,
  storage: Storage,
  fallbackRefreshTokens: Array<string | undefined>,
  logoutGeneration: number
): Promise<void> {
  const abortController = new AbortController();
  const timeoutId = window.setTimeout(
    () => abortController.abort(),
    TOKEN_REVOCATION_TIMEOUT_MILLISECONDS
  );
  try {
    await withAuthSessionLock(
      async () => {
        const racingSession = readAuthSession(storage);
        const racingRefreshToken = racingSession
          && racingSession.authGeneration < logoutGeneration
          ? racingSession.refreshToken
          : undefined;
        if (racingRefreshToken) {
          clearAuthSession(storage);
        }
        const tokens = Array.from(new Set(
          [racingRefreshToken, ...fallbackRefreshTokens].filter(
            (token): token is string => Boolean(token)
          )
        ));
        const results = await Promise.allSettled(tokens.map((token) => logoutRequest(
          client,
          token,
          abortController.signal
        )));
        const failedRevocation = results.find(
          (result): result is PromiseRejectedResult => result.status === 'rejected'
        );
        if (failedRevocation) {
          throw failedRevocation.reason;
        }
      },
      abortController.signal
    );
  } finally {
    abortController.abort();
    window.clearTimeout(timeoutId);
  }
}
