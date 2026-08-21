import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiClient } from '../../lib/api/client';
import { InvalidAuthResponseError, login, refresh, register } from './authApi';

const validUser = {
  id: '3fcf25c2-f096-4462-ae63-4ba4702a9078',
  email: 'viewer@example.com',
  displayName: 'Viewer',
  roles: ['ROLE_USER']
};

const validAuthResponse = {
  user: validUser,
  accessToken: 'access-token',
  refreshToken: 'refresh-token',
  expiresInSeconds: 900
};

describe('auth API response decoding', () => {
  const fetchMock = vi.fn<typeof fetch>();
  const client = new ApiClient('/api/v1', {
    getAuthenticationSnapshot: () => null,
    refreshAccessToken: vi.fn(),
    onAuthenticationFailure: vi.fn()
  });

  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('decodes login and refresh AuthResponse payloads', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(validAuthResponse)));

    await expect(login(client, {
      email: 'viewer@example.com',
      password: 'strong-password'
    })).resolves.toEqual(validAuthResponse);
    await expect(refresh(client, 'refresh-token')).resolves.toEqual(validAuthResponse);
  });

  it('rejects an incomplete AuthResponse before it reaches auth state', async () => {
    fetchMock.mockResolvedValue(jsonResponse({
      ...validAuthResponse,
      refreshToken: ''
    }));

    await expect(login(client, {
      email: 'viewer@example.com',
      password: 'strong-password'
    })).rejects.toBeInstanceOf(InvalidAuthResponseError);
  });

  it('decodes registration UserProfile and rejects unknown roles', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(validUser, 201))
      .mockResolvedValueOnce(jsonResponse({
        ...validUser,
        roles: ['ROLE_SUPERUSER']
      }, 201));

    const request = {
      email: 'viewer@example.com',
      password: 'strong-password',
      displayName: 'Viewer'
    };
    await expect(register(client, request)).resolves.toEqual(validUser);
    await expect(register(client, request)).rejects.toBeInstanceOf(InvalidAuthResponseError);
  });
});

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' }
  });
}
