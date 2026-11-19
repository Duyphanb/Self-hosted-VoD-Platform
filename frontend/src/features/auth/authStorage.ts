import type { AuthResponse } from '../../lib/api/types';

export const AUTH_SESSION_STORAGE_KEY = 'vod.auth.session.v1'; // Legacy, deleted on startup.
export const AUTH_GENERATION_STORAGE_KEY = 'vod.auth.marker.v2';
export interface AuthSession extends AuthResponse { accessTokenExpiresAt: number; authGeneration: number }
export interface SessionMarker { version: 2; generation: number; userId: string | null; logoutPending: boolean }
export function createAuthSession(response: AuthResponse, now = Date.now(), authGeneration = 0): AuthSession {
  return { ...response, accessTokenExpiresAt: now + response.expiresInSeconds * 1000, authGeneration };
}
export const emptyMarker = (): SessionMarker => ({ version: 2, generation: 0, userId: null, logoutPending: false });

/** No secrets enter this store, even when storage fails and the tab falls back to memory. */
export function createMarkerStore(provided?: Storage) {
  let storage: Storage | undefined;
  let available = true;
  let memory: SessionMarker = emptyMarker();
  let known = false;
  try {
    storage = provided ?? window.localStorage;
    storage.removeItem(AUTH_SESSION_STORAGE_KEY);
    storage.removeItem('vod.auth.generation.v1');
  }
  catch { available = false; }
  const read = () => {
    if (available && storage) {
      try {
        const raw = storage.getItem(AUTH_GENERATION_STORAGE_KEY);
        const parsed: unknown = raw ? JSON.parse(raw) : null;
        if (isMarker(parsed)) {
          memory = { version: 2, generation: parsed.generation, userId: parsed.userId, logoutPending: parsed.logoutPending };
          known = true;
        }
        else { memory = emptyMarker(); known = false; }
      } catch { available = false; }
    }
    return { marker: { ...memory }, known, available };
  };
  const write = (marker: SessionMarker) => {
    memory = { version: 2, generation: marker.generation, userId: marker.userId, logoutPending: marker.logoutPending };
    known = true;
    if (available && storage) {
      try { storage.setItem(AUTH_GENERATION_STORAGE_KEY, JSON.stringify(memory)); }
      catch { available = false; }
    }
    return read();
  };
  return { read, write };
}
function isMarker(value: unknown): value is SessionMarker {
  if (!value || typeof value !== 'object') return false;
  const m = value as Partial<SessionMarker>;
  return m.version === 2 && Number.isSafeInteger(m.generation) && (m.generation ?? -1) >= 0
    && (m.userId === null || typeof m.userId === 'string') && typeof m.logoutPending === 'boolean';
}
