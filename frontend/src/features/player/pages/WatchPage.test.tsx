import { onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../../../app/AppRoutes';
import { AuthenticationSupersededError } from '../../../lib/api/client';
import type { UserProfile } from '../../../lib/api/types';
import { installCookieSession } from '../../../test/cookieSession';
import { AuthProvider, useAuth } from '../../auth/AuthContext';
import * as authApi from '../../auth/authApi';
import { AUTH_GENERATION_STORAGE_KEY, createAuthSession } from '../../auth/authStorage';
import { movieKeys, type MovieDetail } from '../../movies/moviesApi';
import { registerClosingPlayerSaves } from '../closingPlayerSaves';
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
const SECOND_MOVIE_ID = '7334b2f1-664f-4bc1-b8f1-10de58c1d74a';
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

  describe('a player of this movie that just closed (ADR-011 D11)', () => {
    it('asks for the playback info only after that player\'s last save is done', async () => {
      let releaseSave: () => void = () => undefined;
      registerClosingPlayerSaves(MOVIE_ID, VIEWER.id, new Promise<void>((resolve) => {
        releaseSave = resolve;
      }));

      renderWatchPage();
      await waitFor(() => expect(movieRequests()).toBe(1), LOAD_TIMEOUT);
      await renderQueryNotifications();
      expect(playbackRequests()).toBe(0);

      releaseSave();

      expect(await screen.findByRole('application', { name: PLAYER_NAME }, LOAD_TIMEOUT)).toBeInTheDocument();
      expect(playbackRequests()).toBe(1);
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

    it('loads again and mounts a new player when the same viewer gets a new session', async () => {
      renderWatchPage();
      await screen.findByRole('application', { name: PLAYER_NAME }, LOAD_TIMEOUT);
      const playbackBefore = playbackRequests();
      vi.spyOn(authApi, 'refresh').mockResolvedValue({
        user: VIEWER,
        accessToken: 'second-session-access-token',
        expiresInSeconds: 900
      });

      // Another tab signed the same viewer in again. The restore finishes before React renders, so the
      // route guard never shows its loading state and the page stays mounted.
      await act(async () => {
        startNewSessionOfSameViewer();
        await flushMicrotasks();
      });

      await waitFor(() => expect(player.mounts).toBe(2));
      expect(playbackRequests()).toBe(playbackBefore + 1);
      expect(screen.getByRole('application', { name: PLAYER_NAME })).toBeInTheDocument();
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

    it('rechecks once for each movie, also after another movie used its recheck', async () => {
      let firstMovieAnswers = 0;
      let secondMovieAnswers = 0;
      fetchMock.mockImplementation(async (input) => {
        const path = new URL(String(input), 'http://localhost').pathname;
        if (path === `/api/v1/movies/${MOVIE_ID}`) {
          return jsonResponse(movieDetail());
        }
        if (path === `/api/v1/movies/${SECOND_MOVIE_ID}`) {
          return jsonResponse(movieDetail({ id: SECOND_MOVIE_ID, title: 'Second Movie' }));
        }
        if (path === `/api/v1/movies/${MOVIE_ID}/playback`) {
          firstMovieAnswers += 1;
          return apiError(409, 'VIDEO_NOT_READY', 'Video is not ready');
        }
        if (path === `/api/v1/movies/${SECOND_MOVIE_ID}/playback`) {
          secondMovieAnswers += 1;
          return secondMovieAnswers === 1
            ? apiError(409, 'VIDEO_NOT_READY', 'Video is not ready')
            : jsonResponse(playbackInfo({ movieId: SECOND_MOVIE_ID }));
        }
        return apiError(404, 'NOT_FOUND', 'Unexpected request');
      });
      renderWatchPage(`/movies/${MOVIE_ID}/watch`, { withSecondMovieLink: true });
      await screen.findByRole('heading', { level: 2, name: 'Not available' }, LOAD_TIMEOUT);
      await waitFor(() => expect(firstMovieAnswers).toBe(2));

      fireEvent.click(screen.getByRole('button', { name: 'Go to the second movie' }));

      expect(await screen.findByRole('application', { name: 'Player for Second Movie' }, LOAD_TIMEOUT))
        .toBeInTheDocument();
      expect(secondMovieAnswers).toBe(2);
    });

    it('gives a return to a movie its own recheck, after another movie played at once', async () => {
      let firstMovieAnswers = 0;
      fetchMock.mockImplementation(async (input) => {
        const path = new URL(String(input), 'http://localhost').pathname;
        if (path === `/api/v1/movies/${MOVIE_ID}`) {
          return jsonResponse(movieDetail());
        }
        if (path === `/api/v1/movies/${SECOND_MOVIE_ID}`) {
          return jsonResponse(movieDetail({ id: SECOND_MOVIE_ID, title: 'Second Movie' }));
        }
        if (path === `/api/v1/movies/${MOVIE_ID}/playback`) {
          firstMovieAnswers += 1;
          // The first visit stays unavailable after its recheck; the return visit plays after its own.
          return firstMovieAnswers === 4
            ? jsonResponse(playbackInfo())
            : apiError(409, 'VIDEO_NOT_READY', 'Video is not ready');
        }
        if (path === `/api/v1/movies/${SECOND_MOVIE_ID}/playback`) {
          return jsonResponse(playbackInfo({ movieId: SECOND_MOVIE_ID }));
        }
        return apiError(404, 'NOT_FOUND', 'Unexpected request');
      });
      renderWatchPage(`/movies/${MOVIE_ID}/watch`, { withSecondMovieLink: true });
      await screen.findByRole('heading', { level: 2, name: 'Not available' }, LOAD_TIMEOUT);
      await waitFor(() => expect(firstMovieAnswers).toBe(2));
      fireEvent.click(screen.getByRole('button', { name: 'Go to the second movie' }));
      await screen.findByRole('application', { name: 'Player for Second Movie' }, LOAD_TIMEOUT);

      fireEvent.click(screen.getByRole('button', { name: 'Go back' }));

      expect(await screen.findByRole('application', { name: PLAYER_NAME }, LOAD_TIMEOUT)).toBeInTheDocument();
      expect(firstMovieAnswers).toBe(4);
    });

    it('waits for the fresh movie before it chooses the label, instead of using the cached one', async () => {
      // The detail page cached the movie as READY; a new upload started before the viewer clicked Watch.
      renderWatchPage(`/movies/${MOVIE_ID}`);
      const watch = await screen.findByRole('link', { name: 'Watch' }, LOAD_TIMEOUT);
      let releaseMovie: (response: Response) => void = () => undefined;
      answers.movie = () => new Promise<Response>((resolve) => {
        releaseMovie = resolve;
      });
      answers.playback = () => apiError(409, 'VIDEO_NOT_READY', 'Video is not ready');

      fireEvent.click(watch);
      await waitFor(() => expect(playbackRequests()).toBe(1));
      await renderQueryNotifications();

      expect(screen.getByRole('heading', { level: 1, name: 'Watch' })).toBeInTheDocument();
      expect(screen.queryByRole('heading', { level: 2, name: 'Not available' })).not.toBeInTheDocument();
      // The cached READY must not trigger the recheck that only a fresh READY earns.
      expect(playbackRequests()).toBe(1);
      await act(async () => {
        releaseMovie(jsonResponse(movieDetail({ videoStatus: 'PROCESSING' })));
      });
      expect(await screen.findByRole('heading', { level: 2, name: 'Processing' }, LOAD_TIMEOUT))
        .toBeInTheDocument();
    });

    it('asks for the movie and its playback info at once on Check again, and plays once ready', async () => {
      answers.movie = () => jsonResponse(movieDetail({ videoStatus: 'PROCESSING' }));
      answers.playback = () => apiError(409, 'VIDEO_NOT_READY', 'Video is not ready');
      renderWatchPage();
      await screen.findByRole('heading', { level: 2, name: 'Processing' }, LOAD_TIMEOUT);
      let releaseMovie: (response: Response) => void = () => undefined;
      answers.movie = () => new Promise<Response>((resolve) => {
        releaseMovie = resolve;
      });
      answers.playback = () => jsonResponse(playbackInfo());
      const movieRequestsBefore = movieRequests();
      const playbackRequestsBefore = playbackRequests();

      fireEvent.click(screen.getByRole('button', { name: 'Check again' }));

      // The playback request starts while the movie request is still pending, not after its answer.
      await waitFor(() => expect(playbackRequests()).toBe(playbackRequestsBefore + 1));
      expect(movieRequests()).toBe(movieRequestsBefore + 1);
      await act(async () => {
        releaseMovie(jsonResponse(movieDetail()));
      });
      expect(await screen.findByRole('application', { name: PLAYER_NAME })).toBeInTheDocument();
      expect(playbackRequests()).toBe(playbackRequestsBefore + 1);
    });

    it('keeps the label and an enabled, focused Check again while a reconnect refreshes the movie', async () => {
      answers.movie = () => jsonResponse(movieDetail({ videoStatus: 'PROCESSING' }));
      answers.playback = () => apiError(409, 'VIDEO_NOT_READY', 'Video is not ready');
      renderWatchPage();
      const checkAgain = await screen.findByRole('button', { name: 'Check again' }, LOAD_TIMEOUT);
      checkAgain.focus();
      answers.movie = () => new Promise<Response>(() => undefined);
      const movieRequestsBefore = movieRequests();

      act(() => {
        onlineManager.setOnline(false);
        onlineManager.setOnline(true);
      });
      await waitFor(() => expect(movieRequests()).toBe(movieRequestsBefore + 1));
      await renderQueryNotifications();

      expect(screen.getByRole('heading', { level: 2, name: 'Processing' })).toBeInTheDocument();
      const stillCheckAgain = screen.getByRole('button', { name: 'Check again' });
      expect(stillCheckAgain).toBe(checkAgain);
      expect(stillCheckAgain).toBeEnabled();
      expect(stillCheckAgain).toHaveFocus();
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

function renderWatchPage(
  path = `/movies/${MOVIE_ID}/watch`,
  { withSignInOtherViewer = false, withSecondMovieLink = false } = {}
) {
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
          {withSecondMovieLink ? <GoToSecondMovie /> : null}
          <AppRoutes />
        </AuthProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
  return { ...view, queryClient };
}

/** In-app navigation to another watch address and back: the same page component stays mounted. */
function GoToSecondMovie() {
  const navigate = useNavigate();
  return (
    <>
      <button onClick={() => navigate(`/movies/${SECOND_MOVIE_ID}/watch`)} type="button">
        Go to the second movie
      </button>
      <button onClick={() => navigate(-1)} type="button">Go back</button>
    </>
  );
}

/** What a sign-in of the same viewer in another tab leaves behind: a new session generation. */
function startNewSessionOfSameViewer() {
  const marker = JSON.parse(window.localStorage.getItem(AUTH_GENERATION_STORAGE_KEY) ?? 'null');
  const nextMarker = { ...marker, generation: marker.generation + 1 };
  window.localStorage.setItem(AUTH_GENERATION_STORAGE_KEY, JSON.stringify(nextMarker));
  window.dispatchEvent(new StorageEvent('storage', { key: AUTH_GENERATION_STORAGE_KEY }));
}

async function flushMicrotasks(): Promise<void> {
  for (let step = 0; step < 50; step += 1) {
    await Promise.resolve();
  }
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
