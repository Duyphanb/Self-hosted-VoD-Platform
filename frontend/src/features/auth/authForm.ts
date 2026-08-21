import { ApiRequestError } from '../../lib/api/client';
import type { LoginRequest, RegisterRequest } from './authApi';

export type AuthFieldErrors<Field extends string> = Partial<Record<Field, string>>;

export interface AuthFormFailure<Field extends string> {
  message: string;
  fieldErrors: AuthFieldErrors<Field>;
}

type LoginField = keyof LoginRequest;
type RegistrationField = keyof RegisterRequest;

const EMAIL_MAX_LENGTH = 320;
const PASSWORD_MIN_LENGTH = 8;
const PASSWORD_MAX_LENGTH = 72;
const DISPLAY_NAME_MIN_LENGTH = 2;
const DISPLAY_NAME_MAX_LENGTH = 100;
const EMAIL_LOCAL_ATOM_PATTERN = /^[a-z0-9!#$%&'*+/=?^_`{|}~\u0080-\uFFFF-]+$/i;
const EMAIL_QUOTED_ATOM_PATTERN = /^[a-z0-9!#$%&'*.(),<>\[\]:; @+/=?^_`{|}~\u0080-\uFFFF-]$/i;
const EMAIL_DOMAIN_ATOM_PATTERN = /^[a-z0-9!#$%&'*+/=?^_`{|}~\u0080-\uFFFF-]$/i;
const IPV4_DOMAIN_PATTERN = /^\[[0-9]{1,3}(?:\.[0-9]{1,3}){3}\]$/;

export function validateLoginForm(request: LoginRequest): AuthFieldErrors<LoginField> {
  const errors: AuthFieldErrors<LoginField> = {};
  validateEmail(request.email, errors);
  if (request.password.trim().length === 0) {
    errors.password = 'Password is required.';
  }
  return errors;
}

export function validateRegistrationForm(
  request: RegisterRequest
): AuthFieldErrors<RegistrationField> {
  const errors: AuthFieldErrors<RegistrationField> = {};
  validateEmail(request.email, errors);

  if (request.password.trim().length === 0) {
    errors.password = 'Password is required.';
  } else if (request.password.length < PASSWORD_MIN_LENGTH) {
    errors.password = `Password must be at least ${PASSWORD_MIN_LENGTH} characters.`;
  } else if (request.password.length > PASSWORD_MAX_LENGTH) {
    errors.password = `Password must be ${PASSWORD_MAX_LENGTH} characters or fewer.`;
  }

  if (request.displayName.trim().length === 0) {
    errors.displayName = 'Display name is required.';
  } else if (request.displayName.length < DISPLAY_NAME_MIN_LENGTH) {
    errors.displayName = `Display name must be at least ${DISPLAY_NAME_MIN_LENGTH} characters.`;
  } else if (request.displayName.length > DISPLAY_NAME_MAX_LENGTH) {
    errors.displayName = `Display name must be ${DISPLAY_NAME_MAX_LENGTH} characters or fewer.`;
  }
  return errors;
}

export function authFormFailure<Field extends string>(
  failure: unknown,
  allowedFields: readonly Field[]
): AuthFormFailure<Field> {
  if (!(failure instanceof ApiRequestError) || !failure.apiError) {
    return {
      message: 'We could not complete the request. Please try again.',
      fieldErrors: {}
    };
  }

  const allowed = new Set<string>(allowedFields);
  const fieldErrors: AuthFieldErrors<Field> = {};
  for (const fieldError of failure.apiError.fieldErrors ?? []) {
    if (allowed.has(fieldError.field) && fieldErrors[fieldError.field as Field] === undefined) {
      fieldErrors[fieldError.field as Field] = fieldError.message;
    }
  }
  return { message: failure.apiError.message, fieldErrors };
}

export function resolvePostLoginPath(state: unknown): string {
  if (!state || typeof state !== 'object' || !('from' in state)) {
    return '/';
  }
  const from = (state as { from?: unknown }).from;
  if (typeof from === 'string') {
    return safeInternalPath(from);
  }
  if (!from || typeof from !== 'object') {
    return '/';
  }

  const candidate = from as { pathname?: unknown; search?: unknown; hash?: unknown };
  if (typeof candidate.pathname !== 'string') {
    return '/';
  }
  const search = typeof candidate.search === 'string' ? candidate.search : '';
  const hash = typeof candidate.hash === 'string' ? candidate.hash : '';
  if ((search && !search.startsWith('?')) || (hash && !hash.startsWith('#'))) {
    return '/';
  }
  return safeInternalPath(`${candidate.pathname}${search}${hash}`);
}

function validateEmail(email: string, errors: { email?: string }): void {
  if (email.trim().length === 0) {
    errors.email = 'Email is required.';
  } else if (email.length > EMAIL_MAX_LENGTH) {
    errors.email = `Email must be ${EMAIL_MAX_LENGTH} characters or fewer.`;
  } else if (!isBackendCompatibleEmail(email)) {
    errors.email = 'Enter a valid email address.';
  }
}

function isBackendCompatibleEmail(email: string): boolean {
  const splitPosition = email.lastIndexOf('@');
  if (splitPosition < 0) {
    return false;
  }
  const localPart = email.slice(0, splitPosition);
  const domainPart = email.slice(splitPosition + 1);
  return localPart.length <= 64
    && validLocalPart(localPart)
    && validEmailDomain(domainPart);
}

function validLocalPart(localPart: string): boolean {
  const segments: string[] = [];
  let current = '';
  let quoted = false;
  let escaped = false;
  for (const character of localPart) {
    if (escaped) {
      current += `\\${character}`;
      escaped = false;
    } else if (quoted && character === '\\') {
      escaped = true;
    } else if (character === '"') {
      quoted = !quoted;
      current += character;
    } else if (character === '.' && !quoted) {
      segments.push(current);
      current = '';
    } else {
      current += character;
    }
  }
  if (quoted || escaped) {
    return false;
  }
  segments.push(current);
  return segments.every(validLocalSegment);
}

function validLocalSegment(segment: string): boolean {
  if (EMAIL_LOCAL_ATOM_PATTERN.test(segment)) {
    return true;
  }
  if (segment.length < 3 || !segment.startsWith('"') || !segment.endsWith('"')) {
    return false;
  }
  const inside = segment.slice(1, -1);
  if (inside.length === 0) {
    return false;
  }
  for (let index = 0; index < inside.length; index += 1) {
    const character = inside[index];
    if (character === '\\') {
      index += 1;
      if (inside[index] !== '\\' && inside[index] !== '"') {
        return false;
      }
    } else if (!EMAIL_QUOTED_ATOM_PATTERN.test(character) || character === '"') {
      return false;
    }
  }
  return true;
}

function validEmailDomain(domain: string): boolean {
  if (domain.endsWith('.') || domain.length === 0) {
    return false;
  }
  if (IPV4_DOMAIN_PATTERN.test(domain)) {
    return true;
  }
  if (/^\[IPv6:.+\]$/i.test(domain)) {
    return validIpv6Address(domain.slice(6, -1));
  }

  const labels = domain.split('.');
  if (!labels.every(validDomainLabel)) {
    return false;
  }
  if (/^[\u0000-\u007f]+$/.test(domain)) {
    return domain.length <= 255 && labels.every((label) => label.length <= 63);
  }
  try {
    const asciiDomain = new URL(`http://${domain}`).hostname;
    return asciiDomain.length <= 255
      && asciiDomain.split('.').every((label) => label.length <= 63);
  } catch {
    return false;
  }
}

function validDomainLabel(label: string): boolean {
  if (label.length === 0 || label.startsWith('-') || label.endsWith('-')) {
    return false;
  }
  return [...label].every(
    (character) => character === '-' || EMAIL_DOMAIN_ATOM_PATTERN.test(character)
  );
}

function validIpv6Address(address: string): boolean {
  const addressWithoutZone = /^fe80:/i.test(address)
    ? address.replace(/%[0-9a-z]+$/i, '')
    : address;
  try {
    return new URL(`http://[${addressWithoutZone}]`).hostname.length > 0;
  } catch {
    return false;
  }
}

function safeInternalPath(path: string): string {
  if (!path.startsWith('/')
    || path.startsWith('//')
    || /[\\\u0000-\u001f\u007f]/.test(path)) {
    return '/';
  }
  const pathname = path.split(/[?#]/, 1)[0].replace(/\/+$/, '') || '/';
  const normalizedPathname = pathname.toLowerCase();
  if (normalizedPathname === '/login' || normalizedPathname === '/register') {
    return '/';
  }
  return path;
}
