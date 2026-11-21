import { useQueryClient } from '@tanstack/react-query';
import { createContext, type PropsWithChildren, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { API_BASE_URL, ApiClient, ApiRequestError, AuthenticationSupersededError } from '../../lib/api/client';
import type { UserProfile } from '../../lib/api/types';
import * as authApi from './authApi';
import { AUTH_GENERATION_STORAGE_KEY, createAuthSession, createMarkerStore, type AuthSession, type SessionMarker } from './authStorage';
import { withAuthSessionLock } from './authSessionLock';
import { useSupersededQueryRecovery } from './supersededQueryRecovery';

export interface AuthContextValue {
  user: UserProfile | null; accessToken: string | null;
  isAuthenticated: boolean; isInitializing: boolean; isLoading: boolean; error: Error | null;
  login(request: authApi.LoginRequest): Promise<UserProfile>;
  register(request: authApi.RegisterRequest): Promise<UserProfile>;
  logout(): Promise<void>; refreshTokens(): Promise<void>; clearError(): void;
}
interface AuthProviderProps extends PropsWithChildren { storage?: Storage; redirectToLogin?: () => void }
const AuthContext = createContext<AuthContextValue | null>(null);
const ApiClientContext = createContext<ApiClient | null>(null);
const asError = (e: unknown) => e instanceof Error ? e : new Error('Authentication request failed');
const sameMarker = (a: SessionMarker, b: SessionMarker) => a.generation === b.generation
  && a.userId === b.userId && a.logoutPending === b.logoutPending;

export function AuthProvider({ children, storage, redirectToLogin = defaultRedirect }: AuthProviderProps) {
  const queryClient = useQueryClient();
  const [store] = useState(() => createMarkerStore(storage));
  const [session, setSession] = useState<AuthSession | null>(null);
  const sessionRef = useRef<AuthSession | null>(null);
  const [isInitializing, setInitializing] = useState(true);
  const [isLoading, setLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [pending, setPending] = useState(store.read().marker.logoutPending);
  const [storageAvailable, setStorageAvailable] = useState(store.read().available);
  const [restoreFailed, setRestoreFailed] = useState(false);
  const channel = useRef<BroadcastChannel | null>(null);
  const refreshing = useRef<Promise<string> | null>(null);
  const restoring = useRef<Promise<void> | null>(null);
  const restoreQueued = useRef(false);
  const restoreCallback = useRef<(() => Promise<void>) | null>(null);
  const mounted = useRef(false);
  const bootstrapped = useRef(false);
  const actions = useRef<{ refresh: () => Promise<string>; fail: (e: unknown) => void }>({
    refresh: () => Promise.reject(new Error('Authentication is initializing')), fail: () => undefined
  });
  // eslint-disable-next-line react-hooks/refs -- ApiClient retains these callbacks; its constructor never invokes them.
  const [client] = useState(() => new ApiClient(API_BASE_URL, {
    getAuthenticationSnapshot: () => {
      const current = sessionRef.current;
      const marker = store.read().marker;
      if (!current || marker.logoutPending || current.authGeneration !== marker.generation
        || current.user.id !== marker.userId) return null;
      return { userId: current.user.id, accessToken: current.accessToken, generation: current.authGeneration };
    },
    refreshAccessToken: () => actions.current.refresh(),
    onAuthenticationFailure: (e) => actions.current.fail(e)
  }));
  const readSession = useCallback(() => sessionRef.current, []);
  useSupersededQueryRecovery(queryClient, session, readSession);
  const commit = useCallback((next: AuthSession | null) => {
    if (sessionRef.current?.user.id !== next?.user.id || sessionRef.current?.authGeneration !== next?.authGeneration) queryClient.clear();
    sessionRef.current = next; setSession(next);
  }, [queryClient]);
  const publish = useCallback((marker: SessionMarker) => {
    const result = store.write(marker);
    setStorageAvailable(result.available); setPending(marker.logoutPending);
    channel.current?.postMessage('changed'); // Wake-up only; never identity or token data.
  }, [store]);
  const signedOut = useCallback(() => {
    const m = store.read().marker;
    publish({ ...m, generation: m.generation + 1, userId: null, logoutPending: false });
    commit(null);
  }, [commit, publish, store]);
  const performRefresh = useCallback((): Promise<string> => {
    if (refreshing.current) return refreshing.current;
    const expected = store.read().marker;
    const operation = withAuthSessionLock(async (signal) => {
      if (!sameMarker(expected, store.read().marker) || expected.logoutPending) throw new AuthenticationSupersededError();
      try {
        const response = await authApi.refresh(client, signal);
        if (!sameMarker(expected, store.read().marker)) throw new AuthenticationSupersededError();
        const identityChanged = expected.userId !== null && expected.userId !== response.user.id;
        const marker = {
          ...expected,
          generation: identityChanged ? expected.generation + 1 : expected.generation,
          userId: response.user.id
        };
        // Once the response is validated, a newly mounted query's 401 needs a new
        // rotation. Queue it behind this lock instead of reusing bootstrap's token.
        refreshing.current = null;
        publish(marker); commit(createAuthSession(response, Date.now(), marker.generation));
        setError(null); setRestoreFailed(false); setInitializing(false);
        return response.accessToken;
      } catch (failure) {
        if (!sameMarker(expected, store.read().marker)) throw new AuthenticationSupersededError();
        if (failure instanceof ApiRequestError && failure.status === 401 && sameMarker(expected, store.read().marker)) {
          signedOut(); setInitializing(false); setRestoreFailed(false);
        } else if (!(failure instanceof AuthenticationSupersededError)) { setError(asError(failure)); setRestoreFailed(true); }
        throw failure;
      }
    }).catch((failure: unknown) => {
      // The lock's timeout also bounds a transport that never settles on abort.
      if (failure instanceof DOMException && failure.name === 'TimeoutError'
        && sameMarker(expected, store.read().marker)) {
        setError(failure);
        setRestoreFailed(true);
      }
      throw failure;
    });
    refreshing.current = operation;
    void operation.finally(() => { if (refreshing.current === operation) refreshing.current = null; }).catch(() => undefined);
    return operation;
  }, [client, commit, publish, signedOut, store]);

  const performLogout = useCallback(async (retry = false) => {
    if (!retry) { commit(null); publish({ ...store.read().marker, userId: null, logoutPending: true }); }
    await withAuthSessionLock(async (signal) => {
      const m = store.read().marker;
      if (retry && !m.logoutPending) return; // Later explicit login cancels stale retries.
      const next = retry ? m : { ...m, generation: m.generation + 1, userId: null, logoutPending: true };
      // A retry keeps the pending marker unchanged, so it cannot wake another
      // tab into retrying. Only a new sign-out increments the generation.
      if (!retry) publish(next);
      commit(null);
      setInitializing(false);
      try {
        await authApi.logout(client, signal);
        if (sameMarker(next, store.read().marker)) publish({ ...next, logoutPending: false });
        setError(null);
        setRestoreFailed(false);
      } catch (failure) {
        if (sameMarker(next, store.read().marker)) setError(asError(failure));
        throw failure;
      }
    });
  }, [client, commit, publish, store]);

  const restore = useCallback((bootstrap = false): Promise<void> => {
    if (restoring.current) {
      restoreQueued.current = true;
      return restoring.current;
    }
    const operation = (async () => {
      try {
        for (let attempt = 0; attempt < 3; attempt += 1) {
          const state = store.read();
          setStorageAvailable(state.available);
          setPending(state.marker.logoutPending);
          if (state.marker.logoutPending) {
            commit(null);
            setInitializing(false);
            // Wake-ups only align memory. Bootstrap gets one pending logout retry.
            if (bootstrap) {
              await performLogout(true);
            }
            return;
          }
          // Without persistent coordination, a reload cannot distinguish a failed
          // logout from a valid cookie. Only explicit sign-in may start a session.
          if (!state.available) return;
          if (state.known && state.marker.userId === null) {
            commit(null);
            return;
          }
          const current = sessionRef.current;
          if (current && current.authGeneration === state.marker.generation
            && current.user.id === state.marker.userId) return;
          commit(null);
          setInitializing(true);
          try {
            await performRefresh();
            return;
          } catch (failure) {
            if (failure instanceof AuthenticationSupersededError) {
              continue;
            }
            if (!(failure instanceof ApiRequestError && failure.status === 401)) {
              setRestoreFailed(true);
            }
            return;
          }
        }
        setError(new AuthenticationSupersededError());
        setRestoreFailed(true);
      } finally {
        setInitializing(false);
      }
    })();
    restoring.current = operation;
    void operation.finally(() => {
      if (restoring.current === operation) restoring.current = null;
      if (restoreQueued.current) {
        restoreQueued.current = false;
        queueMicrotask(() => { if (mounted.current) void restoreCallback.current?.().catch(() => undefined); });
      }
    }).catch(() => undefined);
    return operation;
  }, [commit, performLogout, performRefresh, store]);

  useEffect(() => { restoreCallback.current = restore; }, [restore]);
  useEffect(() => {
    mounted.current = true;
    if (!bootstrapped.current) { bootstrapped.current = true; void restore(true).catch(() => undefined); }
    const reconcile = () => { void restore().catch(() => undefined); };
    const onStorage = (event: StorageEvent) => { if (event.key === AUTH_GENERATION_STORAGE_KEY || event.key === null) reconcile(); };
    window.addEventListener('storage', onStorage); window.addEventListener('pageshow', reconcile);
    window.addEventListener('focus', reconcile); document.addEventListener('visibilitychange', reconcile);
    try {
      channel.current = typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel('vod.auth.wakeup.v2');
      if (channel.current) channel.current.onmessage = reconcile;
    } catch { channel.current = null; }
    return () => {
      mounted.current = false;
      window.removeEventListener('storage', onStorage); window.removeEventListener('pageshow', reconcile);
      window.removeEventListener('focus', reconcile); document.removeEventListener('visibilitychange', reconcile);
      channel.current?.close(); channel.current = null;
    };
  }, [restore]);
  useEffect(() => {
    actions.current = { refresh: performRefresh, fail: (failure) => {
      if (failure instanceof ApiRequestError && failure.status === 401) {
        const expected = store.read().marker;
        commit(null); setError(asError(failure));
        void withAuthSessionLock(async () => {
          if (sameMarker(expected, store.read().marker)) { signedOut(); redirectToLogin(); }
        });
      }
    } };
  }, [commit, performRefresh, redirectToLogin, signedOut, store]);

  const login = async (request: authApi.LoginRequest) => {
    setLoading(true); setError(null);
    try {
      return await withAuthSessionLock(async (signal) => {
        const response = await authApi.login(client, request, signal);
        const m = store.read().marker;
        const next = { ...m, generation: m.generation + 1, userId: response.user.id, logoutPending: false };
        publish(next); commit(createAuthSession(response, Date.now(), next.generation));
        setInitializing(false); setRestoreFailed(false);
        return response.user;
      });
    } catch (failure) { setError(asError(failure)); throw failure; }
    finally { setLoading(false); }
  };
  const register = async (request: authApi.RegisterRequest) => {
    setLoading(true); setError(null);
    try { return await authApi.register(client, request); }
    catch (failure) { setError(asError(failure)); throw failure; }
    finally { setLoading(false); }
  };
  const retry = () => { void (pending ? performLogout(true) : sessionRef.current ? performRefresh() : restore()).catch(() => undefined); };
  return <AuthContext.Provider value={{ user: session?.user ?? null, accessToken: session?.accessToken ?? null,
    isAuthenticated: session !== null, isInitializing, isLoading, error, login, register,
    logout: () => performLogout(), refreshTokens: async () => { await performRefresh(); }, clearError: () => setError(null) }}>
    <ApiClientContext.Provider value={client}>
      {!storageAvailable && (
        <p role="note">
          Site data is blocked. Sign-in is available only in this tab. Sign in again after a reload.
        </p>
      )}
      {!navigator.locks?.request && <p role="note">This browser supports sign-in in one tab at a time.</p>}
      {(pending || restoreFailed) && <div role="alert">
        <p>{pending ? 'Sign-out is not confirmed.' : 'Sign-in could not be restored. Please retry.'}</p>
        <button type="button" onClick={retry}>{pending ? 'Retry sign-out' : 'Retry sign-in'}</button>
      </div>}
      {children}
    </ApiClientContext.Provider>
  </AuthContext.Provider>;
}
// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext); if (!value) throw new Error('useAuth must be used within AuthProvider'); return value;
}
// eslint-disable-next-line react-refresh/only-export-components
export function useApiClient(): ApiClient {
  const value = useContext(ApiClientContext); if (!value) throw new Error('useApiClient must be used within AuthProvider'); return value;
}
function defaultRedirect() { if (window.location.pathname !== '/login') window.location.assign('/login'); }
