import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { type PropsWithChildren, useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthProvider, useAuth } from '../../auth/AuthContext';
import { createAuthSession, writeAuthSession } from '../../auth/authStorage';
import type { MovieFormCredit, MovieFormErrors } from '../movieFormModel';
import { CreditsEditor } from './CreditsEditor';

const firstId = '26ccfbce-e5c8-4c7d-a1a8-fc98a7441371';
const secondId = '67be4de0-c697-45f7-9737-f201a4a79eda';
const fetchMock = vi.fn<typeof fetch>();

describe('CreditsEditor', () => {
  beforeEach(() => {
    fetchMock.mockReset();
    fetchMock.mockResolvedValue(peopleResponse());
    vi.stubGlobal('fetch', fetchMock);
  });
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

  it('debounces person search, supports keyboard selection and retains the selected person ID', async () => {
    renderEditor();
    fireEvent.click(await screen.findByRole('button', { name: 'Add credit' }, { timeout: 5_000 }));
    const input = screen.getByRole('combobox', { name: 'Person 1' });
    fireEvent.change(input, { target: { value: 'First' } });
    fireEvent.change(input, { target: { value: 'First Person' } });
    expect(fetchMock).not.toHaveBeenCalled();
    await screen.findByRole('option', { name: new RegExp(`First Person.*${firstId}`) });
    const url = new URL(String(fetchMock.mock.calls[0][0]), 'http://localhost');
    expect(url.pathname).toBe('/api/v1/admin/people');
    expect(url.searchParams.get('q')).toBe('First Person');
    expect(fetchMock).toHaveBeenCalledTimes(1);
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(credits()[0]).toMatchObject({ personId: firstId, name: 'First Person', role: 'ACTOR' });
    expect(input).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('button', { name: /Create new person/ })).not.toBeInTheDocument();
  });

  it('keeps credits in user-defined order and supports changing roles and removing rows', async () => {
    renderEditor({ initial: [credit(firstId, 'First Person'), credit(secondId, 'Second Person')] });
    await screen.findByRole('combobox', { name: 'Person 1' });
    fireEvent.change(screen.getByRole('combobox', { name: 'Role 1' }), { target: { value: 'DIRECTOR' } });
    fireEvent.click(screen.getByRole('button', { name: 'Move credit 2 up' }));
    expect(credits().map((row) => row.personId)).toEqual([secondId, firstId]);
    expect(credits()[1].role).toBe('DIRECTOR');
    fireEvent.click(screen.getByRole('button', { name: 'Remove credit 1' }));
    expect(credits()).toHaveLength(1);
    expect(credits()[0].personId).toBe(firstId);
  });

  it('browses people with a blank search and selects an existing ID from another results page', async () => {
    let releaseSecondPage: (response: Response) => void = () => undefined;
    const secondPage = new Promise<Response>((resolve) => { releaseSecondPage = resolve; });
    fetchMock.mockImplementation(async (input) => {
      const url = new URL(String(input), 'http://localhost');
      const page = Number(url.searchParams.get('page'));
      if (page === 1) return secondPage;
      return jsonResponse({
        items: [{ id: page === 0 ? firstId : secondId, name: 'Same Name', slug: page === 0 ? 'same-name' : 'same-name-2' }],
        page: { page, size: 20, totalElements: 21, totalPages: 2 }
      });
    });
    renderEditor();
    fireEvent.click(await screen.findByRole('button', { name: 'Add credit' }));
    const input = screen.getByRole('combobox', { name: 'Person 1' });
    fireEvent.focus(input);
    await screen.findByRole('option', { name: new RegExp(`Same Name.*${firstId}`) });
    expect(new URL(String(fetchMock.mock.calls[0][0]), 'http://localhost').searchParams.has('q')).toBe(false);
    input.focus();
    screen.getByRole('button', { name: 'Next people' }).focus();
    expect(input).toHaveAttribute('aria-expanded', 'true');
    fireEvent.click(screen.getByRole('button', { name: 'Next people' }));
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => new URL(String(url), 'http://localhost').searchParams.get('page') === '1')).toBe(true));
    // A slower second page must not be reset by the initial blank-query debounce timer.
    await act(async () => { await new Promise((resolve) => window.setTimeout(resolve, 350)); });
    expect(screen.queryByRole('option', { name: new RegExp(`Same Name.*${firstId}`) })).not.toBeInTheDocument();
    act(() => releaseSecondPage(jsonResponse({ items: [{ id: secondId, name: 'Same Name', slug: 'same-name-2' }], page: { page: 1, size: 20, totalElements: 21, totalPages: 2 } })));
    await screen.findByRole('option', { name: new RegExp(`Same Name.*${secondId}`) });
    expect(input).toHaveFocus();
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(credits()[0].personId).toBe(secondId);
    expect(postRequests()).toHaveLength(0);
  });

  it('searches names without an ASCII slug instead of silently excluding them', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ items: [{ id: firstId, name: '王明', slug: 'person' }], page: { page: 0, size: 20, totalElements: 1, totalPages: 1 } }));
    renderEditor();
    fireEvent.click(await screen.findByRole('button', { name: 'Add credit' }));
    const input = screen.getByRole('combobox', { name: 'Person 1' });
    fireEvent.change(input, { target: { value: '王明' } });
    await screen.findByRole('option', { name: new RegExp(`王明.*${firstId}`) });
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(credits()[0].personId).toBe(firstId);
  });

  it('blocks a duplicate person and role pair without changing existing IDs', async () => {
    renderEditor({ initial: [credit(firstId, 'First Person')] });
    fireEvent.click(await screen.findByRole('button', { name: 'Add credit' }));
    const input = screen.getByRole('combobox', { name: 'Person 2' });
    fireEvent.change(input, { target: { value: 'First' } });
    await screen.findByRole('option', { name: new RegExp(`First Person.*${firstId}`) });
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(screen.getByRole('alert')).toHaveTextContent('This person already has this role.');
    expect(credits()[1].personId).toBe('');
    expect(credits()[0].personId).toBe(firstId);
  });

  it('creates a person only after explicit action and guards against double submission', async () => {
    let complete: (response: Response) => void = () => undefined;
    const pending = new Promise<Response>((resolve) => { complete = resolve; });
    fetchMock.mockImplementation(async (_input, init) => init?.method === 'POST' ? pending : peopleResponse());
    renderEditor();
    fireEvent.click(await screen.findByRole('button', { name: 'Add credit' }));
    fireEvent.change(screen.getByRole('combobox', { name: 'Person 1' }), { target: { value: 'Brand New Person' } });
    expect(postRequests()).toHaveLength(0);
    const create = screen.getByRole('button', { name: "Create new person 'Brand New Person'" });
    fireEvent.click(create);
    fireEvent.click(create);
    expect(postRequests()).toHaveLength(1);
    expect(JSON.parse(String(postRequests()[0][1]?.body))).toEqual({ name: 'Brand New Person' });
    expect(screen.getByText(/saved immediately, even if you cancel/)).toBeInTheDocument();
    await act(async () => complete(jsonResponse({ id: secondId, name: 'Brand New Person', slug: 'brand-new-person' }, 201)));
    await waitFor(() => expect(credits()[0].personId).toBe(secondId));
  });

  it('rejects invalid person names locally and maps a create-person name error to that row', async () => {
    fetchMock.mockImplementation(async (_input, init) => init?.method === 'POST'
      ? jsonResponse({ timestamp: '2026-10-28T00:00:00Z', status: 400, code: 'VALIDATION_ERROR', message: 'Invalid person', fieldErrors: [{ field: 'name', message: 'Pick another name' }] }, 400)
      : peopleResponse());
    renderEditor();
    fireEvent.click(await screen.findByRole('button', { name: 'Add credit' }));
    const input = screen.getByRole('combobox', { name: 'Person 1' });
    fireEvent.change(input, { target: { value: 'a'.repeat(256) } });
    fireEvent.click(screen.getByRole('button', { name: /Create new person/ }));
    expect(postRequests()).toHaveLength(0);
    expect(screen.getByRole('alert')).toHaveTextContent(/255/);
    fireEvent.change(input, { target: { value: 'Valid Name' } });
    fireEvent.click(screen.getByRole('button', { name: /Create new person/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Pick another name');
    expect(input).toHaveAccessibleDescription('Pick another name');
  });

  it('associates indexed server errors with person and role controls and respects the credit limit', async () => {
    renderEditor({ initial: Array.from({ length: 50 }, (_, index) => ({ ...credit(firstId, `Name ${index}`), key: String(index) })), errors: {
      'people[0].personId': 'Select a different person', 'people[0].role': 'Choose a supported role'
    } });
    const input = await screen.findByRole('combobox', { name: 'Person 1' });
    expect(input).toHaveAccessibleDescription('Select a different person');
    expect(screen.getByRole('combobox', { name: 'Role 1' })).toHaveAccessibleDescription('Choose a supported role');
    expect(screen.getByRole('button', { name: 'Add credit' })).toBeDisabled();
  });

  it('clears a selected ID when its name is edited and closes search with Escape', async () => {
    renderEditor({ initial: [credit(firstId, 'First Person')] });
    const input = await screen.findByRole('combobox', { name: 'Person 1' });
    fireEvent.change(input, { target: { value: 'Second' } });
    expect(credits()[0].personId).toBe('');
    expect(input).toHaveAttribute('aria-expanded', 'true');
    fireEvent.keyDown(input, { key: 'Escape' });
    expect(input).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('signals forbidden lookup responses to the page and renders person names as plain text', async () => {
    const forbidden = vi.fn();
    fetchMock.mockResolvedValue(jsonResponse({ timestamp: '2026-10-28T00:00:00Z', status: 403, code: 'FORBIDDEN', message: 'Forbidden' }, 403));
    renderEditor({ initial: [credit(firstId, '<script>alert(1)</script>')], forbidden });
    const input = await screen.findByRole('combobox', { name: 'Person 1' });
    expect(input).toHaveValue('<script>alert(1)</script>');
    expect(document.querySelector('script')).toBeNull();
    fireEvent.change(input, { target: { value: 'Search' } });
    await waitFor(() => expect(forbidden).toHaveBeenCalled());
  });
});

function renderEditor({ initial = [], errors = {}, forbidden = vi.fn() }: { initial?: MovieFormCredit[]; errors?: MovieFormErrors; forbidden?: () => void } = {}) {
  writeAuthSession(createAuthSession({
    user: { id: '3fcf25c2-f096-4462-ae63-4ba4702a9078', email: 'admin@example.com', displayName: 'Admin', roles: ['ROLE_ADMIN'] },
    accessToken: 'access-token', refreshToken: 'refresh-token', expiresInSeconds: 900
  }), window.localStorage);
  function ControlledEditor() {
    const [value, setValue] = useState(initial);
    return <><CreditsEditor errors={errors} onChange={setValue} onForbidden={forbidden} value={value} /><output data-testid="credits-value">{JSON.stringify(value)}</output></>;
  }
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AuthProvider><Ready><ControlledEditor /></Ready></AuthProvider></QueryClientProvider>);
}

function Ready({ children }: PropsWithChildren) { return useAuth().isAuthenticated ? children : null; }
function credit(personId: string, name: string): MovieFormCredit { return { key: personId, personId, name, role: 'ACTOR' }; }
function credits(): MovieFormCredit[] { return JSON.parse(screen.getByTestId('credits-value').textContent ?? '[]') as MovieFormCredit[]; }
function postRequests() { return fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST'); }
function peopleResponse() {
  return jsonResponse({ items: [{ id: firstId, name: 'First Person', slug: 'first-person' }, { id: secondId, name: 'Second Person', slug: 'second-person' }], page: { page: 0, size: 20, totalElements: 2, totalPages: 1 } });
}
function jsonResponse(body: unknown, status = 200) { return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }); }
