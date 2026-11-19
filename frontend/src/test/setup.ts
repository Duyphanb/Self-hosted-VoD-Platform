import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach } from 'vitest';
import { cleanup } from '@testing-library/react';
import { AUTH_GENERATION_STORAGE_KEY, emptyMarker } from '../features/auth/authStorage';

beforeEach(() => {
  window.localStorage.setItem(AUTH_GENERATION_STORAGE_KEY, JSON.stringify(emptyMarker()));
  // Each UI test models a new browser context/lock service. Protocol races use real browsers.
  let tail: Promise<unknown> = Promise.resolve();
  Object.defineProperty(navigator, 'locks', { configurable: true, value: {
    request: (_name: string, callback: () => Promise<unknown>) => {
      const result = tail.then(callback, callback); tail = result.catch(() => undefined); return result;
    }
  } });
});

afterEach(() => {
  cleanup();
  window.localStorage.clear();
});
