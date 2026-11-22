import { onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../../../app/AppRoutes';
import { AuthenticationSupersededError } from '../../../lib/api/client';
import type { UserProfile } from '../../../lib/api/types';
import { installCookieSession } from '../../../test/cookieSession';
import { AuthProvider, useAuth } from '../../auth/AuthContext';
import * as authApi from '../../auth/authApi';
import { createAuthSession } from '../../auth/authStorage';
import { movieKeys, type MovieDetail } from '../../movies/moviesApi';
import type { HlsPlayerProps } from '../HlsPlayer';
import type { PlaybackInfo } from '../playbackApi';

/** The player has its own tests; here it records the props the page gives it and how often it mounts. */
const player = vi.hoisted(() => ({
  renders: [] as HlsPlayerProps[],
  mounts: 0,
  failToLoad: false
}));

vi.mock('../HlsPlayer', async () => {
  const { useEffect } = await import('react');
  return {
    HlsPlayer: (props: HlsPlayerProps) => {
      useEffect(() => {
        player.mounts += 1;
      }, []);
      if (player.failToLoad) {
        throw new Error('Failed to fetch dynamically imported module');
      }
      player.renders.push(props);
      return <div aria-label={`Player for ${props.title}`} role="application" />;
    }
  };
});

const MOVIE_ID = '6f1c0f55-2d4a-4b64-9a43-1d0f3c7b8e21';
const ASSET_ID = '5f1e2d3c-4b5a-4968-8776-655443322110';
const NEW_ASSET_ID = '9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d';
const VIEWER: UserProfile = {
  id: '3fcf25c2-f096-4462-ae63-4ba4702a9078',
  email: 'viewer@example.com',
  displayName: 'Viewer',
  roles: ['ROLE_USER']
};
const OTHER_VIEWER: UserProfile = {
  id: 'b5f7c0f4-1d2e-4c3b-9a8f-7e6d5c4b3a21',
  email: 'other@example.com',
  displayName: 'Other viewer',
  roles: ['ROLE_USER']
};
const PLAYER_NAME = 'Player for The Long Night';
const LOAD_TIMEOUT = { timeout: 5_000 };

const fetchMock = vi.fn<typeof fetch>();

interface ApiAnswers {
  movie: () => Response | Promise<Response>;
  playback: () => Response | Promise<Response>;
}

describe('WatchPage through AppRoutes', () => {
  let answers: ApiAnswers;

  beforeEach(() => {
    player.renders.length = 0;
    player.mounts = 0;
    player.failToLoad = false;
    answers = {
      movie: () => jsonResponse(movieDetail()),
      playback: () => jsonResponse(playbackInfo())
    };
    fetchMock.mockReset();
    fetchMock.mockImplementation(async (input) => {
      const path = new URL(String(input), 'http://localhost').pathname;
      if (path === `/api/v1/movies/${MOVIE_ID}/playback`) {
        return answers.playback();
      }
      if (path === `/api/v1/movies/${MOVIE_ID}`) {
        return answers.movie();
      }
      return apiError(404, 'NOT_FOUND', 'Unexpected request');
    });
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  describe('ready', () => {
    it('plays the movie at its resume position, with its title and description', async () => {
      renderWatchPage();

      expect(await screen.findByRole('application', { name: PLAYER_NAME }, LOAD_TIMEOUT)).toBeInTheDocument();
      expect(screen.getByRole('heading', { level: 1, name: 'The Long Night' })).toBeInTheDocument();
      expect(screen.getByText('A storm traps a crew at sea.')).toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'Back to movie details' }))
        .toHaveAttribute('href', `/movies/${MOVIE_ID}`);
      const props = player.renders.at(-1)!;
      expect(props.movieId).toBe(MOVIE_ID);
      expect(props.title).toBe('The Long Night');
      expect(props.playbackInfo).toEqual(playbackInfo());
    });

    it('opens from the Watch link of the movie detail page and moves focus to the page', async () => {
      renderWatchPage(`/movies/${MOVIE_ID}`);
      const watch = await screen.findByRole('link', { name: 'Watch' }, LOAD_TIMEOUT);
      watch.focus();

      fireEvent.click(watch);

      expect(await screen.findByRole('application', { name: PLAYER_NAME })).toBeInTheDocument();
      expect(screen.getByRole('region', { name: 'Watch movie' })).toHaveFocus();
    });

    it('keeps the player when a background refetch of the movie fails', async () => {
      const { queryClient } = renderWatchPage();
      await screen.findByRole('application', { name: PLAYER_NAME }, LOAD_TIMEOUT);
      answers.movie = () => apiError(503, 'SERVICE_UNAVAILABLE', 'Try again later');

      await act(async () => {
        await queryClient.refetchQueries({ queryKey: movieKeys.detail(MOVIE_ID) });
      });
      await renderQueryNotifications();

      expect(queryClient.getQueryState(movieKeys.detail(MOVIE_ID))?.status).toBe('error');
      expect(screen.getByRole('application', { name: PLAYER_NAME })).toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
      expect(player.mounts).toBe(1);
    });
  });

  describe('reconnect', () => {
    it('refetches the movie but not the playback info, and keeps the player', async () => {
      renderWatchPage();
      await screen.findByRole('application', { name: PLAYER_NAME }, LOAD_TIMEOUT);
      const playbackBefore = playbackRequests();
      answers.playback = () => apiError(503, 'SERVICE_UNAVAILABLE', 'Try again later');

      act(() => {
        onlineManager.setOnline(false);
        onlineManager.setOnline(true);
      });
      await waitFor(() => expect(movieRequests()).toBe(2));
      await renderQueryNotifications();

      expect(playbackRequests()).toBe(playbackBefore);
      expect(screen.getByRole('application', { name: PLAYER_NAME })).toBeInTheDocument();
      expect(player.mounts).toBe(1);
    });
  });

  describe('fetching the playback info again for the player (ADR-011 D7)', () => {
    it('fetches it again without reloading the page or the player', async () => {
      renderWatchPage();
      await screen.findByRole('application', { name: PLAYER_NAME }, LOAD_TIMEOUT);
      answers.playback = () => jsonResponse(playbackInfo({ videoAssetId: NEW_ASSET_ID }));

      const reloaded = await player.renders.at(-1)!.reloadPlaybackInfo();
      await renderQueryNotifications();

      expect(reloaded.videoAssetId).toBe(NEW_ASSET_ID);
      expect(playbackRequests()).toBe(2);
      expect(player.mounts).toBe(1);
    });

    it('passes the API error to the player and keeps the page as it is', async () => {
      renderWatchPage();
      await screen.findByRole('application', { name: PLAYER_NAME }, LOAD_TIMEOUT);
      answers.playback = () => apiError(409, 'VIDEO_NOT_READY', 'Video is not ready');

      await expect(player.renders.at(-1)!.reloadPlaybackInfo()).rejects.toMatchObject({ status: 409 });
      await settle();

      expect(screen.getByRole('application', { name: PLAYER_NAME })).toBeInTheDocument();
    });

    it('never fetches it for the player with the account that signs in next', async () => {
      renderWatchPage(`/movies/${MOVIE_ID}/watch`, { withSignInOtherViewer: true });
      await screen.findByRole('application', { name: PLAYER_NAME }, LOAD_TIMEOUT);
      const firstViewersReload = player.renders.at(-1)!.reloadPlaybackInfo;
      vi.spyOn(authApi, 'login').mockResolvedValue({
        user: OTHER_VIEWER,
        accessToken: 'other-access-token',
        expiresInSeconds: 900
      });
      fireEvent.click(screen.getByRole('button', { name: 'Sign in the other viewer' }));
      await screen.findByText(`Signed in as ${OTHER_VIEWER.id}`);
      // The page loads again for the new viewer and mounts a new player for them.
      await waitFor(() => expect(player.mounts).toBe(2));
      const requestsBefore = playbackRequests();

      await expect(firstViewersReload()).rejects.toBeInstanceOf(AuthenticationSupersededError);

      expect(playbackRequests()).toBe(requestsBefore);
    });
  });

  describe('loading', () => {
    it('announces the loading state while the movie and its playback info load', async () => {
      answers.movie = () => new Promise<Response>(() => undefined);
      answers.playback = () => new Promise<Response>(() => undefined);

      renderWatchPage();

      expect(await screen.findByRole('heading', { level: 1, name: 'Watch' }, LOAD_TIMEOUT)).toBeInTheDocument();
      expect(screen.getByText('Loading movie…').closest('[role="status"]')).not.toBeNull();
      expect(screen.queryByRole('application')).not.toBeInTheDocument();
    });
  });

  describe('not ready (409 VIDEO_NOT_READY)', () => {
    it.each([
      ['PROCESSING', 'Processing', 'This movie is still being processed. Check back later.'],
      ['QUEUED', 'Processing', 'This movie is still being processed. Check back later.'],
      ['FAILED', 'Not available', 'This movie cannot be watched right now.']
    ] as const)('shows "%s" movies as %s, without the failure code or message', async (videoStatus, label, message) => {
      answers.movie = () => jsonResponse(movieDetail({ videoStatus }));
      let releasePlayback: (response: Response) => void = () => undefined;
      answers.playback = () => new Promise<Response>((resolve) => {
        releasePlayback = resolve;
      });

      renderWatchPage();
      await screen.findByRole('heading', { level: 1, name: 'Watch' }, LOAD_TIMEOUT);
      const liveRegion = screen.getByRole('region', { name: 'Watch movie' }).querySelector('p.sr-only[role="status"]');
      expect(liveRegion).toHaveTextContent('');
      await act(async () => {
        releasePlayback(apiError(409, 'VIDEO_NOT_READY', 'Video is not ready for playback'));
      });

      expect(await screen.findByRole('heading', { level: 2, name: label }, LOAD_TIMEOUT)).toBeInTheDocument();
      expect(liveRegion).toHaveTextContent(label);
      const notice = await screen.findByRole('heading', { level: 2, name: label }, LOAD_TIMEOUT);
      expect(notice.parentElement).toHaveTextContent(message);
      expect(screen.getByRole('heading', { level: 1, name: 'The Long Night' })).toBeInTheDocument();
      expect(screen.getByText('A storm traps a crew at sea.')).toBeInTheDocument();
      expect(screen.getAllByRole('status').some((status) => status.textContent === label)).toBe(true);
      expect(screen.queryByText(/VIDEO_NOT_READY/)).not.toBeInTheDocument();
      expect(screen.queryByText(/not ready for playback/)).not.toBeInTheDocument();
      expect(screen.queryByRole('application')).not.toBeInTheDocument();
    });

    it('asks once more when the movie already reports READY, and plays when the video is ready', async () => {
      let playbackAnswer = 0;
      answers.playback = () => {
        playbackAnswer += 1;
        return playbackAnswer === 1
          ? apiError(409, 'VIDEO_NOT_READY', 'Video is not ready')
          : jsonResponse(playbackInfo());
      };

      renderWatchPage();

      expect(await screen.findByRole('application', { name: PLAYER_NAME }, LOAD_TIMEOUT)).toBeInTheDocument();
      expect(playbackRequests()).toBe(2);
    });

    it('shows "Not available" when the movie reports READY but the video stays unavailable', async () => {
      answers.playback = () => apiError(409, 'VIDEO_NOT_READY', 'Video is not ready');

      renderWatchPage();

      expect(await screen.findByRole('heading', { level: 2, name: 'Not available' }, LOAD_TIMEOUT))
        .toBeInTheDocument();
      await waitFor(() => expect(playbackRequests()).toBe(2));
      await settle();
      expect(playbackRequests()).toBe(2);
    });

    it('plays after Check again once the video became ready', async () => {
      answers.movie = () => jsonResponse(movieDetail({ videoStatus: 'PROCESSING' }));
      answers.playback = () => apiError(409, 'VIDEO_NOT_READY', 'Video is not ready');
      renderWatchPage();
      await screen.findByRole('heading', { level: 2, name: 'Processing' }, LOAD_TIMEOUT);
      answers.movie = () => jsonResponse(movieDetail());
      answers.playback = () => jsonResponse(playbackInfo());

      const movieRequestsBefore = movieRequests();
      const playbackRequestsBefore = playbackRequests();

      fireEvent.click(screen.getByRole('button', { name: 'Check again' }));

      expect(await screen.findByRole('application', { name: PLAYER_NAME })).toBeInTheDocument();
      expect(movieRequests()).toBe(movieRequestsBefore + 1);
      expect(playbackRequests()).toBeGreaterThanOrEqual(playbackRequestsBefore + 1);
    });
  });

  describe('not found', () => {
    it('shows the not-found state for 404 MOVIE_NOT_FOUND from the playback API', async () => {
      answers.playback = () => apiError(404, 'MOVIE_NOT_FOUND', 'Movie not found');

      renderWatchPage();

      expect(await screen.findByRole('heading', { level: 1, name: 'Movie not found' }, LOAD_TIMEOUT))
        .toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'Back to movies' })).toHaveAttribute('href', '/movies');
      expect(screen.queryByRole('application')).not.toBeInTheDocument();
    });

    it('shows the not-found state when the movie itself is not found', async () => {
      answers.movie = () => apiError(404, 'MOVIE_NOT_FOUND', 'Movie not found');

      renderWatchPage();

      expect(await screen.findByRole('heading', { level: 1, name: 'Movie not found' }, LOAD_TIMEOUT))
        .toBeInTheDocument();
    });

    it('shows the not-found state for an address that is not a movie ID, without a request', async () => {
      renderWatchPage('/movies/not-a-movie/watch');

      expect(await screen.findByRole('heading', { level: 1, name: 'Movie not found' }, LOAD_TIMEOUT))
        .toBeInTheDocument();
      expect(playbackRequests()).toBe(0);
    });
  });

  describe('errors', () => {
    it('shows the error state with Retry, and plays once Retry succeeds', async () => {
      answers.playback = () => apiError(500, 'INTERNAL_ERROR', 'Something went wrong');

      renderWatchPage();

      const alert = await screen.findByRole('alert', {}, LOAD_TIMEOUT);
      expect(within(alert).getByRole('heading', { level: 1, name: 'Movie could not be loaded' }))
        .toBeInTheDocument();
      answers.playback = () => jsonResponse(playbackInfo());
      fireEvent.click(within(alert).getByRole('button', { name: 'Retry' }));

      expect(await screen.findByRole('application', { name: PLAYER_NAME })).toBeInTheDocument();
      expect(playbackRequests()).toBe(2);
    });

    it('retries the movie when it could not be loaded, and plays once it loads', async () => {
      answers.movie = () => apiError(500, 'INTERNAL_ERROR', 'Something went wrong');
      renderWatchPage();
      const alert = await screen.findByRole('alert', {}, LOAD_TIMEOUT);
      answers.movie = () => jsonResponse(movieDetail());

      fireEvent.click(within(alert).getByRole('button', { name: 'Retry' }));

      expect(await screen.findByRole('application', { name: PLAYER_NAME })).toBeInTheDocument();
      expect(movieRequests()).toBe(2);
    });

    it.each([
      ['400 on a valid movie address', () => apiError(400, 'VALIDATION_ERROR', 'Invalid request')],
      ['404 without MOVIE_NOT_FOUND', () => apiError(404, 'NOT_FOUND', 'No route')],
      ['409 without VIDEO_NOT_READY', () => apiError(409, 'CONFLICT', 'Conflict')]
    ])('shows the error state with Retry for %s', async (_name, answer) => {
      answers.playback = answer;

      renderWatchPage();

      const alert = await screen.findByRole('alert', {}, LOAD_TIMEOUT);
      expect(within(alert).getByRole('button', { name: 'Retry' })).toBeEnabled();
      expect(screen.queryByRole('heading', { name: 'Movie not found' })).not.toBeInTheDocument();
    });

    it('lets the viewer retry a failed request while the other one is still loading', async () => {
      answers.movie = () => new Promise<Response>(() => undefined);
      answers.playback = () => apiError(500, 'INTERNAL_ERROR', 'Something went wrong');

      renderWatchPage();

      const alert = await screen.findByRole('alert', {}, LOAD_TIMEOUT);
      expect(within(alert).getByRole('button', { name: 'Retry' })).toBeEnabled();
      fireEvent.click(within(alert).getByRole('button', { name: 'Retry' }));
      await waitFor(() => expect(playbackRequests()).toBe(2));
    });

    it('keeps the page and offers a reload when the player code cannot be loaded', async () => {
      vi.spyOn(console, 'error').mockImplementation(() => undefined);
      player.failToLoad = true;

      renderWatchPage();

      const alert = await screen.findByRole('alert', {}, LOAD_TIMEOUT);
      expect(alert).toHaveTextContent('The player could not be loaded.');
      expect(within(alert).getByRole('button', { name: 'Reload page' })).toBeInTheDocument();
      expect(screen.getByRole('heading', { level: 1, name: 'The Long Night' })).toBeInTheDocument();
    });
  });
});

function renderWatchPage(path = `/movies/${MOVIE_ID}/watch`, { withSignInOtherViewer = false } = {}) {
  installCookieSession(
    createAuthSession({ user: VIEWER, accessToken: 'access-token', expiresInSeconds: 900 }),
    window.localStorage
  );
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(
    <MemoryRouter future={{ v7_relativeSplatPath: true, v7_startTransition: true }} initialEntries={[path]}>
      <QueryClientProvider client={queryClient}>
        <AuthProvider redirectToLogin={() => undefined}>
          {withSignInOtherViewer ? <SignInOtherViewer /> : null}
          <AppRoutes />
        </AuthProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
  return { ...view, queryClient };
}

function SignInOtherViewer() {
  const auth = useAuth();
  const signIn = () => {
    void auth.login({ email: OTHER_VIEWER.email, password: 'fixture-password' }).catch(() => undefined);
  };
  return (
    <div>
      <p>Signed in as {auth.user?.id ?? 'nobody'}</p>
      <button onClick={signIn} type="button">Sign in the other viewer</button>
    </div>
  );
}

/** TanStack Query notifies components in a later task, so wait for one before checking what renders. */
async function renderQueryNotifications(): Promise<void> {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 20));
  });
}

async function settle(): Promise<void> {
  await act(async () => {
    for (let step = 0; step < 20; step += 1) {
      await Promise.resolve();
    }
  });
}

function requestCount(suffix: string): number {
  return fetchMock.mock.calls
    .map(([input]) => new URL(String(input), 'http://localhost').pathname)
    .filter((path) => path.endsWith(suffix))
    .length;
}

function playbackRequests(): number {
  return requestCount('/playback');
}

function movieRequests(): number {
  return requestCount(`/movies/${MOVIE_ID}`);
}

function movieDetail(overrides: Partial<MovieDetail> = {}): MovieDetail {
  return {
    id: MOVIE_ID,
    title: 'The Long Night',
    slug: 'the-long-night',
    description: 'A storm traps a crew at sea.',
    releaseYear: 2019,
    posterUrl: null,
    genres: ['Drama'],
    averageRating: null,
    status: 'PUBLISHED',
    videoStatus: 'READY',
    maturityRating: 'T16',
    people: [],
    userRating: null,
    inWatchlist: false,
    playbackProgress: null,
    ...overrides
  };
}

function playbackInfo(overrides: Partial<PlaybackInfo> = {}): PlaybackInfo {
  const videoAssetId = overrides.videoAssetId ?? ASSET_ID;
  return {
    movieId: MOVIE_ID,
    videoAssetId,
    hlsManifestUrl: `/hls/video-assets/${videoAssetId}/master.m3u8`,
    resumeSeconds: 754,
    durationSeconds: 3600,
    ...overrides
  };
}

function apiError(status: number, code: string, message: string): Response {
  return jsonResponse({ timestamp: '2026-11-22T00:00:00Z', status, code, message }, status);
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}
