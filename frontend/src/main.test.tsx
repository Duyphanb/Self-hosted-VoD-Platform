import { act, fireEvent, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createAuthSession, writeAuthSession } from './features/auth/authStorage';

const entry = vi.hoisted(() => ({
  roots: [] as Array<{ unmount(): void }>,
  routers: [] as Array<ReturnType<typeof import('react-router-dom')['createBrowserRouter']>>
}));

// Wrap the real boot APIs solely to dispose the actual main.tsx app after the test.
vi.mock('react-dom/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-dom/client')>();
  const createRoot: typeof actual.createRoot = (...args) => {
    const root = actual.createRoot(...args);
    entry.roots.push(root);
    return root;
  };
  return { ...actual, createRoot, default: { createRoot, hydrateRoot: actual.hydrateRoot } };
});
vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router-dom')>();
  const createBrowserRouter: typeof actual.createBrowserRouter = (...args) => {
    const router = actual.createBrowserRouter(...args);
    entry.routers.push(router);
    return router;
  };
  return { ...actual, createBrowserRouter };
});

describe('production main entry', () => {
  beforeEach(() => {
    document.body.innerHTML = '<div id="root"></div>';
    window.history.replaceState(null, '', '/admin/movies/new');
    writeAuthSession(createAuthSession({
      user: { id: '3fcf25c2-f096-4462-ae63-4ba4702a9078', email: 'admin@example.com', displayName: 'Admin', roles: ['ROLE_ADMIN'] },
      accessToken: 'access-token', refreshToken: 'refresh-token', expiresInSeconds: 900
    }), window.localStorage);
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockImplementation(async (input) => {
      const path = new URL(String(input), 'http://localhost').pathname;
      return new Response(JSON.stringify(path.endsWith('/genres') ? [] : {
        items: [], page: { page: 0, size: 20, totalElements: 0, totalPages: 0 }
      }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }));
  });
  afterEach(() => {
    act(() => entry.roots.splice(0).forEach((root) => root.unmount()));
    entry.routers.splice(0).forEach((router) => router.dispose());
    window.history.replaceState(null, '', '/');
    document.body.innerHTML = '';
    vi.unstubAllGlobals();
  });

  it('boots the actual main.tsx provider/router host and supports a dirty admin form', async () => {
    await act(async () => { await import('./main'); });
    expect(await screen.findByRole('heading', { level: 1, name: 'New movie' })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Unsaved in production' } });
    fireEvent.click(screen.getByRole('link', { name: 'Cancel' }));
    expect(await screen.findByRole('dialog', { name: 'Discard changes?' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Keep editing' }));
    expect(screen.getByLabelText('Title')).toHaveValue('Unsaved in production');
  }, 10_000);
});
