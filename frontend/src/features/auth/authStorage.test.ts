import { beforeEach, describe, expect, it } from 'vitest';
import { AUTH_GENERATION_STORAGE_KEY, AUTH_SESSION_STORAGE_KEY, createMarkerStore, emptyMarker } from './authStorage';

describe('non-secret session marker', () => {
  beforeEach(() => window.localStorage.clear());
  it('removes the legacy token session and generation before reading', () => {
    window.localStorage.setItem(AUTH_SESSION_STORAGE_KEY, 'legacy-sensitive-fixture');
    window.localStorage.setItem('vod.auth.generation.v1','12');
    createMarkerStore();
    expect(window.localStorage.length).toBe(0);
  });
  it('stores only the version, generation, identity and pending flag', () => {
    const store = createMarkerStore();
    store.write({ ...emptyMarker(), generation: 3, userId: 'viewer', logoutPending: true });
    expect(JSON.parse(window.localStorage.getItem(AUTH_GENERATION_STORAGE_KEY)!)).toEqual({
      version: 2, generation: 3, userId: 'viewer', logoutPending: true
    });
    expect(store.read().known).toBe(true);
  });
  it('drops extra fields from malformed or attacker-controlled marker data', () => {
    window.localStorage.setItem(AUTH_GENERATION_STORAGE_KEY,JSON.stringify({ ...emptyMarker(), extra: 'not a marker field' }));
    const store=createMarkerStore(); store.write(store.read().marker);
    expect(Object.keys(JSON.parse(window.localStorage.getItem(AUTH_GENERATION_STORAGE_KEY)!))).toEqual([
      'version','generation','userId','logoutPending'
    ]);
  });
  it.each(['{', '{}', '{"version":2,"generation":-1,"userId":null,"logoutPending":false}'])('treats invalid data as unknown: %s', raw => {
    window.localStorage.setItem(AUTH_GENERATION_STORAGE_KEY,raw);
    expect(createMarkerStore().read().known).toBe(false);
  });
  it('re-reads another tab marker on every snapshot and does not retain a deleted marker', () => {
    const store=createMarkerStore(); store.write({ ...emptyMarker(), userId:'one' });
    window.localStorage.setItem(AUTH_GENERATION_STORAGE_KEY,JSON.stringify({ ...emptyMarker(),generation:2,userId:'two' }));
    expect(store.read().marker.userId).toBe('two');
    window.localStorage.clear(); expect(store.read().marker.userId).toBeNull();
  });
  it('falls back to memory when storage is blocked, without throwing', () => {
    const blocked = { getItem() {throw new Error('blocked');}, setItem() {throw new Error('blocked');},
      removeItem() {throw new Error('blocked');} } as unknown as Storage;
    const store=createMarkerStore(blocked);
    store.write({ ...emptyMarker(), generation:1,userId:'viewer',logoutPending:true });
    expect(store.read()).toMatchObject({available:false,known:true,marker:{userId:'viewer',logoutPending:true}});
  });
});
