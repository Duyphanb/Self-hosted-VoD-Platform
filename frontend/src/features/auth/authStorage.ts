import type { AuthResponse, UserProfile, UserRole } from '../../lib/api/types';

export const AUTH_SESSION_STORAGE_KEY = 'vod.auth.session.v1';
export const AUTH_GENERATION_STORAGE_KEY = 'vod.auth.generation.v1';
const STORAGE_VERSION = 1;

export interface AuthSession extends AuthResponse {
  accessTokenExpiresAt: number;
  authGeneration: number;
}

interface StoredAuthSession {
  version: typeof STORAGE_VERSION;
  session: AuthSession;
}

export function createAuthSession(
  response: AuthResponse,
  now = Date.now(),
  authGeneration = 0
): AuthSession {
  return {
    ...response,
    user: { ...response.user, roles: [...response.user.roles] },
    accessTokenExpiresAt: now + response.expiresInSeconds * 1_000,
    authGeneration
  };
}

export function readAuthGeneration(storage: Storage): number {
  try {
    const raw = storage.getItem(AUTH_GENERATION_STORAGE_KEY);
    if (raw === null) {
      return 0;
    }
    const generation = Number(raw);
    if (Number.isSafeInteger(generation) && generation >= 0) {
      return generation;
    }

    const recoveredGeneration = Date.now();
    storage.setItem(AUTH_GENERATION_STORAGE_KEY, String(recoveredGeneration));
    return recoveredGeneration;
  } catch {
    return Number.MAX_SAFE_INTEGER;
  }
}

export function advanceAuthGeneration(storage: Storage): number {
  const currentGeneration = readAuthGeneration(storage);
  const nextGeneration = currentGeneration < Number.MAX_SAFE_INTEGER
    ? Math.max(currentGeneration + 1, Date.now())
    : currentGeneration;
  try {
    storage.setItem(AUTH_GENERATION_STORAGE_KEY, String(nextGeneration));
  } catch {
    // The current tab still fails closed in memory when storage is unavailable.
  }
  return nextGeneration;
}

export function readAuthSession(storage: Storage): AuthSession | null {
  let raw: string | null;
  try {
    raw = storage.getItem(AUTH_SESSION_STORAGE_KEY);
  } catch {
    return null;
  }
  if (!raw) {
    return null;
  }

  try {
    const stored = JSON.parse(raw) as unknown;
    if (isStoredAuthSession(stored)) {
      return stored.session;
    }
  } catch {
    // Malformed authentication state must fail closed.
  }

  try {
    storage.removeItem(AUTH_SESSION_STORAGE_KEY);
  } catch {
    // Memory state still fails closed when browser storage is unavailable.
  }
  return null;
}

export function writeAuthSession(session: AuthSession, storage: Storage): void {
  const stored: StoredAuthSession = { version: STORAGE_VERSION, session };
  storage.setItem(AUTH_SESSION_STORAGE_KEY, JSON.stringify(stored));
}

export function clearAuthSession(storage: Storage): void {
  try {
    storage.removeItem(AUTH_SESSION_STORAGE_KEY);
  } catch {
    // Reads also fail closed when browser storage is unavailable.
  }
}

function isStoredAuthSession(value: unknown): value is StoredAuthSession {
  if (!value || typeof value !== 'object') {
    return false;
  }
  const stored = value as Partial<StoredAuthSession>;
  return stored.version === STORAGE_VERSION && isAuthSession(stored.session);
}

function isAuthSession(value: unknown): value is AuthSession {
  if (!value || typeof value !== 'object') {
    return false;
  }
  const session = value as Partial<AuthSession>;
  return isUserProfile(session.user)
    && nonEmptyString(session.accessToken)
    && nonEmptyString(session.refreshToken)
    && positiveNumber(session.expiresInSeconds)
    && positiveNumber(session.accessTokenExpiresAt)
    && nonNegativeInteger(session.authGeneration);
}

function isUserProfile(value: unknown): value is UserProfile {
  if (!value || typeof value !== 'object') {
    return false;
  }
  const user = value as Partial<UserProfile>;
  return nonEmptyString(user.id)
    && nonEmptyString(user.email)
    && typeof user.displayName === 'string'
    && Array.isArray(user.roles)
    && user.roles.every(isUserRole);
}

function isUserRole(value: unknown): value is UserRole {
  return value === 'ROLE_USER' || value === 'ROLE_ADMIN';
}

function nonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

function positiveNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0;
}

function nonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}
