import { beforeEach, describe, expect, it } from 'vitest';
import {
  AUTH_GENERATION_STORAGE_KEY,
  AUTH_SESSION_STORAGE_KEY,
  advanceAuthGeneration,
  clearAuthSession,
  createAuthSession,
  readAuthGeneration,
  readAuthSession,
  writeAuthSession
} from './authStorage';
import type { AuthResponse } from '../../lib/api/types';

const authResponse: AuthResponse = {
  user: {
    id: '3fcf25c2-f096-4462-ae63-4ba4702a9078',
    email: 'viewer@example.com',
    displayName: 'Viewer',
    roles: ['ROLE_USER']
  },
  accessToken: 'access-token',
  refreshToken: 'refresh-token',
  expiresInSeconds: 900
};

describe('auth session storage', () => {
  beforeEach(() => window.localStorage.clear());

  it('round-trips a versioned session and derives its access-token expiry', () => {
    const session = createAuthSession(authResponse, 1_000);

    writeAuthSession(session, window.localStorage);

    expect(session.accessTokenExpiresAt).toBe(901_000);
    expect(readAuthSession(window.localStorage)).toEqual(session);
  });

  it('fails closed and removes malformed or unknown-version storage', () => {
    window.localStorage.setItem(AUTH_SESSION_STORAGE_KEY, '{not-json');
    expect(readAuthSession(window.localStorage)).toBeNull();
    expect(window.localStorage.getItem(AUTH_SESSION_STORAGE_KEY)).toBeNull();

    window.localStorage.setItem(
      AUTH_SESSION_STORAGE_KEY,
      JSON.stringify({ version: 99, session: createAuthSession(authResponse) })
    );
    expect(readAuthSession(window.localStorage)).toBeNull();
    expect(window.localStorage.getItem(AUTH_SESSION_STORAGE_KEY)).toBeNull();
  });

  it('clears the persisted session', () => {
    writeAuthSession(createAuthSession(authResponse), window.localStorage);

    clearAuthSession(window.localStorage);

    expect(readAuthSession(window.localStorage)).toBeNull();
  });

  it('persists a monotonic logout generation independently of the session', () => {
    expect(readAuthGeneration(window.localStorage)).toBe(0);

    const firstGeneration = advanceAuthGeneration(window.localStorage);
    const secondGeneration = advanceAuthGeneration(window.localStorage);

    expect(firstGeneration).toBeGreaterThan(0);
    expect(secondGeneration).toBeGreaterThan(firstGeneration);
    expect(readAuthGeneration(window.localStorage)).toBe(secondGeneration);
    expect(window.localStorage.getItem(AUTH_GENERATION_STORAGE_KEY))
      .toBe(String(secondGeneration));
  });

  it('rejects a stored session without a valid auth generation', () => {
    const session = createAuthSession(authResponse);
    window.localStorage.setItem(AUTH_SESSION_STORAGE_KEY, JSON.stringify({
      version: 1,
      session: { ...session, authGeneration: -1 }
    }));

    expect(readAuthSession(window.localStorage)).toBeNull();
    expect(window.localStorage.getItem(AUTH_SESSION_STORAGE_KEY)).toBeNull();
  });
});
