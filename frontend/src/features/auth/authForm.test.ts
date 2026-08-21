import { describe, expect, it } from 'vitest';
import { ApiRequestError } from '../../lib/api/client';
import {
  authFormFailure,
  resolvePostLoginPath,
  validateLoginForm,
  validateRegistrationForm
} from './authForm';

describe('auth form validation', () => {
  it('matches the login request constraints', () => {
    const maximumDomain = Array.from({ length: 4 }, () => 'a'.repeat(63)).join('.');
    expect(validateLoginForm({ email: '', password: '   ' })).toEqual({
      email: 'Email is required.',
      password: 'Password is required.'
    });
    expect(validateLoginForm({ email: 'not-an-email', password: 'valid' })).toEqual({
      email: 'Enter a valid email address.'
    });
    expect(validateLoginForm({ email: `${'a'.repeat(64)}@${maximumDomain}`, password: 'valid' }))
      .toEqual({});
    expect(validateLoginForm({ email: `${'a'.repeat(65)}@${maximumDomain}`, password: 'valid' })).toEqual({
      email: 'Email must be 320 characters or fewer.'
    });
  });

  it.each([
    '.viewer@example.com',
    'viewer..name@example.com',
    'viewer@-example.com',
    'viewer@example.com.',
    `${'a'.repeat(65)}@example.com`
  ])('rejects %s consistently for login and registration', (email) => {
    expect(validateLoginForm({ email, password: 'valid' })).toEqual({
      email: 'Enter a valid email address.'
    });
    expect(validateRegistrationForm({
      email,
      password: 'valid-password',
      displayName: 'Viewer'
    })).toEqual({ email: 'Enter a valid email address.' });
  });

  it.each([
    'viewer@example',
    'viewer.name+vod@example.com',
    '"viewer name"@example.com',
    'viewer@[127.0.0.1]'
  ])('accepts backend-supported address %s', (email) => {
    expect(validateLoginForm({ email, password: 'valid' })).toEqual({});
  });

  it('matches the registration password and displayName boundaries', () => {
    expect(validateRegistrationForm({
      email: 'viewer@example.com',
      password: '1234567',
      displayName: 'V'
    })).toEqual({
      password: 'Password must be at least 8 characters.',
      displayName: 'Display name must be at least 2 characters.'
    });
    expect(validateRegistrationForm({
      email: 'viewer@example.com',
      password: 'x'.repeat(72),
      displayName: 'x'.repeat(100)
    })).toEqual({});
    expect(validateRegistrationForm({
      email: 'viewer@example.com',
      password: 'x'.repeat(73),
      displayName: 'x'.repeat(101)
    })).toEqual({
      password: 'Password must be 72 characters or fewer.',
      displayName: 'Display name must be 100 characters or fewer.'
    });
  });

  it('preserves recognized backend field errors and a safe summary', () => {
    const failure = authFormFailure(new ApiRequestError(400, {
      timestamp: '2026-08-21T00:00:00Z',
      status: 400,
      code: 'VALIDATION_ERROR',
      message: 'Request validation failed',
      fieldErrors: [
        { field: 'email', message: 'must be a well-formed email address' },
        { field: 'unknown', message: 'must not be shown as a field error' }
      ]
    }), ['email', 'password']);

    expect(failure).toEqual({
      message: 'Request validation failed',
      fieldErrors: { email: 'must be a well-formed email address' }
    });
  });
});

describe('post-login redirect validation', () => {
  it.each([
    [{ from: '/library?tab=recent#video' }, '/library?tab=recent#video'],
    [{ from: { pathname: '/library', search: '?tab=recent', hash: '#video' } }, '/library?tab=recent#video'],
    [{ from: '//evil.example/path' }, '/'],
    [{ from: '/\\evil.example/path' }, '/'],
    [{ from: 'https://evil.example/path' }, '/'],
    [{ from: '/login' }, '/'],
    [{ from: '/REGISTER' }, '/'],
    [{ from: '/register?next=/library' }, '/'],
    [{ from: 42 }, '/'],
    [undefined, '/']
  ])('maps %j to %s', (state, expected) => {
    expect(resolvePostLoginPath(state)).toBe(expected);
  });
});
