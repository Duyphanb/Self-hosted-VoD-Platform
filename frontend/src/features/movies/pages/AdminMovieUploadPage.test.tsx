import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, Outlet, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AuthResponse, UserProfile } from '../../../lib/api/types';
import { FakeXhr } from '../../../test/fakeXhr';
import { AuthProvider } from '../../auth/AuthContext';
import { AdminRoute } from '../../auth/RouteGuards';
import { createAuthSession, writeAuthSession } from '../../auth/authStorage';
import { movieKeys, type MovieDetail } from '../moviesApi';
import { MAX_UPLOAD_SIZE_BYTES } from '../uploadLimits';
import { AdminMovieUploadPage } from './AdminMovieUploadPage';

const movieId = '6f1c0f55-2d4a-4b64-9a43-1d0f3c7b8e21';
const assetId = 'd614036c-ff08-4304-b70d-839b793e467e';
const uploadPath = `/admin/movies/${movieId}/video`;
const admin: UserProfile = { id: '3fcf25c2-f096-4462-ae63-4ba4702a9078', email: 'admin@example.com',
  displayName: 'Admin', roles: ['ROLE_USER', 'ROLE_ADMIN'] };
const fetchMock = vi.fn<typeof fetch>();

describe('AdminMovieUploadPage through the data router', () => {
  beforeEach(() => {
    FakeXhr.instances = [];
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('XMLHttpRequest', FakeXhr);
    serve();
  });
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

  it('rejects a non-canonical route id with no requests', async () => {
    renderUpload({ path: '/admin/movies/not-a-uuid/video' });
    expect(await screen.findByRole('heading', { name: 'Movie not found' })).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled(); expect(FakeXhr.instances.length).toBe(0);
  });

  it('does not automatically upload on a StrictMode mount', async () => {
    renderUpload({ strict: true });
    await screen.findByLabelText('Video file');
    expect(FakeXhr.instances.length).toBe(0);
    expect(uploadButton()).toBeDisabled();
  });

  it('labels the empty file picker and keeps Upload disabled until a valid selection', async () => {
    renderUpload();
    const input = await screen.findByLabelText('Video file');
    expect(input).toHaveAttribute('accept', '.mp4,.mov,.mkv,.webm');
    expect(screen.getByText('No file selected.')).toBeInTheDocument();
    expect(uploadButton()).toBeDisabled();
    const baseline = fetchMock.mock.calls.length;
    choose(new File(['text'], 'note.txt', { type: 'text/plain' }));
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByRole('alert')).toHaveTextContent('MP4, MOV, MKV or WebM');
    expect(uploadButton()).toBeDisabled();
    expect(fetchMock).toHaveBeenCalledTimes(baseline);
    expect(FakeXhr.instances.length).toBe(0);
    choose();
    expect(uploadButton()).toBeEnabled();
  });

  it('rejects an empty or over-limit file without a submit GET or XHR', async () => {
    renderUpload();
    await screen.findByLabelText('Video file');
    const baseline = fetchMock.mock.calls.length;
    choose(new File([], 'empty.mp4', { type: 'video/mp4' }));
    expect(screen.getByRole('alert')).toHaveTextContent('empty');
    const oversize = video();
    Object.defineProperty(oversize, 'size', { value: MAX_UPLOAD_SIZE_BYTES + 1 });
    choose(oversize);
    expect(screen.getByRole('alert')).toHaveTextContent('2048 MiB');
    expect(uploadButton()).toBeDisabled();
    expect(fetchMock).toHaveBeenCalledTimes(baseline);
    expect(FakeXhr.instances.length).toBe(0);
  });

  it('does a fresh canonical GET before every click even with cached detail, and never lists video assets or refreshes a valid token', async () => {
    const observations: number[] = [];
    serve(() => { observations.push(FakeXhr.instances.length); return json(detail()); });
    const { queryClient } = renderUpload({ path: `/admin/movies/${movieId.toUpperCase()}/video`, cached: detail() });
    await screen.findByLabelText('Video file');
    choose();
    fireEvent.click(uploadButton());
    await waitFor(() => expect(FakeXhr.instances.length).toBe(1));
    expect(observations[observations.length - 1]).toBe(0);
    respondError(409, 'VIDEO_UPLOAD_IN_PROGRESS');
    await outcome('already in progress');
    expect(uploadButton()).toBeEnabled();
    fireEvent.click(uploadButton());
    await waitFor(() => expect(FakeXhr.instances.length).toBe(2));
    expect(observations[observations.length - 1]).toBe(1);
    expect(fetchMock.mock.calls.every(([url]) => String(url) === `/api/v1/movies/${movieId}`)).toBe(true);
    expect(queryClient.getQueryData(movieKeys.detail(movieId))).toBeDefined();
    expect(FakeXhr.instances[1].url).toBe(`/api/v1/admin/movies/${movieId}/video`);
  });

  it.each(['DRAFT', 'PUBLISHED', 'ARCHIVED'] as const)('allows upload to a %s movie', async (status) => {
    serve(() => json(detail({ status })));
    renderUpload();
    await start();
    expect(FakeXhr.instances.length).toBe(1);
  });

  it.each(['QUEUED', 'PROCESSING', 'READY', 'FAILED', null] as const)('never gates upload on videoStatus %s', async (videoStatus) => {
    serve(() => json(detail({ videoStatus })));
    renderUpload();
    await start();
    expect(FakeXhr.instances.length).toBe(1);
  });

  it.each(['404', '403', '500', 'network', 'invalid'])('a submit-time GET %s failure sends no body and reaches a focused outcome', async (failure) => {
    let count = 0;
    serve(() => {
      if (++count === 1) return json(detail());
      if (failure === 'network') return Promise.reject(new TypeError('Connection lost'));
      if (failure === 'invalid') return json({});
      return apiError(Number(failure), failure === '404' ? 'MOVIE_NOT_FOUND' : 'CHECK_FAILED');
    });
    renderUpload();
    await screen.findByLabelText('Video file');
    choose();
    fireEvent.click(uploadButton());
    const heading = await screen.findByRole('heading', { name: failure === '404' ? 'Movie not found' : failure === '403' ? 'Access forbidden' : 'Movie could not be checked. The file was not sent.' });
    await waitFor(() => expect(document.activeElement?.contains(heading)).toBe(true));
    expect(FakeXhr.instances.length).toBe(0);
    if (!['404', '403'].includes(failure)) expect(uploadButton()).toBeEnabled();
  });

  it('unmounts during a deferred checking GET without starting a late XHR', async () => {
    const check = deferred<Response>();
    let calls = 0;
    serve(() => ++calls === 1 ? json(detail()) : check.promise);
    const view = renderUpload();
    await screen.findByLabelText('Video file'); choose(); fireEvent.click(uploadButton());
    await screen.findByText('Checking movie…');
    view.unmount();
    await act(async () => { check.resolve(json(detail())); await check.promise; });
    expect(FakeXhr.instances.length).toBe(0);
  });

  it('disables its controls during checking, sending and saving, suppresses duplicate clicks, and preserves focus', async () => {
    const check = deferred<Response>();
    let calls = 0;
    serve(() => ++calls === 1 ? json(detail()) : check.promise);
    renderUpload();
    const input = await screen.findByLabelText('Video file'); choose();
    uploadButton().focus(); fireEvent.click(uploadButton()); fireEvent.click(uploadButton());
    expect(uploadButton()).toBeDisabled(); expect(input).toBeDisabled();
    expect(screen.getByRole('status', { name: 'Upload status' })).toHaveFocus();
    expect(screen.queryByText('Cancel upload', { selector: 'button' })).not.toBeInTheDocument();
    await act(async () => { check.resolve(json(detail())); await check.promise; });
    await waitFor(() => expect(FakeXhr.instances.length).toBe(1));
    expect(FakeXhr.instances[0].sendCount).toBe(1);
    act(() => FakeXhr.instances[0].progress(996, 1000));
    expect(screen.getByText('99% sent')).toBeInTheDocument();
    screen.getByText('Cancel upload', { selector: 'button' }).focus();
    act(() => FakeXhr.instances[0].finishBody(1000, { load: false }));
    expect(screen.getByText('Saving to storage…')).toBeInTheDocument();
    expect(screen.queryByText('Cancel upload', { selector: 'button' })).not.toBeInTheDocument();
    expect(screen.getByRole('status', { name: 'Upload status' })).toHaveFocus();
    expect(uploadButton()).toBeDisabled(); expect(input).toBeDisabled();
  });

  it('switches to saving on upload load when progress length was not computable', async () => {
    renderUpload(); await start();
    act(() => FakeXhr.instances[0].progress(50, 0, false));
    expect(screen.getByText('Sending file…')).toBeInTheDocument();
    act(() => FakeXhr.instances[0].uploadLoad(100));
    expect(screen.getByText('Saving to storage…')).toBeInTheDocument();
  });

  it.each([false, true])('upload 401 with bodySent=%s shows session expired, keeps the selection, and never refreshes or replays', async (sent) => {
    renderUpload(); await start();
    if (sent) act(() => FakeXhr.instances[0].finishBody(100));
    const requests = fetchMock.mock.calls.length;
    respondError(401, 'UNAUTHORIZED');
    await outcome('session expired');
    expect(uploadButton()).toBeEnabled();
    expect(screen.queryByText('No file selected.')).not.toBeInTheDocument();
    expect(FakeXhr.instances.length).toBe(1);
    expect(fetchMock).toHaveBeenCalledTimes(requests);
  });

  it('refreshes only a submit GET 401 and then sends exactly one upload', async () => {
    let movieCalls = 0;
    fetchMock.mockImplementation(async (input) => {
      if (String(input) === '/api/v1/auth/refresh') return json({ user: admin, accessToken: 'renewed-access', refreshToken: 'renewed-refresh', expiresInSeconds: 900 });
      if (String(input) === `/api/v1/movies/${movieId}`) return ++movieCalls === 2 ? apiError(401, 'UNAUTHORIZED') : json(detail());
      throw new Error('Unexpected request in upload refresh test');
    });
    renderUpload(); await start();
    expect(fetchMock.mock.calls.filter(([url]) => String(url) === '/api/v1/auth/refresh').length).toBe(1);
    expect(movieCalls).toBe(3);
    expect(FakeXhr.instances.length).toBe(1); expect(FakeXhr.instances[0].sendCount).toBe(1);
  });

  it('Cancel aborts once, keeps the file, focuses a conditional cancelled outcome and does not imply server rollback', async () => {
    renderUpload(); await start();
    fireEvent.click(screen.getByText('Cancel upload', { selector: 'button' }));
    const panel = await outcome('Upload cancelled before');
    expect(panel).toHaveTextContent('Upload cancelled before the file was fully sent.');
    expect(panel.textContent?.match(/rolled back/g)?.length).toBe(1);
    guidance(panel);
    expect(FakeXhr.instances[0].abortCount).toBe(1);
    expect(FakeXhr.instances.length).toBe(1);
    expect(uploadButton()).toBeEnabled();
    expect(screen.queryByText('Saving to storage…')).not.toBeInTheDocument();
  });

  it('a cancellation that settles after the body was fully sent is unknown', async () => {
    renderUpload(); await start();
    FakeXhr.instances[0].deferAbort = true;
    fireEvent.click(screen.getByText('Cancel upload', { selector: 'button' }));
    act(() => { FakeXhr.instances[0].finishBody(100); FakeXhr.instances[0].fail('abort'); });
    guidance(await outcome('Upload result unknown'));
  });

  it.each([false, true])('status 0 with bodySent=%s has optional request details and no automatic replay', async (sent) => {
    renderUpload(); await start();
    if (sent) act(() => FakeXhr.instances[0].finishBody(100));
    act(() => FakeXhr.instances[0].fail('error'));
    const panel = await outcome(sent ? 'Upload result unknown' : 'File too large or connection lost');
    expect(panel).not.toHaveTextContent('Request ID');
    expect(uploadButton()).toBeEnabled(); expect(FakeXhr.instances.length).toBe(1);
  });

  it.each([[502, 'UPSTREAM_UNAVAILABLE'], [503, 'UPSTREAM_UNAVAILABLE'], [504, 'UPSTREAM_TIMEOUT'],
    [500, 'UPLOAD_OUTCOME_UNKNOWN'], [500, 'INTERNAL_ERROR'], [500, 'UNMAPPED'], [503, 'UPLOAD_DATABASE_ERROR']] as const)(
    'classifies %s/%s as unknown with requestId and conditional re-upload guidance', async (status, code) => {
      renderUpload(); await start(); respondError(status, code);
      const panel = await outcome('Upload result unknown');
      expect(panel).toHaveTextContent('Request ID: upload-test-request'); guidance(panel);
      expect(uploadButton()).toBeEnabled(); expect(FakeXhr.instances.length).toBe(1);
    });

  it.each([[503, 'UPLOAD_STORAGE_UNAVAILABLE'], [500, 'UPLOAD_DATABASE_ERROR']] as const)(
    'classifies %s/%s as not accepted and only retries on a new click', async (status, code) => {
      renderUpload(); await start(); respondError(status, code);
      await outcome('not accepted (no committed asset/job pair)');
      expect(FakeXhr.instances.length).toBe(1);
      fireEvent.click(uploadButton());
      await waitFor(() => expect(FakeXhr.instances.length).toBe(2));
    });

  it.each([[400, 'UNSUPPORTED_VIDEO_TYPE', 'not a supported video'], [413, 'PAYLOAD_TOO_LARGE', '2048 MiB'],
    [409, 'VIDEO_UPLOAD_IN_PROGRESS', 'already in progress']] as const)(
    'shows a focused specific %s/%s rejection', async (status, code, message) => {
      renderUpload(); await start(); respondError(status, code);
      await outcome(message);
      expect(uploadButton()).toBeEnabled();
    });

  it('a non-ApiError 400 and non-JSON 500 do not invent Request ID details', async () => {
    renderUpload(); await start();
    act(() => FakeXhr.instances[0].respond(400, '<html>Bad request</html>'));
    expect(await outcome('server rejected')).not.toHaveTextContent('Request ID');
    fireEvent.click(uploadButton()); await waitFor(() => expect(FakeXhr.instances.length).toBe(2));
    act(() => FakeXhr.instances[1].respond(500, '<html>Error</html>'));
    expect(await outcome('Upload result unknown')).not.toHaveTextContent('Request ID');
  });

  it.each([undefined, 'invalid'])('accepts 202 with %s optional/undecodable body and clears the selection', async (variant) => {
    const { queryClient } = renderUpload(); await start();
    const invalidation = vi.spyOn(queryClient, 'invalidateQueries');
    act(() => FakeXhr.instances[0].respond(202, variant === 'invalid' ? 'unreadable' : JSON.stringify({ videoAssetId: assetId, status: 'QUEUED' })));
    const panel = await outcome(variant === 'invalid' ? 'Upload accepted, status could not be read.' : 'Upload accepted. Video asset status: QUEUED.');
    guidance(panel); expect(screen.getByText('No file selected.')).toBeInTheDocument();
    expect(uploadButton()).toBeDisabled();
    expect(invalidation).toHaveBeenCalledWith({ queryKey: movieKeys.all });
  });

  it('a 200 is unknown rather than accepted', async () => {
    renderUpload(); await start();
    act(() => FakeXhr.instances[0].respond(200, JSON.stringify({ videoAssetId: assetId, status: 'QUEUED' })));
    await outcome('Upload result unknown'); expect(uploadButton()).toBeEnabled();
  });

  it('lost replacement response stays unknown even when a later detail refresh still says READY', async () => {
    serve(() => json(detail({ videoStatus: 'READY' })));
    const { queryClient } = renderUpload(); await start();
    act(() => { FakeXhr.instances[0].finishBody(100); FakeXhr.instances[0].fail('error'); });
    guidance(await outcome('Upload result unknown'));
    await act(async () => { await queryClient.invalidateQueries({ queryKey: movieKeys.all }); });
    expect(screen.getByRole('heading', { name: 'Upload result unknown' })).toBeInTheDocument();
    expect(FakeXhr.instances.length).toBe(1);
  });

  it.each([false, true])('a superseded upload session bodySent=%s ends in a focused terminal state', async (sent) => {
    renderUpload(); await start();
    persist('replacement');
    if (sent) act(() => FakeXhr.instances[0].finishBody(100));
    act(() => FakeXhr.instances[0].respond(202, JSON.stringify({ videoAssetId: assetId, status: 'QUEUED' })));
    await outcome(sent ? 'Upload result unknown' : 'Your session changed. Select Upload to try again.');
    expect(uploadButton()).toBeEnabled();
  });

  it('a superseded checking GET terminates without sending a body', async () => {
    const check = deferred<Response>(); let calls = 0;
    serve(() => ++calls === 1 ? json(detail()) : check.promise);
    renderUpload(); await screen.findByLabelText('Video file'); choose(); fireEvent.click(uploadButton());
    persist('replacement');
    await act(async () => { check.resolve(json(detail())); await check.promise; });
    await outcome('Your session changed. Select Upload to try again.');
    expect(uploadButton()).toBeEnabled(); expect(FakeXhr.instances.length).toBe(0);
  });

  it('lets a saving upload finish after unmount and invalidates accepted queries without aborting it', async () => {
    const { queryClient, unmount } = renderUpload(); await start();
    const invalidation = vi.spyOn(queryClient, 'invalidateQueries');
    act(() => FakeXhr.instances[0].finishBody(100)); unmount();
    expect(FakeXhr.instances[0].abortCount).toBe(0);
    await act(async () => FakeXhr.instances[0].respond(202, JSON.stringify({ videoAssetId: assetId, status: 'QUEUED' })));
    expect(invalidation).toHaveBeenCalledWith({ queryKey: movieKeys.all });
  });

  it('aborts only the owned sender when unmounted during transfer', async () => {
    const { unmount } = renderUpload(); await start();
    unmount();
    expect(FakeXhr.instances[0].abortCount).toBe(1);
    expect(FakeXhr.instances.length).toBe(1);
  });

  it('uses the filtered return link, blocks checking/saving navigation and removes beforeunload after settling', async () => {
    const check = deferred<Response>(); let calls = 0;
    serve(() => ++calls === 1 ? json(detail()) : check.promise);
    renderUpload({ returnTo: '/admin/movies?status=ARCHIVED&page=2' });
    await screen.findByLabelText('Video file');
    expect(screen.getByText('Back to movies')).toHaveAttribute('href', '/admin/movies?status=ARCHIVED&page=2');
    choose(); fireEvent.click(uploadButton());
    expect(beforeUnload().defaultPrevented).toBe(true);
    fireEvent.click(screen.getByText('Back to movies'));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).queryByText('Cancel upload and leave')).not.toBeInTheDocument();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('status', { name: 'Upload status' })).toHaveFocus();
    await act(async () => { check.resolve(json(detail())); await check.promise; });
    await waitFor(() => expect(FakeXhr.instances.length).toBe(1));
    act(() => FakeXhr.instances[0].finishBody(100));
    fireEvent.click(screen.getByText('Back to movies'));
    expect(within(await screen.findByRole('dialog')).queryByText('Cancel upload and leave')).not.toBeInTheDocument();
    respondError(503, 'UPLOAD_STORAGE_UNAVAILABLE'); await outcome('not accepted');
    expect(beforeUnload().defaultPrevented).toBe(false);
  });

  it('a response while the leave dialog is open resets navigation, closes it and focuses the outcome', async () => {
    const { router } = renderUpload(); await start();
    fireEvent.click(screen.getByText('Back to movies'));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    act(() => FakeXhr.instances[0].respond(202, JSON.stringify({ videoAssetId: assetId, status: 'QUEUED' })));
    await outcome('Upload accepted. Video asset status: QUEUED.');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(router.state.location.pathname).toBe(uploadPath);
    expect([...router.state.blockers.values()].every((entry) => entry.state === 'unblocked')).toBe(true);
  });

  it('a rejected response resets blocked navigation before a manual retry', async () => {
    const { router } = renderUpload(); await start();
    fireEvent.click(screen.getByText('Back to movies'));
    await screen.findByRole('dialog');
    respondError(503, 'UPLOAD_STORAGE_UNAVAILABLE');
    await outcome('not accepted');
    expect([...router.state.blockers.values()].every((entry) => entry.state === 'unblocked')).toBe(true);
    fireEvent.click(uploadButton());
    await waitFor(() => expect(FakeXhr.instances.length).toBe(2));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('keeps keyboard focus elsewhere when sending finishes', async () => {
    renderUpload(); await start();
    const back = screen.getByText('Back to movies'); back.focus();
    act(() => FakeXhr.instances[0].finishBody(100));
    expect(back).toHaveFocus();
  });

  it('sending to saving removes Cancel upload and leave from an open modal without losing focus', async () => {
    const focus = HTMLElement.prototype.focus;
    vi.spyOn(HTMLElement.prototype, 'focus').mockImplementation(function (this: HTMLElement, options) {
      if (!this.closest('[inert]')) focus.call(this, options);
    });
    renderUpload(); await start();
    fireEvent.click(screen.getByText('Back to movies'));
    const dialog = await screen.findByRole('dialog');
    within(dialog).getByText('Cancel upload and leave').focus();
    act(() => FakeXhr.instances[0].finishBody(100));
    expect(within(dialog).queryByText('Cancel upload and leave')).not.toBeInTheDocument();
    expect(within(dialog).getByText('Stay on page')).toHaveFocus();
    expect(screen.getByText('Saving to storage…')).toBeInTheDocument();
    expect(fireEvent.mouseDown(dialog.parentElement!)).toBe(false);
    fireEvent.click(within(dialog).getByText('Stay on page'));
    expect(screen.getByRole('status', { name: 'Upload status' })).toHaveFocus();
  });

  it('an explicit Cancel upload and leave aborts the sender and follows the blocked link', async () => {
    const { router } = renderUpload(); await start();
    fireEvent.click(screen.getByText('Back to movies'));
    fireEvent.click(within(await screen.findByRole('dialog')).getByText('Cancel upload and leave'));
    await waitFor(() => expect(router.state.location.pathname).toBe('/admin/movies'));
    expect(FakeXhr.instances[0].abortCount).toBe(1);
  });

  it.each(['/login', '/forbidden'])('lets automatic %s navigation through while sending', async (destination) => {
    const { router } = renderUpload(); await start();
    await act(async () => { await router.navigate(destination); });
    expect(router.state.location.pathname).toBe(destination);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

function renderUpload({ path = uploadPath, returnTo, cached, strict = false }: { path?: string; returnTo?: string; cached?: MovieDetail; strict?: boolean } = {}) {
  persist();
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 60_000 } } });
  if (cached) queryClient.setQueryData(movieKeys.detail(movieId), cached);
  const providers = <QueryClientProvider client={queryClient}><AuthProvider redirectToLogin={vi.fn()}><Outlet /></AuthProvider></QueryClientProvider>;
  const router = createMemoryRouter([{
    element: strict ? <StrictMode>{providers}</StrictMode> : providers,
    children: [
      { element: <AdminRoute />, children: [{ path: '/admin/movies/:movieId/video', element: <AdminMovieUploadPage /> }] },
      { path: '/admin/movies', element: <h1>Movies list</h1> },
      { path: '/login', element: <h1>Sign in</h1> },
      { path: '/forbidden', element: <h1>Forbidden destination</h1> }
    ]
  }], { initialEntries: [{ pathname: path, state: returnTo ? { returnTo } : null }], future: { v7_relativeSplatPath: true } });
  return { ...render(<RouterProvider future={{ v7_startTransition: true }} router={router} />), router, queryClient };
}
function persist(prefix = '') {
  const response: AuthResponse = { user: admin, accessToken: `${prefix}access`, refreshToken: `${prefix}refresh`, expiresInSeconds: 900 };
  writeAuthSession(createAuthSession(response), window.localStorage);
}
function serve(get?: () => Response | Promise<Response>) {
  fetchMock.mockImplementation(async (input) => {
    if (String(input) === `/api/v1/movies/${movieId}`) return get ? get() : json(detail());
    throw new Error('Unexpected request in upload page test');
  });
}
function detail(overrides: Partial<MovieDetail> = {}): MovieDetail {
  return { id: movieId, title: 'Upload movie', slug: 'upload-movie', description: 'Movie description', releaseYear: 2026,
    maturityRating: null, genres: [], people: [], averageRating: null, posterUrl: null, status: 'PUBLISHED', videoStatus: null,
    userRating: null, inWatchlist: false, playbackProgress: null, ...overrides };
}
function video() { return new File(['synthetic'], 'movie.mp4', { type: 'video/mp4' }); }
function choose(file = video()) { fireEvent.change(screen.getByLabelText('Video file'), { target: { files: [file] } }); }
function uploadButton() { return screen.getByText('Upload', { selector: 'button' }); }
async function start() {
  await screen.findByLabelText('Video file'); choose(); fireEvent.click(uploadButton());
  await waitFor(() => expect(FakeXhr.instances.length).toBe(1));
}
function respondError(status: number, code: string) {
  act(() => FakeXhr.instances[FakeXhr.instances.length - 1]?.respond(status, JSON.stringify({ timestamp: '2026-11-02T00:00:00Z', status,
    code, message: 'The request was rejected.', requestId: 'upload-test-request' })));
}
async function outcome(message: string) {
  const panel = await screen.findByRole('region', { name: (name) => name.toLowerCase().includes(message.toLowerCase()) });
  await waitFor(() => expect(panel).toHaveFocus());
  return panel;
}
function guidance(panel: HTMLElement) {
  expect(panel).toHaveTextContent('A deliberate re-upload is safe: existing video assets are never changed.');
  expect(panel).toHaveTextContent('If the previous upload is still being processed');
  expect(panel).toHaveTextContent('409'); expect(panel).toHaveTextContent('If it has finished');
  expect(panel).toHaveTextContent('newer asset with extra storage cost');
  expect(panel).toHaveTextContent('Manual purge is planned');
  expect(within(panel).queryByRole('link')).not.toBeInTheDocument();
}
function json(body: unknown) { return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }); }
function apiError(status: number, code: string) { return new Response(JSON.stringify({ timestamp: '2026-11-02T00:00:00Z', status,
  code, message: 'The movie could not be checked.' }), { status, headers: { 'Content-Type': 'application/json' } }); }
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}
function beforeUnload() {
  const event = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(event); return event;
}
