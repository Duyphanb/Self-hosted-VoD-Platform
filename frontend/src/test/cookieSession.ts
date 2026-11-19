import { vi } from 'vitest';
import * as authApi from '../features/auth/authApi';
import { AUTH_GENERATION_STORAGE_KEY, type AuthSession } from '../features/auth/authStorage';

/** Bootstrap fixture: tokens stay in the mock's memory, never in browser storage. */
export function installCookieSession(session: AuthSession, storage: Storage): void {
  const previous = JSON.parse(storage.getItem(AUTH_GENERATION_STORAGE_KEY) ?? 'null');
  const generation = previous?.userId ? previous.generation + 1 : session.authGeneration;
  storage.setItem(AUTH_GENERATION_STORAGE_KEY, JSON.stringify({ version: 2,
    generation, userId: session.user.id, logoutPending: false }));
  const bootstrap = vi.spyOn(authApi, 'refresh');
  bootstrap.mockReset().mockImplementationOnce(async () => {
    bootstrap.mockRestore();
    return { user: session.user, accessToken: session.accessToken, expiresInSeconds: session.expiresInSeconds };
  });
}
