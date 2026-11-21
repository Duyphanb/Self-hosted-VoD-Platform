import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, screen, waitFor } from '@testing-library/react';
import { StrictMode, type PropsWithChildren } from 'react';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider, useAuth, useApiClient } from './AuthContext';
import * as api from './authApi';
import { AUTH_GENERATION_STORAGE_KEY, AUTH_SESSION_STORAGE_KEY, emptyMarker } from './authStorage';
import { ApiRequestError, AuthenticationSupersededError } from '../../lib/api/client';

vi.mock('./authApi');
const user={id:'viewer',email:'viewer@example.com',displayName:'Viewer',roles:['ROLE_USER' as const]};
const response={user,accessToken:'fixture-access-only',expiresInSeconds:900};
const refresh=vi.mocked(api.refresh), login=vi.mocked(api.login), logout=vi.mocked(api.logout);
function setup(storage?:Storage) {
  const cache=new QueryClient({defaultOptions:{queries:{retry:false}}});
  const redirect=vi.fn();
  const wrapper=({children}:PropsWithChildren)=><StrictMode><QueryClientProvider client={cache}>
    <AuthProvider storage={storage} redirectToLogin={redirect}>{children}</AuthProvider>
  </QueryClientProvider></StrictMode>;
  return {...renderHook(()=>({auth:useAuth(),client:useApiClient()}),{wrapper}),redirect,cache};
}
function knownUser() {window.localStorage.setItem(AUTH_GENERATION_STORAGE_KEY,JSON.stringify({...emptyMarker(),userId:user.id}));}
describe('cookie-backed memory session',()=>{
  beforeEach(()=>{vi.resetAllMocks();refresh.mockResolvedValue(response);login.mockResolvedValue(response);logout.mockResolvedValue();});
  afterEach(()=>{vi.unstubAllGlobals();});
  it('skips bootstrap for an explicitly signed-out marker',async()=>{
    const {result}=setup(); await waitFor(()=>expect(result.current.auth.isInitializing).toBe(false));
    expect(refresh).not.toHaveBeenCalled();
  });
  it('bootstraps exactly once under StrictMode and deletes legacy tokens',async()=>{
    knownUser();window.localStorage.setItem(AUTH_SESSION_STORAGE_KEY,'old-fixture');
    const {result}=setup();await waitFor(()=>expect(result.current.auth.user?.id).toBe(user.id));
    expect(refresh).toHaveBeenCalledTimes(1);expect(window.localStorage.getItem(AUTH_SESSION_STORAGE_KEY)).toBeNull();
    expect(Object.keys(JSON.parse(window.localStorage.getItem(AUTH_GENERATION_STORAGE_KEY)!))).toEqual(['version','generation','userId','logoutPending']);
    expect(window.localStorage.getItem(AUTH_GENERATION_STORAGE_KEY)?.includes(response.accessToken)).toBe(false);
  });
  it('attempts one bootstrap when there is no readable marker',async()=>{
    window.localStorage.clear();const {result}=setup();
    await waitFor(()=>expect(result.current.auth.isAuthenticated).toBe(true));expect(refresh).toHaveBeenCalledTimes(1);
  });
  it.each([500,503,'network','timeout'] as const)('keeps bootstrap retryable after %s',async(kind)=>{
    knownUser();const failure=typeof kind==='number'?new ApiRequestError(kind,null):new Error(kind);
    refresh.mockRejectedValueOnce(failure);
    const {result}=setup();await waitFor(()=>expect(result.current.auth.error).toBe(failure));
    expect(result.current.auth.isInitializing).toBe(false);
    expect(JSON.parse(window.localStorage.getItem(AUTH_GENERATION_STORAGE_KEY)!).userId).toBe(user.id);
    await act(async()=>{await result.current.auth.refreshTokens();});expect(result.current.auth.user?.id).toBe(user.id);
  });
  it('a bootstrap 401 clears identity without erasing another user',async()=>{
    knownUser();refresh.mockRejectedValueOnce(new ApiRequestError(401,null));
    const {result}=setup();await waitFor(()=>expect(result.current.auth.isInitializing).toBe(false));
    expect(result.current.auth.isAuthenticated).toBe(false);
    expect(JSON.parse(window.localStorage.getItem(AUTH_GENERATION_STORAGE_KEY)!).userId).toBeNull();
  });
  it.each([500,503,'network','timeout'] as const)('keeps a JSON request session on refresh %s',async(kind)=>{
    knownUser();const {result}=setup();await waitFor(()=>expect(result.current.auth.isAuthenticated).toBe(true));
    const failure=typeof kind==='number'?new ApiRequestError(kind,null):new Error(kind);
    refresh.mockRejectedValueOnce(failure);vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response('',{status:401})));
    await act(async()=>{await expect(result.current.client.request('/movies',{authenticated:true})).rejects.toBe(failure);});
    expect(result.current.auth.isAuthenticated).toBe(true);expect(result.current.auth.error).toBe(failure);
  });
  it('a JSON recovery 401 ends the session',async()=>{
    knownUser();const {result}=setup();await waitFor(()=>expect(result.current.auth.isAuthenticated).toBe(true));
    refresh.mockRejectedValueOnce(new ApiRequestError(401,null));vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response('',{status:401})));
    await act(async()=>{await expect(result.current.client.request('/movies',{authenticated:true})).rejects.toBeInstanceOf(ApiRequestError);});
    expect(result.current.auth.isAuthenticated).toBe(false);
  });
  it('signs in without persisting an access token and replaces identity',async()=>{
    const {result}=setup();await waitFor(()=>expect(result.current.auth.isInitializing).toBe(false));
    await act(async()=>{await result.current.auth.login({email:user.email,password:'fixture'});});
    expect(result.current.auth.user?.id).toBe(user.id);
    expect(window.localStorage.getItem(AUTH_GENERATION_STORAGE_KEY)?.includes(response.accessToken)).toBe(false);
    expect(window.sessionStorage.length).toBe(0);
  });
  it('pending logout survives remount and retries logout instead of refreshing',async()=>{
    knownUser();const first=setup();await waitFor(()=>expect(first.result.current.auth.isAuthenticated).toBe(true));
    logout.mockRejectedValueOnce(new Error('offline'));
    await act(async()=>{await expect(first.result.current.auth.logout()).rejects.toThrow('offline');});
    expect(first.result.current.auth.isAuthenticated).toBe(false);first.unmount();
    refresh.mockClear();const next=setup();await waitFor(()=>expect(logout).toHaveBeenCalledTimes(2));
    await waitFor(()=>expect(JSON.parse(window.localStorage.getItem(AUTH_GENERATION_STORAGE_KEY)!).logoutPending).toBe(false));
    expect(refresh).not.toHaveBeenCalled();expect(next.result.current.auth.isAuthenticated).toBe(false);
  });
  it('pending logout wake-ups send no requests; retries publish only confirmation', async () => {
    const postMessage = vi.fn();
    const channels: Array<{ onmessage: ((event: MessageEvent) => void) | null }> = [];
    vi.stubGlobal('BroadcastChannel', class {
      onmessage: ((event: MessageEvent) => void) | null = null;
      postMessage = postMessage;
      close() { /* No browser resources in this fixture. */ }
      constructor() { channels.push(this); }
    });
    knownUser();
    const { result } = setup();
    await waitFor(() => expect(result.current.auth.isAuthenticated).toBe(true));
    logout.mockRejectedValue(new Error('offline'));
    await act(async () => { await expect(result.current.auth.logout()).rejects.toThrow('offline'); });
    const marker = window.localStorage.getItem(AUTH_GENERATION_STORAGE_KEY);
    const publications = postMessage.mock.calls.length;

    for (let round = 0; round < 5; round += 1) {
      await act(async () => {
        window.dispatchEvent(new StorageEvent('storage', { key: AUTH_GENERATION_STORAGE_KEY }));
        for (const event of ['focus', 'pageshow']) window.dispatchEvent(new Event(event));
        document.dispatchEvent(new Event('visibilitychange'));
        for (const channel of channels) channel.onmessage?.(new MessageEvent('message', { data: 'changed' }));
      });
    }
    expect(logout).toHaveBeenCalledTimes(1);
    expect(window.localStorage.getItem(AUTH_GENERATION_STORAGE_KEY)).toBe(marker);
    expect(postMessage).toHaveBeenCalledTimes(publications);
    expect(result.current.auth.isAuthenticated).toBe(false);
    expect(screen.getByRole('alert')).toHaveTextContent('Sign-out is not confirmed.');

    await act(async () => { screen.getByRole('button', { name: 'Retry sign-out' }).click(); });
    expect(logout).toHaveBeenCalledTimes(2);
    expect(window.localStorage.getItem(AUTH_GENERATION_STORAGE_KEY)).toBe(marker);
    expect(postMessage).toHaveBeenCalledTimes(publications);

    logout.mockResolvedValueOnce();
    await act(async () => { screen.getByRole('button', { name: 'Retry sign-out' }).click(); });
    expect(logout).toHaveBeenCalledTimes(3);
    const confirmed = JSON.parse(window.localStorage.getItem(AUTH_GENERATION_STORAGE_KEY)!);
    expect(confirmed).toEqual({ ...JSON.parse(marker!), logoutPending: false });
    expect(postMessage).toHaveBeenCalledTimes(publications + 1);
  });
  it.each(['pageshow','focus','visibilitychange'])('reconciles another identity on %s without a channel',async(event)=>{
    knownUser();const {result}=setup();await waitFor(()=>expect(result.current.auth.isAuthenticated).toBe(true));
    refresh.mockResolvedValue({...response,user:{...user,id:'other'}});
    window.localStorage.setItem(AUTH_GENERATION_STORAGE_KEY,JSON.stringify({...emptyMarker(),generation:2,userId:'other'}));
    await act(async()=>{(event==='visibilitychange'?document:window).dispatchEvent(new Event(event));});
    await waitFor(()=>expect(result.current.auth.user?.id).toBe('other'));
  });
  it('checks persistent generation before sending protected requests even without events',async()=>{
    knownUser();const {result}=setup();await waitFor(()=>expect(result.current.auth.isAuthenticated).toBe(true));
    window.localStorage.setItem(AUTH_GENERATION_STORAGE_KEY,JSON.stringify({...emptyMarker(),generation:2,userId:'other'}));
    const fetch=vi.fn();vi.stubGlobal('fetch',fetch);
    await expect(result.current.client.request('/movies',{authenticated:true})).rejects.toBeInstanceOf(AuthenticationSupersededError);
    expect(fetch).not.toHaveBeenCalled();
  });
  describe('startup without persistent coordination', () => {
    it.each(['unreadable', 'unwritable'])('requires explicit sign-in after a failed logout and %s storage reload',
      async (storageFailure) => {
        const blockedStorage = {
          getItem() {
            if (storageFailure === 'unreadable') {
              throw new Error('Storage reads are blocked');
            }
            return null;
          },
          setItem() {
            if (storageFailure === 'unwritable') {
              throw new Error('Storage writes are blocked');
            }
          },
          removeItem() {}
        } as unknown as Storage;
        const first = setup(blockedStorage);
        await waitFor(() => expect(first.result.current.auth.isInitializing).toBe(false));
        expect(first.result.current.auth.isAuthenticated).toBe(false);
        expect(refresh).not.toHaveBeenCalled();

        await act(async () => {
          await first.result.current.auth.login({ email: user.email, password: 'fixture-password' });
          await first.result.current.auth.refreshTokens();
        });
        expect(first.result.current.auth.accessToken).toBe(response.accessToken);
        logout.mockRejectedValueOnce(new Error('Logout could not reach the server'));
        await act(async () => {
          await expect(first.result.current.auth.logout()).rejects.toThrow('could not reach the server');
        });
        first.unmount();
        refresh.mockClear();

        const reloaded = setup(blockedStorage);
        await waitFor(() => expect(reloaded.result.current.auth.isInitializing).toBe(false));
        expect(reloaded.result.current.auth.isAuthenticated).toBe(false);
        expect(refresh).not.toHaveBeenCalled();
        expect(screen.getByRole('note')).toHaveTextContent('Sign in again after a reload.');
      });
  });

  describe('cookie identity recovery', () => {
    it('accepts the cookie identity with a new generation when its recorded marker stays unchanged', async () => {
      knownUser();
      const cookieUser = { ...user, id: 'cookie-viewer', email: 'cookie@example.com' };
      refresh.mockResolvedValueOnce({ ...response, user: cookieUser });
      const { result } = setup();

      await waitFor(() => expect(result.current.auth.user?.id).toBe(cookieUser.id));
      expect(result.current.auth.isInitializing).toBe(false);
      expect(refresh).toHaveBeenCalledTimes(1);
      expect(JSON.parse(window.localStorage.getItem(AUTH_GENERATION_STORAGE_KEY)!)).toEqual({
        ...emptyMarker(), generation: 1, userId: cookieUser.id
      });
    });

    it('discards a cookie identity when another marker is published during the refresh', async () => {
      knownUser();
      const { result } = setup();
      await waitFor(() => expect(result.current.auth.isAuthenticated).toBe(true));
      const newerMarker = { ...emptyMarker(), generation: 2, userId: 'newer-viewer' };
      refresh.mockImplementationOnce(async () => {
        window.localStorage.setItem(AUTH_GENERATION_STORAGE_KEY, JSON.stringify(newerMarker));
        return { ...response, user: { ...user, id: 'stale-cookie-viewer' } };
      });

      await act(async () => {
        await expect(result.current.auth.refreshTokens()).rejects.toBeInstanceOf(AuthenticationSupersededError);
      });
      expect(JSON.parse(window.localStorage.getItem(AUTH_GENERATION_STORAGE_KEY)!)).toEqual(newerMarker);
      expect(result.current.auth.user?.id).not.toBe('stale-cookie-viewer');
    });

    it('finishes initialization and offers retry after three superseded restore attempts', async () => {
      knownUser();
      let generation = 0;
      refresh.mockImplementation(async () => {
        generation += 1;
        window.localStorage.setItem(AUTH_GENERATION_STORAGE_KEY, JSON.stringify({
          ...emptyMarker(), generation, userId: 'concurrent-viewer'
        }));
        return response;
      });
      const { result } = setup();

      await screen.findByRole('button', { name: 'Retry sign-in' });
      expect(refresh).toHaveBeenCalledTimes(3);
      expect(result.current.auth.isInitializing).toBe(false);
      expect(result.current.auth.isAuthenticated).toBe(false);
    });
  });
});
