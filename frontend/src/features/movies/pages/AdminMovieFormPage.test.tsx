import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../../../app/AppRoutes';
import type { AuthResponse, UserProfile } from '../../../lib/api/types';
import { AuthProvider } from '../../auth/AuthContext';
import { AUTH_SESSION_STORAGE_KEY, createAuthSession, writeAuthSession } from '../../auth/authStorage';
import { movieKeys, type MovieDetail } from '../moviesApi';

const movieId = '6f1c0f55-2d4a-4b64-9a43-1d0f3c7b8e21';
const actorId = '0b8f3a52-6a3e-4d0c-9a4b-3f2e1d0c9b8a';
const directorId = '1c9e4b63-7b4f-4e1d-8b5c-4a3f2e1d0c9b';
const admin: UserProfile = {
  id: '3fcf25c2-f096-4462-ae63-4ba4702a9078', email: 'admin@example.com', displayName: 'Admin', roles: ['ROLE_USER', 'ROLE_ADMIN']
};
const fetchMock = vi.fn<typeof fetch>();

describe('AdminMovieFormPage through the data router', () => {
  beforeEach(() => { fetchMock.mockReset(); vi.stubGlobal('fetch', fetchMock); serve(); });
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

  it('starts a new movie as Draft and suggests a Vietnamese slug until it is manually edited', async () => {
    renderForm();
    await screen.findByRole('heading', { level: 1, name: 'New movie' }, { timeout: 5_000 });
    expect(screen.getByLabelText('Title')).toHaveValue('');
    expect(screen.getByLabelText('Description')).toHaveValue('');
    expect(screen.getByLabelText('Release year (optional)')).toHaveValue('');
    expect(screen.getByLabelText('Maturity rating')).toHaveValue('');
    expect(screen.getByLabelText('Status')).toHaveValue('DRAFT');
    expect(screen.queryByLabelText('Person 1')).not.toBeInTheDocument();
    change('Title', 'Đất Rừng Phương Nam');
    expect(screen.getByLabelText('Slug')).toHaveValue('dat-rung-phuong-nam');
    change('Title', 'Đất Rừng Phương Nam II');
    expect(screen.getByLabelText('Slug')).toHaveValue('dat-rung-phuong-nam-ii');
    change('Slug', 'owner-selected-slug');
    change('Title', 'A different title');
    expect(screen.getByLabelText('Slug')).toHaveValue('owner-selected-slug');
    expect(mutations()).toHaveLength(0);
  });

  it('validates required fields, focuses a linked error summary and makes no mutation', async () => {
    renderForm();
    fireEvent.click(await screen.findByRole('button', { name: 'Create movie' }));
    const summary = errorSummary();
    expect(summary).toHaveFocus();
    expect(summary).toHaveTextContent('Title is required.');
    expect(summary).toHaveTextContent('Description is required.');
    expect(summary).toHaveTextContent('Choose 1–10 genres.');
    expect(screen.getByLabelText('Title')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Description')).toHaveAttribute('aria-invalid', 'true');
    fireEvent.click(within(summary).getByRole('link', { name: 'Title is required.' }));
    expect(screen.getByLabelText('Title')).toHaveFocus();
    expect(mutations()).toHaveLength(0);
  });

  it('shows a blur validation error without taking focus from the next field', async () => {
    renderForm();
    const title = await screen.findByLabelText('Title');
    title.focus();
    screen.getByLabelText('Slug').focus();
    fireEvent.blur(title);
    expect(screen.getByLabelText('Title')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Slug')).toHaveFocus();
    expect(screen.queryByRole('heading', { name: 'The movie could not be saved' })).not.toBeInTheDocument();
    change('Title', 'A valid title');
    expect(screen.getByLabelText('Title')).toHaveAttribute('aria-invalid', 'false');
  });

  it('creates a movie with a full replacement payload, invalidates movie caches and shows success', async () => {
    const { queryClient, router } = renderForm();
    queryClient.setQueryData(movieKeys.detail(movieId), movieDetail());
    await fillCreate({ title: '  Đất Rừng Phương Nam  ', description: '  A story.\nThe next line.  ' });
    fireEvent.click(screen.getByRole('button', { name: 'Create movie' }));

    expect(await screen.findByText('Movie created')).toHaveAttribute('role', 'status');
    expect(router.state.location.pathname).toBe('/admin/movies');
    expect(screen.queryByRole('dialog', { name: 'Discard changes?' })).not.toBeInTheDocument();
    const [[input, init]] = mutations();
    expect(new URL(String(input), 'http://localhost').pathname).toBe('/api/v1/movies');
    expect(init?.method).toBe('POST');
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer access-token');
    expect(JSON.parse(String(init?.body))).toEqual({
      title: 'Đất Rừng Phương Nam', slug: 'dat-rung-phuong-nam', description: 'A story.\nThe next line.',
      releaseYear: null, maturityRating: null, genres: ['Drama'], people: [], status: 'DRAFT'
    });
    expect(queryClient.getQueryState(movieKeys.detail(movieId))?.isInvalidated).toBe(true);
  });

  it('maps indexed server errors to controls and keeps unknown fields in the focused summary', async () => {
    serve((_url, init) => init?.method === 'PUT' ? apiError(400, 'VALIDATION_ERROR', [
      { field: 'title', message: 'Title was rejected' },
      { field: 'genres[0]', message: 'Genre was rejected' },
      { field: 'people[0].name', message: 'More than one person has this name' },
      { field: 'otherField', message: 'An unexpected field error' }
    ], 'form-request-90') : undefined);
    renderForm({ path: `/admin/movies/${movieId}/edit` });
    fireEvent.click(await screen.findByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(errorSummary()).toHaveFocus());
    const summary = errorSummary();
    expect(summary).toHaveTextContent('Title was rejected');
    expect(summary).toHaveTextContent('otherField: An unexpected field error');
    expect(summary).toHaveTextContent('Request ID: form-request-90');
    expect(screen.getByLabelText('Title')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Add genre')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Person 1')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Person 1')).toHaveAccessibleDescription('Several people have this name; pick one.');
    fireEvent.click(within(summary).getByRole('link', { name: 'Several people have this name; pick one.' }));
    expect(screen.getByLabelText('Person 1')).toHaveFocus();
  });

  it('maps a duplicate slug conflict to the slug field and lets the admin correct it', async () => {
    serve((_url, init) => init?.method === 'POST' ? apiError(409, 'MOVIE_SLUG_CONFLICT') : undefined);
    renderForm();
    await fillCreate();
    fireEvent.click(screen.getByRole('button', { name: 'Create movie' }));
    await waitFor(() => expect(screen.getByLabelText('Slug')).toHaveAttribute('aria-invalid', 'true'));
    expect(errorSummary()).toHaveFocus();
    expect(screen.getByLabelText('Slug')).toHaveAccessibleDescription('This slug is already used');
    act(() => screen.getByLabelText('Slug').focus());
    act(() => screen.getByLabelText('Title').focus());
    expect(screen.getByLabelText('Slug')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Slug')).toHaveAccessibleDescription('This slug is already used');
    expect(errorSummary()).toHaveTextContent('This slug is already used');
    change('Slug', 'a-unique-slug');
    expect(screen.getByLabelText('Slug')).toHaveAttribute('aria-invalid', 'false');
    expect(mutations()).toHaveLength(1);
  });

  it('retains a server error on another credit when focus or a different person changes', async () => {
    serve((_url, init) => init?.method === 'PUT' ? apiError(400, 'VALIDATION_ERROR', [
      { field: 'people[1].personId', message: 'must identify an existing person' }
    ]) : undefined);
    renderForm({ path: `/admin/movies/${movieId}/edit` });
    fireEvent.click(await screen.findByRole('button', { name: 'Save changes' }));
    const secondPerson = screen.getByLabelText('Person 2');
    await waitFor(() => expect(secondPerson).toHaveAttribute('aria-invalid', 'true'));

    act(() => screen.getByLabelText('Person 1').focus());
    act(() => screen.getByLabelText('Role 1').focus());
    expect(secondPerson).toHaveAttribute('aria-invalid', 'true');
    change('Person 1', 'A different person to select');
    expect(secondPerson).toHaveAttribute('aria-invalid', 'true');
    expect(secondPerson).toHaveAccessibleDescription('must identify an existing person');
    expect(errorSummary()).toHaveTextContent('must identify an existing person');
  });

  it('does not invalidate a newly added untouched credit or show a failed-save summary', async () => {
    renderForm();
    await fillCreate();
    const add = screen.getByRole('button', { name: 'Add credit' });
    act(() => add.focus());
    fireEvent.click(add);
    const person = screen.getByLabelText('Person 1');
    act(() => person.focus());
    expect(person).toHaveAttribute('aria-invalid', 'false');
    expect(screen.queryByRole('heading', { name: 'The movie could not be saved' })).not.toBeInTheDocument();
    expect(screen.queryByText('Pick an existing person or explicitly create a new person.')).not.toBeInTheDocument();
    change('Person 1', 'A new person');
    act(() => screen.getByRole('button', { name: "Create new person 'A new person'" }).focus());
    expect(screen.queryByRole('heading', { name: 'The movie could not be saved' })).not.toBeInTheDocument();
    expect(mutations()).toHaveLength(0);
  });

  it.each(['DRAFT', 'PUBLISHED'])('prefills every edit field and restores an archived movie to %s', async (status) => {
    serve((url, init) => url.pathname === `/api/v1/movies/${movieId}` && !init?.method
      ? json(movieDetail({ status: 'ARCHIVED' })) : undefined);
    const { router } = renderForm({ path: `/admin/movies/${movieId.toUpperCase()}/edit` });
    await screen.findByRole('button', { name: 'Save changes' });
    expect(screen.getByLabelText('Title')).toHaveValue('Existing movie');
    expect(screen.getByLabelText('Slug')).toHaveValue('existing-movie');
    expect(screen.getByLabelText('Description')).toHaveValue('An existing description.');
    expect(screen.getByLabelText('Release year (optional)')).toHaveValue('2020');
    expect(screen.getByLabelText('Maturity rating')).toHaveValue('T16');
    expect(screen.getByLabelText('Status')).toHaveValue('ARCHIVED');
    expect(screen.getByText('This movie is archived. Choose Draft or Published to restore it.')).toBeInTheDocument();
    expect(screen.getByLabelText('Person 1')).toHaveValue('Existing actor');
    expect(screen.getByLabelText('Role 1')).toHaveValue('ACTOR');
    expect(screen.getByLabelText('Person 2')).toHaveValue('Existing director');
    change('Title', 'Retitled movie');
    expect(screen.getByLabelText('Slug')).toHaveValue('existing-movie');
    change('Status', status);
    change('Release year (optional)', '');
    fireEvent.click(screen.getByRole('button', { name: 'Move credit 2 up' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('Changes saved')).toHaveAttribute('role', 'status');
    expect(router.state.location.pathname).toBe('/admin/movies');
    const [[input, init]] = mutations();
    expect(new URL(String(input), 'http://localhost').pathname).toBe(`/api/v1/movies/${movieId}`);
    expect(init?.method).toBe('PUT');
    expect(JSON.parse(String(init?.body))).toEqual({
      title: 'Retitled movie', slug: 'existing-movie', description: 'An existing description.', releaseYear: null,
      maturityRating: 'T16', genres: ['Drama', 'Mystery'],
      people: [{ personId: directorId, role: 'DIRECTOR' }, { personId: actorId, role: 'ACTOR' }], status
    });
  });

  it('sends only one request for repeated submit events while saving', async () => {
    const held = deferredResponse();
    serve((_url, init) => init?.method === 'POST' ? held.promise : undefined);
    renderForm();
    await fillCreate();
    const form = screen.getByRole('button', { name: 'Create movie' }).closest('form');
    if (!form) throw new Error('Movie form is missing');
    fireEvent.submit(form);
    fireEvent.submit(form);
    await waitFor(() => expect(mutations()).toHaveLength(1));
    expect(screen.getByRole('button', { name: 'Saving…' })).toBeDisabled();
    expect(screen.getByLabelText('Title')).toBeDisabled();
    held.release(json(movieDetail()));
    expect(await screen.findByText('Movie created')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it.each(['header', 'back', 'sign-out'] as const)('waits for an already-sent save when leaving through %s', async (departure) => {
    const held = deferredResponse();
    serve((_url, init) => init?.method === 'POST' ? held.promise : undefined);
    const { router, queryClient } = renderForm({ entries: ['/account', '/admin/movies/new'] });
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
    await fillCreate();
    fireEvent.click(screen.getByRole('button', { name: 'Create movie' }));
    if (departure === 'header') fireEvent.click(screen.getByRole('link', { name: 'Admin' }));
    else if (departure === 'sign-out') fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    else await act(async () => { await router.navigate(-1); });

    const dialog = await screen.findByRole('dialog', { name: 'Saving movie…' });
    expect(within(dialog).queryByRole('button', { name: /Discard/ })).not.toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/admin/movies/new');
    expect(screen.getByLabelText('Title')).toHaveValue('New movie title');
    expect(mutations()).toHaveLength(1);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Stay on page' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/admin/movies/new');
    held.release(json(movieDetail(), 201));
    expect(await screen.findByText('Movie created')).toBeInTheDocument();
    expect(invalidate).toHaveBeenCalledWith({ queryKey: movieKeys.all });
  });

  it('still invalidates movie queries when a sent save completes after a forced unmount', async () => {
    const held = deferredResponse();
    serve((_url, init) => init?.method === 'POST' ? held.promise : undefined);
    const view = renderForm();
    const invalidate = vi.spyOn(view.queryClient, 'invalidateQueries');
    await fillCreate();
    fireEvent.click(screen.getByRole('button', { name: 'Create movie' }));
    await waitFor(() => expect(mutations()).toHaveLength(1));
    const signal = mutations()[0][1]?.signal;
    view.unmount();
    expect(signal?.aborted ?? false).toBe(false);
    held.release(json(movieDetail(), 201));
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: movieKeys.all }));
  });

  it('searches for an existing person and sends their ID and selected role when creating a movie', async () => {
    serve((url) => url.pathname === '/api/v1/admin/people' ? json({
      items: [{ id: actorId, name: 'Existing actor', slug: 'existing-actor' }],
      page: { page: 0, size: 20, totalPages: 1, totalElements: 1 }
    }) : undefined);
    renderForm();
    await fillCreate();
    fireEvent.click(screen.getByRole('button', { name: 'Add credit' }));
    change('Person 1', 'Existing');
    const results = await screen.findByRole('listbox', { name: 'People for credit 1' });
    const person = await within(results).findByRole('button', { name: /Existing actor/ });
    fireEvent.click(person);
    change('Role 1', 'DIRECTOR');
    fireEvent.click(screen.getByRole('button', { name: 'Create movie' }));
    expect(await screen.findByText('Movie created')).toBeInTheDocument();
    expect(JSON.parse(String(mutations()[0][1]?.body)).people).toEqual([{ personId: actorId, role: 'DIRECTOR' }]);
    expect(fetchMock.mock.calls.some(([input]) => {
      const url = new URL(String(input), 'http://localhost');
      return url.pathname === '/api/v1/admin/people' && url.searchParams.get('q') === 'Existing';
    })).toBe(true);
    expect(mutations()).toHaveLength(1);
  });

  it('explicitly creates a person before saving the movie and submits the returned person ID', async () => {
    serve((url, init) => url.pathname === '/api/v1/admin/people' && init?.method === 'POST'
      ? json({ id: actorId, name: 'New person', slug: 'new-person' }, 201) : undefined);
    renderForm();
    await fillCreate();
    fireEvent.click(screen.getByRole('button', { name: 'Add credit' }));
    change('Person 1', 'New person');
    fireEvent.click(screen.getByRole('button', { name: "Create new person 'New person'" }));
    await waitFor(() => expect(screen.queryByRole('button', { name: /Create new person|Creating person/ })).not.toBeInTheDocument());
    fireEvent.click(screen.getByRole('button', { name: 'Create movie' }));
    expect(await screen.findByText('Movie created')).toBeInTheDocument();
    const requests = mutations();
    expect(requests).toHaveLength(2);
    expect(new URL(String(requests[0][0]), 'http://localhost').pathname).toBe('/api/v1/admin/people');
    expect(JSON.parse(String(requests[0][1]?.body))).toEqual({ name: 'New person' });
    expect(new URL(String(requests[1][0]), 'http://localhost').pathname).toBe('/api/v1/movies');
    expect(JSON.parse(String(requests[1][1]?.body)).people).toEqual([{ personId: actorId, role: 'ACTOR' }]);
  });

  it('handles detail loading before the edit form is available', async () => {
    const held = deferredResponse();
    serve((url) => url.pathname === `/api/v1/movies/${movieId}` ? held.promise : undefined);
    renderForm({ path: `/admin/movies/${movieId}/edit` });
    expect(await screen.findByRole('status')).toHaveTextContent('Loading movie…');
    expect(screen.queryByLabelText('Title')).not.toBeInTheDocument();
    held.release(json(movieDetail()));
    expect(await screen.findByLabelText('Title')).toHaveValue('Existing movie');
  });

  it('keeps an unsaved edit draft through a failed background refresh and a successful Retry', async () => {
    let failRefresh = false;
    serve((url, init) => url.pathname === `/api/v1/movies/${movieId}` && !init?.method && failRefresh
      ? apiError(500, 'INTERNAL_ERROR') : undefined);
    const { queryClient } = renderForm({ path: `/admin/movies/${movieId}/edit` });
    await screen.findByLabelText('Title');
    change('Title', 'An unsaved title');
    change('Description', 'An unsaved description.');
    failRefresh = true;
    await act(async () => { await queryClient.refetchQueries({ queryKey: movieKeys.detail(movieId), exact: true }); });
    expect(await screen.findByRole('heading', { name: 'Movie could not be refreshed; your changes are kept' })).toBeInTheDocument();
    expect(screen.getByLabelText('Title')).toHaveValue('An unsaved title');
    expect(screen.getByLabelText('Description')).toHaveValue('An unsaved description.');
    failRefresh = false;
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Movie could not be refreshed; your changes are kept' })).not.toBeInTheDocument());
    expect(screen.getByLabelText('Title')).toHaveValue('An unsaved title');
    expect(screen.getByLabelText('Description')).toHaveValue('An unsaved description.');
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
  });

  it('fetches after entering Edit instead of using cached public metadata, even when the cache is fresh', async () => {
    const freshResponse = deferredResponse();
    const freshMovie = movieDetail({ description: 'Description changed on the server.', genres: ['Comedy'] });
    let detailReads = 0;
    serve((url, init) => {
      if (url.pathname === `/api/v1/movies/${movieId}` && !init?.method) {
        detailReads += 1;
        return detailReads === 1 ? json(movieDetail()) : freshResponse.promise;
      }
      if (init?.method === 'PUT') return json(movieDetail({ ...freshMovie, title: 'Retitled after a fresh GET' }));
      return undefined;
    });
    const { queryClient } = renderForm({ path: `/movies/${movieId}` });
    await screen.findByRole('heading', { level: 1, name: 'Existing movie' });
    queryClient.setQueryDefaults(movieKeys.detail(movieId), { staleTime: Infinity });
    fireEvent.click(screen.getByRole('link', { name: 'Edit movie' }));
    await waitFor(() => expect(detailReads).toBe(2));
    expect(screen.queryByLabelText('Title')).not.toBeInTheDocument();
    expect(screen.getByText('Loading movie…')).toBeInTheDocument();
    freshResponse.release(json(freshMovie));
    expect(await screen.findByLabelText('Description')).toHaveValue(freshMovie.description);
    expect(screen.getByRole('button', { name: 'Remove genre Comedy' })).toBeInTheDocument();
    change('Title', 'Retitled after a fresh GET');
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('Changes saved')).toBeInTheDocument();
    expect(JSON.parse(String(mutations()[0][1]?.body))).toMatchObject({ description: freshMovie.description, genres: ['Comedy'] });
  });

  it('reopens Edit with Archived status after Cancel and Archive, without republishing on a metadata save', async () => {
    let serverMovie = movieDetail();
    serve((url, init) => {
      if (url.pathname === '/api/v1/admin/movies') return adminPage(serverMovie);
      if (url.pathname !== `/api/v1/movies/${movieId}`) return undefined;
      if (init?.method === 'DELETE') {
        serverMovie = { ...serverMovie, status: 'ARCHIVED' };
        return new Response(null, { status: 204 });
      }
      if (init?.method === 'PUT') {
        serverMovie = { ...serverMovie, ...JSON.parse(String(init.body)), people: serverMovie.people };
      }
      return json(serverMovie);
    });
    renderForm({ path: `/admin/movies/${movieId}/edit` });
    await screen.findByLabelText('Title');
    fireEvent.click(screen.getByRole('link', { name: 'Cancel' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Archive Existing movie' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Archive' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    fireEvent.click(await screen.findByRole('link', { name: 'Edit Existing movie' }));
    expect(await screen.findByLabelText('Status')).toHaveValue('ARCHIVED');
    expect(screen.getByText('This movie is archived. Choose Draft or Published to restore it.')).toBeInTheDocument();
    change('Description', 'Metadata update while the movie stays archived.');
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('Changes saved')).toBeInTheDocument();
    expect(JSON.parse(String(mutations()[0][1]?.body)).status).toBe('ARCHIVED');
    expect(serverMovie.status).toBe('ARCHIVED');
  });

  it('caches the saved detail before a cancelled refetch and waits for a fresh GET on reopening', async () => {
    let serverMovie = movieDetail();
    let saved = false;
    const delayedReads: ReturnType<typeof deferredResponse>[] = [];
    serve((url, init) => {
      if (url.pathname === '/api/v1/admin/movies') return adminPage(serverMovie);
      if (url.pathname !== `/api/v1/movies/${movieId}`) return undefined;
      if (init?.method === 'PUT') {
        serverMovie = { ...serverMovie, ...JSON.parse(String(init.body)), people: serverMovie.people };
        saved = true;
        return json(serverMovie);
      }
      if (saved) {
        const response = deferredResponse();
        delayedReads.push(response);
        return response.promise;
      }
      return json(serverMovie);
    });
    const { queryClient } = renderForm({ path: `/admin/movies/${movieId}/edit` });
    await screen.findByLabelText('Title');
    change('Title', 'The saved title');
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('Changes saved')).toBeInTheDocument();
    expect(queryClient.getQueryData<MovieDetail>(movieKeys.detail(movieId))?.title).toBe('The saved title');
    const previousReads = delayedReads.length;
    fireEvent.click(await screen.findByRole('link', { name: 'Edit The saved title' }));
    await waitFor(() => expect(delayedReads.length).toBeGreaterThan(previousReads));
    expect(screen.queryByLabelText('Title')).not.toBeInTheDocument();
    for (const response of delayedReads) response.release(json(serverMovie));
    expect(await screen.findByLabelText('Title')).toHaveValue('The saved title');
    expect(queryClient.getQueryData<MovieDetail>(movieKeys.detail(movieId))?.title).toBe('The saved title');
  });

  it('keeps the initial archived status and its option when a background refresh returns Published', async () => {
    let currentStatus: MovieDetail['status'] = 'ARCHIVED';
    serve((url, init) => url.pathname === `/api/v1/movies/${movieId}` && !init?.method
      ? json(movieDetail({ status: currentStatus })) : undefined);
    const { queryClient } = renderForm({ path: `/admin/movies/${movieId}/edit` });
    await screen.findByLabelText('Title');
    change('Title', 'Unsaved archived title');
    currentStatus = 'PUBLISHED';
    await act(async () => { await queryClient.refetchQueries({ queryKey: movieKeys.detail(movieId), exact: true }); });
    await waitFor(() => expect(queryClient.getQueryData<MovieDetail>(movieKeys.detail(movieId))?.status).toBe('PUBLISHED'));
    expect(screen.getByLabelText('Title')).toHaveValue('Unsaved archived title');
    const status = screen.getByLabelText('Status');
    expect(status).toHaveValue('ARCHIVED');
    expect(within(status).getByRole('option', { name: 'Archived (current)' })).toBeInTheDocument();
    expect(screen.getByText('This movie is archived. Choose Draft or Published to restore it.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('Changes saved')).toBeInTheDocument();
    expect(JSON.parse(String(mutations()[0][1]?.body))).toMatchObject({ title: 'Unsaved archived title', status: 'ARCHIVED' });
  });

  it('rejects an invalid edit UUID without calling any API', async () => {
    renderForm({ path: '/admin/movies/not-a-uuid/edit' });
    expect(await screen.findByRole('heading', { name: 'Movie not found' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to movies' })).toHaveAttribute('href', '/admin/movies');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it.each([400, 404])('shows movie not found when the detail API responds %s', async (status) => {
    serve((url) => url.pathname === `/api/v1/movies/${movieId}` ? apiError(status, 'MOVIE_NOT_FOUND') : undefined);
    renderForm({ path: `/admin/movies/${movieId}/edit` });
    expect(await screen.findByRole('heading', { name: 'Movie not found' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Title')).not.toBeInTheDocument();
  });

  it('shows Forbidden when detail access is denied by the server', async () => {
    serve((url) => url.pathname === `/api/v1/movies/${movieId}` ? apiError(403, 'FORBIDDEN') : undefined);
    renderForm({ path: `/admin/movies/${movieId}/edit` });
    expect(await screen.findByRole('heading', { name: 'Access forbidden' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument();
  });

  it('shows Forbidden when saving is denied, without an unsaved changes prompt', async () => {
    serve((_url, init) => init?.method === 'POST' ? apiError(403, 'FORBIDDEN') : undefined);
    renderForm();
    await fillCreate();
    fireEvent.click(screen.getByRole('button', { name: 'Create movie' }));
    expect(await screen.findByRole('heading', { name: 'Access forbidden' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('shows Movie not found when the edit PUT reports that the movie disappeared', async () => {
    serve((_url, init) => init?.method === 'PUT' ? apiError(404, 'MOVIE_NOT_FOUND') : undefined);
    renderForm({ path: `/admin/movies/${movieId}/edit` });
    fireEvent.click(await screen.findByRole('button', { name: 'Save changes' }));
    expect(await screen.findByRole('heading', { name: 'Movie not found' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Title')).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'The movie could not be saved' })).not.toBeInTheDocument();
  });

  it('shows Forbidden when explicit person creation is denied by the server', async () => {
    serve((url, init) => url.pathname === '/api/v1/admin/people' && init?.method === 'POST'
      ? apiError(403, 'FORBIDDEN') : undefined);
    renderForm();
    fireEvent.click(await screen.findByRole('button', { name: 'Add credit' }));
    change('Person 1', 'Someone');
    fireEvent.click(screen.getByRole('button', { name: "Create new person 'Someone'" }));
    expect(await screen.findByRole('heading', { name: 'Access forbidden' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Title')).not.toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('surfaces a forbidden genre editor request as Forbidden for the whole page', async () => {
    serve((url) => url.pathname === '/api/v1/admin/genres' ? apiError(403, 'FORBIDDEN') : undefined);
    renderForm();
    expect(await screen.findByRole('heading', { name: 'Access forbidden' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Title')).not.toBeInTheDocument();
  });

  it('surfaces a forbidden people search as Forbidden for the whole page', async () => {
    serve((url) => url.pathname === '/api/v1/admin/people' ? apiError(403, 'FORBIDDEN') : undefined);
    renderForm();
    fireEvent.click(await screen.findByRole('button', { name: 'Add credit' }));
    change('Person 1', 'Someone');
    expect(await screen.findByRole('heading', { name: 'Access forbidden' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('registers beforeunload only while there are unsaved changes', async () => {
    renderForm();
    await screen.findByLabelText('Title');
    const clean = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(clean);
    expect(clean.defaultPrevented).toBe(false);
    change('Title', 'Unsaved movie');
    const dirty = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(dirty);
    expect(dirty.defaultPrevented).toBe(true);
    change('Title', '');
    const reverted = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(reverted);
    expect(reverted.defaultPrevented).toBe(false);
  });

  it('warns on Cancel, traps focus, keeps edits on Escape and discards only after confirmation', async () => {
    const { router } = renderForm();
    await screen.findByLabelText('Title');
    change('Title', 'Keep this draft');
    const cancel = screen.getByRole('link', { name: 'Cancel' });
    cancel.focus();
    fireEvent.click(cancel);
    const dialog = await screen.findByRole('dialog', { name: 'Discard changes?' });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    const keep = within(dialog).getByRole('button', { name: 'Keep editing' });
    const discard = within(dialog).getByRole('button', { name: 'Discard changes' });
    expect(keep).toHaveFocus();
    fireEvent.keyDown(keep, { key: 'Tab', shiftKey: true });
    expect(discard).toHaveFocus();
    fireEvent.keyDown(discard, { key: 'Tab' });
    expect(keep).toHaveFocus();
    act(() => screen.getByLabelText('Title').focus());
    expect(keep).toHaveFocus();
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Title')).toHaveValue('Keep this draft');
    expect(cancel).toHaveFocus();
    fireEvent.click(cancel);
    fireEvent.click(await screen.findByRole('button', { name: 'Discard changes' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/admin/movies'));
    expect(await screen.findByRole('heading', { level: 1, name: 'Movies' })).toBeInTheDocument();
  });

  it('warns for header navigation and allows the confirmed destination', async () => {
    const { router } = renderForm();
    await screen.findByLabelText('Title');
    change('Title', 'Unsaved movie');
    const nav = screen.getByRole('navigation', { name: 'Primary navigation' });
    fireEvent.click(within(nav).getByRole('link', { name: 'Movies' }));
    expect(await screen.findByRole('dialog', { name: 'Discard changes?' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Discard changes' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/movies'));
    expect(screen.queryByLabelText('Title')).not.toBeInTheDocument();
  });

  it('asks before a deliberate sign-out discards a draft and does not log out when editing continues', async () => {
    serve((url) => url.pathname === '/api/v1/auth/logout' ? new Response(null, { status: 204 }) : undefined);
    renderForm();
    await screen.findByLabelText('Title');
    change('Title', 'Keep this draft until sign-out is confirmed');
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    const dialog = await screen.findByRole('dialog', { name: 'Discard changes?' });
    const logoutRequests = () => fetchMock.mock.calls.filter(([input]) => new URL(String(input), 'http://localhost').pathname === '/api/v1/auth/logout');
    expect(logoutRequests()).toHaveLength(0);
    await act(async () => { fireEvent.click(within(dialog).getByRole('button', { name: 'Keep editing' })); });
    expect(screen.getByLabelText('Title')).toHaveValue('Keep this draft until sign-out is confirmed');
    expect(logoutRequests()).toHaveLength(0);
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Discard changes' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument();
    expect(logoutRequests()).toHaveLength(1);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('blocks browser Back while dirty and proceeds after the admin confirms', async () => {
    const { router } = renderForm({ entries: ['/account', '/admin/movies/new'] });
    await screen.findByLabelText('Title');
    change('Title', 'Unsaved movie');
    await act(async () => { await router.navigate(-1); });
    expect(await screen.findByRole('dialog', { name: 'Discard changes?' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Keep editing' }));
    expect(router.state.location.pathname).toBe('/admin/movies/new');
    expect(screen.getByLabelText('Title')).toHaveValue('Unsaved movie');
    await act(async () => { await router.navigate(-1); });
    fireEvent.click(await screen.findByRole('button', { name: 'Discard changes' }));
    expect(await screen.findByRole('heading', { name: 'Account' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/account');
  });

  it('leaves a clean form without a discard prompt', async () => {
    const { router } = renderForm();
    fireEvent.click(await screen.findByRole('link', { name: 'Cancel' }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/admin/movies'));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it.each(['new', 'edit'] as const)('returns the %s form to its filtered list and replaces the submitted history entry', async (mode) => {
    const listPath = '/admin/movies?status=PUBLISHED&page=2&sort=title';
    serve((url, init) => url.pathname === '/api/v1/admin/movies'
      ? adminPage(movieDetail(), 1)
      : init?.method === 'PUT' ? json(movieDetail({ title: 'Saved from filtered list' })) : undefined);
    const { router } = renderForm({ entries: ['/account', listPath] });
    fireEvent.click(await screen.findByRole('link', { name: mode === 'new' ? 'New movie' : 'Edit Existing movie' }));
    expect(await screen.findByRole('link', { name: 'Cancel' })).toHaveAttribute('href', listPath);
    if (mode === 'new') await fillCreate();
    else change('Title', 'Saved from filtered list');
    fireEvent.click(screen.getByRole('button', { name: mode === 'new' ? 'Create movie' : 'Save changes' }));
    expect(await screen.findByText(mode === 'new' ? 'Movie created' : 'Changes saved')).toBeInTheDocument();
    expect(`${router.state.location.pathname}${router.state.location.search}`).toBe(listPath);
    expect(screen.getByRole('heading', { level: 1, name: 'Movies' })).toHaveFocus();
    await act(async () => { await router.navigate(-1); });
    expect(`${router.state.location.pathname}${router.state.location.search}`).toBe(listPath);
    expect(screen.queryByLabelText('Title')).not.toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('does not silently drop a typed genre that has not been added before saving', async () => {
    renderForm();
    await fillCreate();
    change('Add genre', 'Uncommitted genre');
    fireEvent.click(screen.getByRole('button', { name: 'Create movie' }));
    expect(errorSummary()).toHaveTextContent('Add or clear the genre you typed before saving.');
    expect(mutations()).toHaveLength(0);
  });

  it('drops the previous admin draft when another admin signs in', async () => {
    renderForm();
    await screen.findByLabelText('Title');
    change('Title', 'First admin private draft');
    const otherAdmin = { ...admin, id: 'b5f7c0f4-1d2e-4c3b-9a8f-7e6d5c4b3a21', displayName: 'Another admin' };
    persist(otherAdmin, 'other');
    act(() => window.dispatchEvent(new StorageEvent('storage', { key: AUTH_SESSION_STORAGE_KEY })));
    await waitFor(() => expect(screen.getByLabelText('Title')).toHaveValue(''));
    expect(screen.getByLabelText('Slug')).toHaveValue('');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

function renderForm({ path = '/admin/movies/new', entries, user = admin }: { path?: string; entries?: string[]; user?: UserProfile } = {}) {
  persist(user);
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter([{
    path: '*',
    element: <QueryClientProvider client={queryClient}><AuthProvider redirectToLogin={vi.fn()}><AppRoutes /></AuthProvider></QueryClientProvider>
  }], { initialEntries: entries ?? [path], initialIndex: entries ? entries.length - 1 : 0, future: { v7_relativeSplatPath: true } });
  const view = render(<RouterProvider future={{ v7_startTransition: true }} router={router} />);
  return { ...view, router, queryClient };
}

async function fillCreate({ title = 'New movie title', description = 'A description.' }: { title?: string; description?: string } = {}) {
  await screen.findByLabelText('Title');
  change('Title', title);
  change('Description', description);
  change('Add genre', 'Drama');
  fireEvent.click(screen.getByRole('button', { name: 'Add genre' }));
}

function change(label: string, value: string) { fireEvent.change(screen.getByLabelText(label), { target: { value } }); }
function errorSummary(): HTMLElement {
  const summary = screen.getByRole('heading', { name: 'The movie could not be saved' }).closest<HTMLElement>('[role="alert"]');
  if (!summary) throw new Error('Error summary is missing');
  return summary;
}
function mutations() { return fetchMock.mock.calls.filter(([, init]) => ['POST', 'PUT'].includes(init?.method ?? 'GET')); }
function persist(user: UserProfile, prefix = '') {
  const response: AuthResponse = { user, accessToken: `${prefix ? `${prefix}-` : ''}access-token`, refreshToken: `${prefix ? `${prefix}-` : ''}refresh-token`, expiresInSeconds: 900 };
  writeAuthSession(createAuthSession(response), window.localStorage);
}
function serve(override?: (url: URL, init: RequestInit | undefined) => Response | Promise<Response> | undefined) {
  fetchMock.mockImplementation(async (input, init) => {
    const url = new URL(String(input), 'http://localhost');
    const response = override?.(url, init);
    if (response !== undefined) return response;
    if (url.pathname === '/api/v1/admin/genres') return json([{ id: actorId, name: 'Drama', slug: 'drama' }]);
    if (url.pathname === '/api/v1/admin/people') return json({ items: [], page: { page: 0, size: 20, totalPages: 0, totalElements: 0 } });
    if (url.pathname === '/api/v1/admin/movies' || (url.pathname === '/api/v1/movies' && !init?.method)) {
      return json({ items: [], page: { page: 0, size: 20, totalPages: 0, totalElements: 0 } });
    }
    if (url.pathname === `/api/v1/movies/${movieId}` || (url.pathname === '/api/v1/movies' && init?.method === 'POST')) return json(movieDetail());
    throw new Error(`Unexpected test request ${init?.method ?? 'GET'} ${url.pathname}`);
  });
}
function movieDetail(overrides: Partial<MovieDetail> = {}): MovieDetail {
  return {
    id: movieId, title: 'Existing movie', slug: 'existing-movie', description: 'An existing description.', releaseYear: 2020,
    maturityRating: 'T16', genres: ['Drama', 'Mystery'], people: [
      { personId: actorId, name: 'Existing actor', role: 'ACTOR' }, { personId: directorId, name: 'Existing director', role: 'DIRECTOR' }
    ], averageRating: null, posterUrl: null, status: 'PUBLISHED', videoStatus: null, userRating: null, inWatchlist: false, playbackProgress: null, ...overrides
  };
}
function adminPage(movie: MovieDetail, page = 0): Response {
  return json({ items: [movie], page: { page, size: 20, totalElements: page * 20 + 1, totalPages: page + 1 } });
}
function json(body: unknown, status = 200) { return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }); }
function apiError(status: number, code: string, fieldErrors?: Array<{ field: string; message: string }>, requestId?: string) {
  return json({ timestamp: '2026-10-28T00:00:00Z', status, code, message: 'Request could not be completed', fieldErrors, requestId }, status);
}
function deferredResponse() {
  let resolveResponse: (response: Response) => void = () => undefined;
  const promise = new Promise<Response>((resolve) => { resolveResponse = resolve; });
  return { promise, release: (response: Response) => act(() => resolveResponse(response)) };
}
