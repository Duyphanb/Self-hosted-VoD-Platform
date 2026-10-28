import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, useLocation, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../../../app/AppRoutes';
import { createQueryClient } from '../../../app/queryClient';
import { AuthenticationSupersededError } from '../../../lib/api/client';
import type { AuthResponse, UserProfile } from '../../../lib/api/types';
import { AuthProvider } from '../../auth/AuthContext';
import { AUTH_SESSION_STORAGE_KEY, createAuthSession, writeAuthSession } from '../../auth/authStorage';
import { movieKeys, type MovieDetail } from '../moviesApi';

const movieId = '6f1c0f55-2d4a-4b64-9a43-1d0f3c7b8e21';
const nextMovieId = '7334b2f1-664f-4bc1-b8f1-10de58c1d74a';
const regularUser: UserProfile = {
  id: '3fcf25c2-f096-4462-ae63-4ba4702a9078',
  email: 'viewer@example.com',
  displayName: 'Viewer',
  roles: ['ROLE_USER']
};
const admin: UserProfile = { ...regularUser, roles: ['ROLE_USER', 'ROLE_ADMIN'] };
const otherUser: UserProfile = {
  ...regularUser,
  id: 'b5f7c0f4-1d2e-4c3b-9a8f-7e6d5c4b3a21',
  email: 'other@example.com',
  displayName: 'Other viewer'
};
const fetchMock = vi.fn<typeof fetch>();

describe('MovieDetailPage through AppRoutes', () => {
  beforeEach(() => {
    fetchMock.mockReset();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('fetches the movie with authentication and shows metadata in the agreed order', async () => {
    const movie = movieDetail({ averageRating: 4.25 });
    fetchMock.mockResolvedValue(jsonResponse(movie));
    renderDetail();

    // The first authenticated query can settle after 1s when all jsdom workers start in parallel.
    const title = await screen.findByRole('heading', { level: 1, name: movie.title }, { timeout: 5_000 });
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(fieldValue('Release year')).toHaveTextContent('2019');
    expect(fieldValue('Maturity rating')).toHaveTextContent('T16');
    expect(screen.getByText('T16', { selector: 'abbr' })).toHaveAttribute('title', 'Ages 16 and over');
    expect(fieldValue('Genres')).toHaveTextContent('Drama');
    expect(fieldValue('Genres')).toHaveTextContent('Thriller');
    expect(fieldValue('Availability')).toHaveTextContent('Not available');
    expect(fieldValue('Average rating')).toHaveTextContent(/4\.25\s*\/\s*5/);
    expect(headingSection('Description')).toHaveTextContent(movie.description);
    expect(headingSection('Directors')).toHaveTextContent('Director First');
    expect(headingSection('Cast')).toHaveTextContent('Actor First');
    expect(headingSection('Cast')).toHaveTextContent('Actor Second');
    expect(headingSection('Cast').textContent?.indexOf('Actor First'))
      .toBeLessThan(headingSection('Cast').textContent?.indexOf('Actor Second') ?? -1);

    const ordered = [
      title,
      screen.getByText('Release year'),
      screen.getByText('Maturity rating'),
      screen.getByText('Genres'),
      screen.getByText('Availability'),
      screen.getByText('Average rating'),
      screen.getByRole('heading', { name: 'Description' }),
      screen.getByRole('heading', { name: 'Directors' }),
      screen.getByRole('heading', { name: 'Cast' })
    ];
    for (let index = 1; index < ordered.length; index += 1) {
      expect(ordered[index - 1].compareDocumentPosition(ordered[index]) & Node.DOCUMENT_POSITION_FOLLOWING)
        .toBeTruthy();
    }
    const [input, init] = fetchMock.mock.calls[0];
    expect(new URL(String(input), 'http://localhost').pathname).toBe(`/api/v1/movies/${movieId}`);
    expect(init?.method ?? 'GET').toBe('GET');
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer access-token');
    expect(init?.signal).toBeInstanceOf(AbortSignal);
    expect(screen.getByRole('link', { name: 'Back to movies' })).toHaveAttribute('href', '/movies');
  });

  const availabilityCases = [
    ['READY', 'Ready', true],
    ['UPLOADED', 'Processing', false],
    ['QUEUED', 'Processing', false],
    ['PROCESSING', 'Processing', false],
    ['FAILED', 'Not available', false],
    [null, 'Not available', false],
    ['TRANSCODING_V2', 'Not available', false]
  ] as const;
  const viewers = [
    ['viewer', regularUser],
    ['admin', admin]
  ] as const;
  it.each(viewers.flatMap(([role, user]) => availabilityCases.map(([videoStatus, label, canWatch]) => ({
    role, user, videoStatus, label, canWatch
  }))))('maps $videoStatus to $label and gates Watch for $role', async ({ user, videoStatus, label, canWatch }) => {
    fetchMock.mockResolvedValue(jsonResponse({ ...movieDetail(), videoStatus }));
    renderDetail({ user });

    await screen.findByRole('heading', { level: 1, name: 'The Long Night' });
    expect(fieldValue('Availability')).toHaveTextContent(label);
    if (user.roles.includes('ROLE_ADMIN')) {
      expect(fieldValue('Video status')).toHaveTextContent(videoStatus === 'TRANSCODING_V2' ? 'UNKNOWN' : videoStatus ?? 'None');
    }
    if (canWatch) {
      const watch = screen.getByRole('link', { name: 'Watch' });
      expect(watch).toHaveAttribute('href', `/movies/${movieId}/watch`);
      watch.focus();
      expect(watch).toHaveFocus();
      fireEvent.click(watch);
      expect(await screen.findByRole('heading', { level: 1, name: 'Page not found' })).toBeInTheDocument();
    } else {
      expect(screen.queryByRole('link', { name: 'Watch' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Watch' })).not.toBeInTheDocument();
    }
  });

  it('shows explicit empty metadata and omits absent personal fields', async () => {
    const { playbackProgress: _omitted, ...movie } = movieDetail({
      releaseYear: null,
      maturityRating: null,
      genres: [],
      people: []
    });
    fetchMock.mockResolvedValue(jsonResponse(movie));
    renderDetail();

    await screen.findByRole('heading', { level: 1, name: movie.title });
    expect(fieldValue('Release year')).toHaveTextContent('Not specified');
    expect(fieldValue('Maturity rating')).toHaveTextContent('Not classified');
    expect(fieldValue('Genres')).toHaveTextContent('No genres listed');
    expect(fieldValue('Average rating')).toHaveTextContent('No ratings yet');
    expect(headingSection('Directors')).toHaveTextContent('Not listed');
    expect(headingSection('Cast')).toHaveTextContent('Not listed');
    expect(screen.queryByText('Your rating')).not.toBeInTheDocument();
    expect(screen.queryByText('In your watchlist')).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Playback progress' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Other credits' })).not.toBeInTheDocument();
  });

  it('shows personal rating, watchlist membership and saved progress when present', async () => {
    fetchMock.mockResolvedValue(jsonResponse(movieDetail({
      userRating: 4,
      inWatchlist: true,
      playbackProgress: {
        movieId,
        currentSeconds: 754,
        durationSeconds: 5400,
        finished: false,
        lastPlayedAt: '2026-10-27T09:15:00Z'
      }
    })));
    renderDetail();

    await screen.findByRole('heading', { level: 1, name: 'The Long Night' });
    expect(fieldValue('Your rating')).toHaveTextContent(/4\s*\/\s*5/);
    expect(screen.getByText('In your watchlist')).toBeInTheDocument();
    const progress = headingSection('Playback progress');
    expect(progress).toHaveTextContent('754');
    expect(progress).toHaveTextContent('5400');
    expect(progress).toHaveTextContent('In progress');
    expect(progress).toHaveTextContent('Last played');
    expect(progress).toHaveTextContent('2026-10-27T09:15:00Z');
  });

  it('keeps zero progress and completed state when optional progress fields are absent', async () => {
    fetchMock.mockResolvedValue(jsonResponse({
      ...movieDetail(),
      playbackProgress: { movieId, currentSeconds: 0, finished: true }
    }));
    renderDetail();

    await screen.findByRole('heading', { level: 1, name: 'The Long Night' });
    const progress = headingSection('Playback progress');
    expect(progress).toHaveTextContent(/0\s*seconds/);
    expect(progress).toHaveTextContent('Completed');
    expect(progress).not.toHaveTextContent('null');
    expect(progress).not.toHaveTextContent('undefined');
    expect(progress).not.toHaveTextContent('Last played');
  });

  it('shows unknown maturity and credit roles without dropping the credited person', async () => {
    fetchMock.mockResolvedValue(jsonResponse({
      ...movieDetail(),
      maturityRating: 'C',
      people: [{ personId: regularUser.id, name: 'A Future Credit', role: 'WRITER' }]
    }));
    renderDetail();

    await screen.findByRole('heading', { level: 1, name: 'The Long Night' });
    expect(fieldValue('Maturity rating')).toHaveTextContent('Unknown');
    expect(headingSection('Other credits')).toHaveTextContent('A Future Credit');
    expect(headingSection('Directors')).not.toHaveTextContent('A Future Credit');
    expect(headingSection('Cast')).not.toHaveTextContent('A Future Credit');
  });

  it('does not render admin information or controls for a regular user', async () => {
    fetchMock.mockResolvedValue(jsonResponse(movieDetail({ videoStatus: 'UPLOADED' })));
    renderDetail();

    await screen.findByRole('heading', { level: 1, name: 'The Long Night' });
    const main = screen.getByRole('main');
    expect(within(main).queryByRole('heading', { name: 'Movie administration' })).not.toBeInTheDocument();
    expect(within(main).queryByText('Movie status')).not.toBeInTheDocument();
    expect(within(main).queryByText('Slug')).not.toBeInTheDocument();
    expect(within(main).queryByText('Video status')).not.toBeInTheDocument();
    expect(within(main).queryByRole('link', { name: 'Edit movie' })).not.toBeInTheDocument();
    expect(main).not.toHaveTextContent('the-long-night');
    expect(main).not.toHaveTextContent('PUBLISHED');
    expect(main).not.toHaveTextContent('UPLOADED');
  });

  it.each(['DRAFT', 'ARCHIVED'] as const)('shows the admin panel and visibility banner for %s', async (status) => {
    fetchMock.mockResolvedValue(jsonResponse(movieDetail({ status, videoStatus: 'FAILED' })));
    renderDetail({ user: admin });

    await screen.findByRole('heading', { level: 1, name: 'The Long Night' });
    const panel = headingSection('Movie administration');
    expect(fieldValue('Movie status')).toHaveTextContent(status === 'DRAFT' ? 'Draft' : 'Archived');
    expect(fieldValue('Slug')).toHaveTextContent('the-long-night');
    expect(fieldValue('Video status')).toHaveTextContent('FAILED');
    expect(within(panel).getByRole('link', { name: 'Edit movie' }))
      .toHaveAttribute('href', `/admin/movies/${movieId}/edit`);
    expect(screen.getByText(/not visible to viewers/i)).toBeInTheDocument();
  });

  it('shows a published admin movie without a visibility warning and handles no video asset', async () => {
    fetchMock.mockResolvedValue(jsonResponse(movieDetail()));
    renderDetail({ user: admin });

    await screen.findByRole('heading', { level: 1, name: 'The Long Night' });
    expect(fieldValue('Movie status')).toHaveTextContent('Published');
    expect(fieldValue('Video status')).toHaveTextContent('None');
    expect(screen.queryByText(/not visible to viewers/i)).not.toBeInTheDocument();
  });

  it('normalizes an upper-case UUID for the request and query cache', async () => {
    fetchMock.mockResolvedValue(jsonResponse(movieDetail({ videoStatus: 'READY' })));
    const { queryClient } = renderDetail({ path: `/movies/${movieId.toUpperCase()}` });

    await screen.findByRole('heading', { level: 1, name: 'The Long Night' });
    expect(requestPaths()).toEqual([`/api/v1/movies/${movieId}`]);
    expect(queryClient.getQueryData(movieKeys.detail(movieId))).toMatchObject({ id: movieId });
    expect(queryClient.getQueryData(movieKeys.detail(movieId.toUpperCase()))).toBeUndefined();
    expect(screen.getByRole('link', { name: 'Watch' })).toHaveAttribute('href', `/movies/${movieId}/watch`);
  });

  it.each(['not-a-uuid', '1234', `${movieId}x`, '6f1c0f55-2d4a-4b64-9a43-1d0f3c7b8e2g'])(
    'shows movie not found without a request for invalid id %s', async (id) => {
      renderDetail({ path: `/movies/${id}` });

      expect(await screen.findByRole('heading', { level: 1, name: 'Movie not found' })).toBeInTheDocument();
      expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
      expect(screen.getByRole('link', { name: 'Back to movies' })).toHaveAttribute('href', '/movies');
      expect(fetchMock).not.toHaveBeenCalled();
      expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
    }
  );

  it.each([
    [400, 'VALIDATION_ERROR', 'Invalid movieId'],
    [404, 'MOVIE_NOT_FOUND', 'Movie not found']
  ] as const)('uses the same movie not-found state for HTTP %s', async (status, code, message) => {
    fetchMock.mockResolvedValue(apiError(status, code, message));
    renderDetail({ queryClient: createQueryClient() });

    expect(await screen.findByRole('heading', { level: 1, name: 'Movie not found' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to movies' })).toHaveAttribute('href', '/movies');
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('keeps missing and non-published movies indistinguishable when the API answers 404', async () => {
    fetchMock.mockResolvedValue(apiError(404, 'MOVIE_NOT_FOUND', 'Movie not found'));
    renderDetail();

    expect(await screen.findByRole('heading', { level: 1, name: 'Movie not found' })).toBeInTheDocument();
    expect(screen.getByRole('main')).not.toHaveTextContent('The Long Night');
    expect(screen.getByRole('main')).not.toHaveTextContent(/DRAFT|ARCHIVED|the-long-night/);
  });

  it('announces loading with one page heading while the response is pending', async () => {
    const response = deferredResponse();
    fetchMock.mockReturnValue(response.promise);
    renderDetail();

    const status = await screen.findByRole('status');
    expect(status).toHaveTextContent('Loading movie…');
    expect(screen.getByRole('heading', { level: 1, name: 'Movie details' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.queryByRole('link', { name: 'Watch' })).not.toBeInTheDocument();
    response.release(jsonResponse(movieDetail()));
    await screen.findByRole('heading', { level: 1, name: 'The Long Night' });
    expect(screen.queryByText('Loading movie…')).not.toBeInTheDocument();
  });

  it('shows other API failures with Retry and keeps focus while retrying', async () => {
    const response = deferredResponse();
    fetchMock.mockResolvedValueOnce(apiError(500, 'INTERNAL_ERROR', 'Please try again', 'detail-request'))
      .mockReturnValueOnce(response.promise);
    renderDetail();

    const alert = await screen.findByRole('alert');
    expect(within(alert).getByRole('heading', { level: 1, name: 'Movie could not be loaded' })).toBeInTheDocument();
    expect(alert).toHaveTextContent('Please try again');
    expect(alert).toHaveTextContent('detail-request');
    const retry = screen.getByRole('button', { name: 'Retry' });
    retry.focus();
    fireEvent.click(retry);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(document.activeElement).toHaveAttribute('aria-label', 'Movie details');
    response.release(jsonResponse(movieDetail()));
    await screen.findByRole('heading', { level: 1, name: 'The Long Night' });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(document.activeElement).toHaveAttribute('aria-label', 'Movie details');
  });

  it.each([403, 429])('does not treat HTTP %s as movie not found', async (status) => {
    fetchMock.mockResolvedValue(apiError(status, 'REQUEST_REJECTED', 'Request could not be completed'));
    renderDetail();

    expect(await screen.findByRole('heading', { level: 1, name: 'Movie could not be loaded' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Movie not found' })).not.toBeInTheDocument();
  });

  it('handles a malformed success response as a retryable error', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ ...movieDetail(), people: null }));
    renderDetail();

    expect(await screen.findByRole('heading', { level: 1, name: 'Movie could not be loaded' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'The Long Night' })).not.toBeInTheDocument();
  });

  it('offers Retry after a network failure and loads the movie when the connection recovers', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(jsonResponse(movieDetail()));
    renderDetail();

    expect(await screen.findByRole('heading', { level: 1, name: 'Movie could not be loaded' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('heading', { level: 1, name: 'The Long Night' })).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('removes cached metadata and Watch when a refetch says the movie is no longer visible', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(movieDetail({ videoStatus: 'READY' })))
      .mockResolvedValueOnce(apiError(404, 'MOVIE_NOT_FOUND', 'Movie not found'));
    const { queryClient } = renderDetail();
    await screen.findByRole('heading', { level: 1, name: 'The Long Night' });
    expect(screen.getByRole('link', { name: 'Watch' })).toBeInTheDocument();

    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: movieKeys.detail(movieId) });
    });

    expect(await screen.findByRole('heading', { level: 1, name: 'Movie not found' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'The Long Night' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Watch' })).not.toBeInTheDocument();
  });

  it('does not show the previous movie or its Watch link while another id loads', async () => {
    const response = deferredResponse();
    fetchMock.mockResolvedValueOnce(jsonResponse(movieDetail({ videoStatus: 'READY' })))
      .mockReturnValueOnce(response.promise);
    renderDetail();
    await screen.findByRole('heading', { level: 1, name: 'The Long Night' });

    fireEvent.click(screen.getByRole('button', { name: 'Next movie' }));
    await waitFor(() => expect(requestPaths()).toEqual([
      `/api/v1/movies/${movieId}`, `/api/v1/movies/${nextMovieId}`
    ]));
    expect(screen.queryByRole('heading', { name: 'The Long Night' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Watch' })).not.toBeInTheDocument();
    response.release(jsonResponse(movieDetail({ id: nextMovieId, title: 'The Next Movie' })));
    expect(await screen.findByRole('heading', { level: 1, name: 'The Next Movie' })).toBeInTheDocument();
  });

  it('renders API text literally and uses generated poster art without fetching poster URLs', async () => {
    const movie = movieDetail({
      title: '<img src=x onerror="window.__detailXss = 1">',
      description: '<script>alert(1)</script>',
      genres: ['<b>Drama</b>'],
      posterUrl: 'https://example.invalid/untrusted.jpg',
      slug: '<a href="javascript:alert(2)">slug</a>',
      people: [{ personId: regularUser.id, name: '<svg onload=alert(3)>', role: 'ACTOR' }]
    });
    fetchMock.mockResolvedValue(jsonResponse(movie));
    const { container } = renderDetail({ user: admin });

    await screen.findByRole('heading', { level: 1, name: movie.title });
    expect(headingSection('Description')).toHaveTextContent(movie.description);
    expect(fieldValue('Genres')).toHaveTextContent('<b>Drama</b>');
    expect(fieldValue('Slug')).toHaveTextContent(movie.slug);
    expect(headingSection('Cast')).toHaveTextContent('<svg onload=alert(3)>');
    expect(container.querySelector('img, script, svg, b')).toBeNull();
    expect([...container.querySelectorAll('a')].some((link) => link.href.startsWith('javascript:'))).toBe(false);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect((window as Window & { __detailXss?: number }).__detailXss).toBeUndefined();
  });

  it('requires sign-in before fetching a detail route and preserves the intended URL', async () => {
    renderDetail({ user: null, path: `/movies/${movieId}?source=catalog#cast` });

    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByTestId('location-state')).toHaveTextContent(`/movies/${movieId}?source=catalog#cast`);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('uses the shared refresh flow after a 401 and retries with the rotated access token', async () => {
    fetchMock.mockResolvedValueOnce(apiError(401, 'UNAUTHORIZED', 'Authentication required'))
      .mockResolvedValueOnce(jsonResponse({
        user: regularUser,
        accessToken: 'rotated-access-token',
        refreshToken: 'rotated-refresh-token',
        expiresInSeconds: 900
      }))
      .mockResolvedValueOnce(jsonResponse(movieDetail()));
    renderDetail();

    expect(await screen.findByRole('heading', { level: 1, name: 'The Long Night' })).toBeInTheDocument();
    expect(requestPaths()).toEqual([
      `/api/v1/movies/${movieId}`, '/api/v1/auth/refresh', `/api/v1/movies/${movieId}`
    ]);
    expect(authorizationOf(2)).toBe('Bearer rotated-access-token');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('returns to sign-in when a 401 cannot be refreshed', async () => {
    fetchMock.mockResolvedValueOnce(apiError(401, 'UNAUTHORIZED', 'Authentication required'))
      .mockResolvedValueOnce(apiError(401, 'INVALID_REFRESH_TOKEN', 'Refresh token is invalid or expired'));
    const { redirectToLogin } = renderDetail();

    expect(await screen.findByRole('heading', { level: 1, name: 'Sign in' })).toBeInTheDocument();
    expect(redirectToLogin).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('heading', { name: 'The Long Night' })).not.toBeInTheDocument();
  });

  it('recovers silently when the same user rotates their session while a response is pending', async () => {
    const response = deferredResponse();
    fetchMock.mockReturnValueOnce(response.promise)
      .mockResolvedValueOnce(jsonResponse(movieDetail()));
    const { queryClient } = renderDetail();
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));

    persistSession(regularUser, 'rotated');
    response.release(jsonResponse(movieDetail()));
    await waitFor(() => expect(queryClient.getQueryState(movieKeys.detail(movieId))?.error)
      .toBeInstanceOf(AuthenticationSupersededError));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByText('Loading movie…')).toBeInTheDocument();
    announceStoredSession();

    expect(await screen.findByRole('heading', { level: 1, name: 'The Long Night' })).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(authorizationOf(1)).toBe('Bearer rotated-access-token');
  });

  it('drops an admin result and personal data when a different regular user signs in', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(movieDetail({
      userRating: 5,
      inWatchlist: true,
      status: 'DRAFT'
    })))
      .mockResolvedValueOnce(apiError(404, 'MOVIE_NOT_FOUND', 'Movie not found'));
    renderDetail({ user: admin });
    await screen.findByRole('heading', { level: 2, name: 'Movie administration' });

    persistSession(otherUser, 'other');
    announceStoredSession();

    expect(await screen.findByRole('heading', { level: 1, name: 'Movie not found' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Movie administration' })).not.toBeInTheDocument();
    expect(screen.queryByText('Your rating')).not.toBeInTheDocument();
    expect(screen.queryByText('In your watchlist')).not.toBeInTheDocument();
    expect(authorizationOf(1)).toBe('Bearer other-access-token');
  });
});

function renderDetail({
  path = `/movies/${movieId}`,
  user = regularUser,
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
}: { path?: string; user?: UserProfile | null; queryClient?: QueryClient } = {}) {
  if (user) persistSession(user);
  const redirectToLogin = vi.fn();
  const view = render(
    <MemoryRouter
      future={{ v7_relativeSplatPath: true, v7_startTransition: true }}
      initialEntries={[path]}
    >
      <QueryClientProvider client={queryClient}>
        <AuthProvider redirectToLogin={redirectToLogin}>
          <LocationProbe />
          <AppRoutes />
        </AuthProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
  return { ...view, queryClient, redirectToLogin };
}

function LocationProbe() {
  const location = useLocation();
  const navigate = useNavigate();
  return (
    <>
      <span className="sr-only" data-testid="location-state">{JSON.stringify(location.state)}</span>
      <button onClick={() => navigate(`/movies/${nextMovieId}`)} type="button">Next movie</button>
    </>
  );
}

function fieldValue(label: string): HTMLElement {
  const term = screen.getByText(label, { selector: 'dt' });
  const definition = term.nextElementSibling;
  if (!(definition instanceof HTMLElement) || definition.tagName !== 'DD') {
    throw new Error(`${label} has no definition`);
  }
  return definition;
}

function headingSection(name: string): HTMLElement {
  const section = screen.getByRole('heading', { name }).closest('section');
  if (!section) throw new Error(`${name} has no section`);
  return section;
}

function movieDetail(overrides: Partial<MovieDetail> = {}): MovieDetail {
  return {
    id: movieId,
    title: 'The Long Night',
    slug: 'the-long-night',
    description: 'A storm traps a crew at sea.',
    releaseYear: 2019,
    posterUrl: null,
    genres: ['Drama', 'Thriller'],
    averageRating: null,
    status: 'PUBLISHED',
    videoStatus: null,
    maturityRating: 'T16',
    people: [
      { personId: '0b8f3a52-6a3e-4d0c-9a4b-3f2e1d0c9b8a', name: 'Actor First', role: 'ACTOR' },
      { personId: '1c9e4b63-7b4f-4e1d-8b5c-4a3f2e1d0c9b', name: 'Director First', role: 'DIRECTOR' },
      { personId: 'd8635ad2-7dc9-478d-8c42-2c486d030d48', name: 'Actor Second', role: 'ACTOR' }
    ],
    userRating: null,
    inWatchlist: false,
    playbackProgress: null,
    ...overrides
  };
}

function persistSession(user: UserProfile, tokenPrefix?: string) {
  const response: AuthResponse = {
    user,
    accessToken: tokenPrefix ? `${tokenPrefix}-access-token` : 'access-token',
    refreshToken: tokenPrefix ? `${tokenPrefix}-refresh-token` : 'refresh-token',
    expiresInSeconds: 900
  };
  writeAuthSession(createAuthSession(response), window.localStorage);
}

function announceStoredSession() {
  act(() => window.dispatchEvent(new StorageEvent('storage', { key: AUTH_SESSION_STORAGE_KEY })));
}

function deferredResponse() {
  let resolveResponse: (response: Response) => void = () => undefined;
  const promise = new Promise<Response>((resolve) => { resolveResponse = resolve; });
  return {
    promise,
    release: (response: Response) => act(() => resolveResponse(response))
  };
}

function requestPaths(): string[] {
  return fetchMock.mock.calls.map(([input]) => new URL(String(input), 'http://localhost').pathname);
}

function authorizationOf(callIndex: number): string | null {
  return new Headers(fetchMock.mock.calls[callIndex]?.[1]?.headers).get('Authorization');
}

function apiError(status: number, code: string, message: string, requestId?: string): Response {
  return jsonResponse({ timestamp: '2026-10-28T00:00:00Z', status, code, message, requestId }, status);
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}
