import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import type { PropsWithChildren } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import type { AuthResponse, UserProfile } from '../lib/api/types';
import { AuthProvider } from '../features/auth/AuthContext';
import { createAuthSession, writeAuthSession } from '../features/auth/authStorage';
import { AppLayout } from './AppLayout';

const regularUser: UserProfile = {
  id: '3fcf25c2-f096-4462-ae63-4ba4702a9078',
  email: 'viewer@example.com',
  displayName: 'Viewer',
  roles: ['ROLE_USER']
};

describe('AppLayout auth navigation', () => {
  it('shows guest links without protected or admin navigation', async () => {
    renderLayout();

    expect(await screen.findByRole('link', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Register' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Account' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument();
  });

  it('hides admin navigation from a regular authenticated user', async () => {
    persistUser(regularUser);
    renderLayout();

    expect(await screen.findByRole('link', { name: 'Account' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Sign in' })).not.toBeInTheDocument();
  });

  it('shows admin navigation only to ROLE_ADMIN', async () => {
    persistUser({ ...regularUser, roles: ['ROLE_USER', 'ROLE_ADMIN'] });
    renderLayout();

    expect(await screen.findByRole('link', { name: 'Admin' })).toHaveAttribute('href', '/admin');
  });

});

function renderLayout() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter future={{ v7_relativeSplatPath: true, v7_startTransition: true }}>
      <QueryClientProvider client={queryClient}>
        <AuthProvider redirectToLogin={vi.fn()}>
          <Routes>
            <Route element={<AppLayout />}>
              <Route index element={<div>Home</div>} />
            </Route>
          </Routes>
        </AuthProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
}

function persistUser(user: UserProfile) {
  const response: AuthResponse = {
    user,
    accessToken: 'access-token',
    refreshToken: 'refresh-token',
    expiresInSeconds: 900
  };
  writeAuthSession(createAuthSession(response), window.localStorage);
}
