import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { type PropsWithChildren, useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider, useAuth } from '../../auth/AuthContext';
import { createAuthSession, writeAuthSession } from '../../auth/authStorage';
import type { MovieFormErrors } from '../movieFormModel';
import { GenreEditor } from './GenreEditor';

const fetchMock = vi.fn<typeof fetch>();

describe('GenreEditor', () => {
  beforeEach(() => {
    fetchMock.mockReset();
    fetchMock.mockResolvedValue(jsonResponse([{ id: '26ccfbce-e5c8-4c7d-a1a8-fc98a7441371', name: 'Drama', slug: 'drama' }]));
    vi.stubGlobal('fetch', fetchMock);
  });
  afterEach(() => vi.unstubAllGlobals());

  it('loads genre suggestions and adds normalized chips with Enter or comma', async () => {
    const draftChanged = vi.fn();
    const { container } = renderEditor({ draftChanged });
    const input = await screen.findByLabelText('Add genre', {}, { timeout: 5_000 });
    await waitFor(() => expect(container.querySelector('datalist option')).toHaveAttribute('value', 'Drama'));
    fireEvent.change(input, { target: { value: '  Action  Thriller  ' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    fireEvent.change(input, { target: { value: 'Drama' } });
    fireEvent.keyDown(input, { key: ',' });

    expect(screen.getByTestId('genres-value')).toHaveTextContent('["Action Thriller","Drama"]');
    expect(input).toHaveValue('');
    expect(draftChanged).toHaveBeenCalledWith(true);
    expect(draftChanged).toHaveBeenLastCalledWith(false);
    expect(new URL(String(fetchMock.mock.calls[0][0]), 'http://localhost').pathname).toBe('/api/v1/admin/genres');
    expect(new Headers(fetchMock.mock.calls[0][1]?.headers).get('Authorization')).toBe('Bearer access-token');
  });

  it('removes the last chip with Backspace only when the input is empty and supports named removal', async () => {
    renderEditor({ initial: ['Drama', 'Comedy'] });
    const input = await screen.findByLabelText('Add genre');
    fireEvent.change(input, { target: { value: 'A' } });
    fireEvent.keyDown(input, { key: 'Backspace' });
    expect(screen.getByTestId('genres-value')).toHaveTextContent('["Drama","Comedy"]');
    fireEvent.change(input, { target: { value: '' } });
    fireEvent.keyDown(input, { key: 'Backspace' });
    expect(screen.queryByRole('button', { name: 'Remove genre Comedy' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Remove genre Drama' }));
    expect(screen.getByTestId('genres-value')).toHaveTextContent('[]');
  });

  it('rejects slug duplicates, empty slugs, controls, oversize names and an eleventh chip', async () => {
    renderEditor({ initial: ['Hai', ...Array.from({ length: 9 }, (_, index) => `Genre ${index}`)] });
    const input = await screen.findByLabelText('Add genre');
    const add = screen.getByRole('button', { name: 'Add genre' });
    for (const name of ['!!!', 'Bad\u0000Name', 'a'.repeat(101), 'Another']) {
      fireEvent.change(input, { target: { value: name } });
      fireEvent.click(add);
      expect(input).toHaveAttribute('aria-invalid', 'true');
    }
    fireEvent.click(screen.getByRole('button', { name: 'Remove genre Genre 0' }));
    fireEvent.change(input, { target: { value: 'Hài' } });
    fireEvent.click(add);
    expect(screen.getByRole('alert')).toHaveTextContent('This genre is already selected.');
    expect(JSON.parse(screen.getByTestId('genres-value').textContent ?? '[]')).toHaveLength(9);
  });

  it('associates indexed server errors with the input and preserves API text literally', async () => {
    renderEditor({ initial: ['<img src=x onerror=alert(1)>'], errors: { 'genres[0]': 'Invalid genre' } });
    const input = await screen.findByLabelText('Add genre');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription(/Invalid genre/);
    expect(screen.getByText('<img src=x onerror=alert(1)>')).toBeInTheDocument();
    expect(document.querySelector('img')).toBeNull();
  });

  it('handles a forbidden lookup through the page callback', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ timestamp: '2026-10-28T00:00:00Z', status: 403, code: 'FORBIDDEN', message: 'Forbidden' }, 403));
    const forbidden = vi.fn();
    renderEditor({ forbidden });
    await waitFor(() => expect(forbidden).toHaveBeenCalled());
  });
});

function renderEditor({ initial = [], errors = {}, draftChanged = vi.fn(), forbidden = vi.fn() }: {
  initial?: string[]; errors?: MovieFormErrors; draftChanged?: (dirty: boolean) => void; forbidden?: () => void;
} = {}) {
  writeAuthSession(createAuthSession({
    user: { id: '3fcf25c2-f096-4462-ae63-4ba4702a9078', email: 'admin@example.com', displayName: 'Admin', roles: ['ROLE_ADMIN'] },
    accessToken: 'access-token', refreshToken: 'refresh-token', expiresInSeconds: 900
  }), window.localStorage);
  function ControlledEditor() {
    const [value, setValue] = useState(initial);
    return <><GenreEditor errors={errors} onChange={setValue} onDraftChange={draftChanged} onForbidden={forbidden} value={value} /><output data-testid="genres-value">{JSON.stringify(value)}</output></>;
  }
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AuthProvider><Ready><ControlledEditor /></Ready></AuthProvider></QueryClientProvider>);
}

function Ready({ children }: PropsWithChildren) {
  return useAuth().isAuthenticated ? children : null;
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}
