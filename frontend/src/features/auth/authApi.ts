import { ApiClient } from '../../lib/api/client';
import type { AuthResponse, UserProfile, UserRole } from '../../lib/api/types';

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
  displayName: string;
}

export async function login(client: ApiClient, request: LoginRequest): Promise<AuthResponse> {
  const response = await client.request<unknown>('/auth/login', {
    method: 'POST',
    json: request
  });
  return decodeAuthResponse(response);
}

export async function register(client: ApiClient, request: RegisterRequest): Promise<UserProfile> {
  const response = await client.request<unknown>('/auth/register', {
    method: 'POST',
    json: request
  });
  return decodeUserProfile(response);
}

export async function refresh(
  client: ApiClient,
  refreshToken: string,
  signal?: AbortSignal
): Promise<AuthResponse> {
  const response = await client.request<unknown>('/auth/refresh', {
    method: 'POST',
    json: { refreshToken },
    signal
  });
  return decodeAuthResponse(response);
}

export function logout(
  client: ApiClient,
  refreshToken: string,
  signal?: AbortSignal
): Promise<void> {
  return client.request('/auth/logout', {
    method: 'POST',
    json: { refreshToken },
    signal
  });
}

export class InvalidAuthResponseError extends Error {
  constructor(contractName: 'AuthResponse' | 'UserProfile') {
    super(`Server returned an invalid ${contractName}`);
    this.name = 'InvalidAuthResponseError';
  }
}

function decodeAuthResponse(value: unknown): AuthResponse {
  if (!value || typeof value !== 'object') {
    throw new InvalidAuthResponseError('AuthResponse');
  }
  const candidate = value as Record<string, unknown>;
  const user = tryDecodeUserProfile(candidate.user);
  if (!user
    || !nonEmptyString(candidate.accessToken)
    || !nonEmptyString(candidate.refreshToken)
    || !positiveInteger(candidate.expiresInSeconds)) {
    throw new InvalidAuthResponseError('AuthResponse');
  }
  return {
    user,
    accessToken: candidate.accessToken,
    refreshToken: candidate.refreshToken,
    expiresInSeconds: candidate.expiresInSeconds
  };
}

function decodeUserProfile(value: unknown): UserProfile {
  const user = tryDecodeUserProfile(value);
  if (!user) {
    throw new InvalidAuthResponseError('UserProfile');
  }
  return user;
}

function tryDecodeUserProfile(value: unknown): UserProfile | null {
  if (!value || typeof value !== 'object') {
    return null;
  }
  const candidate = value as Record<string, unknown>;
  if (!nonEmptyString(candidate.id)
    || !nonEmptyString(candidate.email)
    || typeof candidate.displayName !== 'string'
    || !Array.isArray(candidate.roles)
    || !candidate.roles.every(isUserRole)) {
    return null;
  }
  return {
    id: candidate.id,
    email: candidate.email,
    displayName: candidate.displayName,
    roles: [...candidate.roles]
  };
}

function isUserRole(value: unknown): value is UserRole {
  return value === 'ROLE_USER' || value === 'ROLE_ADMIN';
}

function nonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

function positiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
}
