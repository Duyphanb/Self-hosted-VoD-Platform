import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../../../app/AppRoutes';
import type { UserProfile } from '../../../lib/api/types';
import { installCookieSession } from '../../../test/cookieSession';
import { AuthProvider } from '../../auth/AuthContext';
import { createAuthSession } from '../../auth/authStorage';
import type { MovieDetail } from '../../movies/moviesApi';
import type { HlsPlayerProps } from '../HlsPlayer';
import type { PlaybackInfo } from '../playbackApi';

/** The player has its own tests; here it records the props the page gives it. */
const { playerRenders } = vi.hoisted(() => ({ playerRenders: [] as HlsPlayerProps[] }));

vi.mock('../HlsPlayer', () => ({
  HlsPlayer: (props: HlsPlayerProps) => {
    playerRenders.push(props);
    return <div aria-label={`Player for ${props.title}`} role="application" />;
  }
}));

const MOVIE_ID = '6f1c0f55-2d4a-4b64-9a43-1d0f3c7b8e21';
const ASSET_ID = '5f1e2d3c-4b5a-4968-8776-655443322110';
const NEW_ASSET_ID = '9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d';
const VIEWER: UserProfile = {
  id: '3fcf25c2-f096-4462-ae63-4ba4702a9078',
  email: 'viewer@example.com',
  displayName: 'Viewer',
  roles: ['ROLE_USER']
};

const fetchMock = vi.fn<typeof fetch>();

interface ApiAnswers {
  movie: () => Response | Promise<Response>;
  playback: () => Response | Promise<Response>;
}

describe('WatchPage through AppRoutes', () => {
  let answers: ApiAnswers;

  beforeEach(() => {
    playerRenders.length = 0;
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

      expect(await screen.findByRole('application', { name: 'Player for The Long Night' }, { timeout: 5_000 }))
        .toBeInTheDocument();
      expect(screen.getByRole('heading', { level: 1, name: 'The Long Night' })).toBeInTheDocument();
      expect(screen.getByText('A storm traps a crew at sea.')).toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'Back to movie details' })).toHaveAttribute('href', `/movies/${MOVIE_ID}`);
      const props = playerRenders.at(-1)!;
      expect(props.movieId).toBe(MOVIE_ID);
      expect(props.title).toBe('The Long Night');
      expect(props.playbackInfo).toEqual(playbackInfo());
    });

    it('opens from the Watch link of the movie detail page', async () => {
      renderWatchPage(`/movies/${MOVIE_ID}`);

      fireEvent.click(await screen.findByRole('link', { name: 'Watch' }, { timeout: 5_000 }));

      expect(await screen.findByRole('application', { name: 'Player for The Long Night' })).toBeInTheDocument();
    });

    it('lets the player fetch the playback info again without reloading the page', async () => {
      renderWatchPage();
      await screen.findByRole('application', { name: 'Player for The Long Night' }, { timeout: 5_000 });
      answers.playback = () => jsonResponse(playbackInfo({ videoAssetId: NEW_ASSET_ID }));
      const rendersBefore = playerRenders.length;

      const reloaded = await playerRenders.at(-1)!.reloadPlaybackInfo();

      expect(reloaded.videoAssetId).toBe(NEW_ASSET_ID);
      expect(playbackRequests()).toBe(2);
      expect(playerRenders.length).toBe(rendersBefore);
    });

    it('lets the player see why the playback info could not be fetched again', async () => {
      renderWatchPage();
      await screen.findByRole('application', { name: 'Player for The Long Night' }, { timeout: 5_000 });
      answers.playback = () => apiError(409, 'VIDEO_NOT_READY', 'Video is not ready');

      await expect(playerRenders.at(-1)!.reloadPlaybackInfo()).rejects.toMatchObject({ status: 409 });

      expect(screen.getByRole('application', { name: 'Player for The Long Night' })).toBeInTheDocument();
    });
  });

  describe('loading', () => {
    it('announces the loading state while the movie and its playback info load', async () => {
      answers.movie = () => new Promise<Response>(() => undefined);
      answers.playback = () => new Promise<Response>(() => undefined);

      renderWatchPage();

      expect(await screen.findByRole('heading', { level: 1, name: 'Watch' }, { timeout: 5_000 })).toBeInTheDocument();
      expect(screen.getByText('Loading movie…').closest('[role="status"]')).not.toBeNull();
      expect(screen.queryByRole('application')).not.toBeInTheDocument();
    });
  });

  describe('not ready (409 VIDEO_NOT_READY)', () => {
    it.each([
      ['PROCESSING', 'Processing', 'This movie is still being processed. Check back later.'],
      ['QUEUED', 'Processing', 'This movie is still being processed. Check back later.'],
      ['FAILED', 'Not available', 'This movie cannot be watched right now.'],
      ['READY', 'Not available', 'This movie cannot be watched right now.']
    ] as const)('shows "%s" movies as %s, without the failure code or message', async (videoStatus, label, message) => {
      answers.movie = () => jsonResponse(movieDetail({ videoStatus }));
      answers.playback = () => apiError(409, 'VIDEO_NOT_READY', 'Video is not ready for playback');

      renderWatchPage();

      const notice = await screen.findByRole('heading', { level: 2, name: label }, { timeout: 5_000 });
      expect(notice.parentElement).toHaveTextContent(message);
      expect(screen.getByRole('heading', { level: 1, name: 'The Long Night' })).toBeInTheDocument();
      expect(screen.queryByText(/VIDEO_NOT_READY/)).not.toBeInTheDocument();
      expect(screen.queryByText(/not ready for playback/)).not.toBeInTheDocument();
      expect(screen.queryByRole('application')).not.toBeInTheDocument();
    });
  });

  describe('not found', () => {
    it('shows the not-found state for 404 MOVIE_NOT_FOUND from the playback API', async () => {
      answers.playback = () => apiError(404, 'MOVIE_NOT_FOUND', 'Movie not found');

      renderWatchPage();

      expect(await screen.findByRole('heading', { level: 1, name: 'Movie not found' }, { timeout: 5_000 }))
        .toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'Back to movies' })).toHaveAttribute('href', '/movies');
      expect(screen.queryByRole('application')).not.toBeInTheDocument();
    });

    it('shows the not-found state when the movie itself is not found', async () => {
      answers.movie = () => apiError(404, 'MOVIE_NOT_FOUND', 'Movie not found');

      renderWatchPage();

      expect(await screen.findByRole('heading', { level: 1, name: 'Movie not found' }, { timeout: 5_000 }))
        .toBeInTheDocument();
    });

    it('shows the not-found state for an address that is not a movie ID, without a request', async () => {
      renderWatchPage('/movies/not-a-movie/watch');

      expect(await screen.findByRole('heading', { level: 1, name: 'Movie not found' }, { timeout: 5_000 }))
        .toBeInTheDocument();
      expect(playbackRequests()).toBe(0);
    });
  });

  describe('errors', () => {
    it('shows the error state with Retry, and plays once Retry succeeds', async () => {
      answers.playback = () => apiError(500, 'INTERNAL_ERROR', 'Something went wrong');

      renderWatchPage();

      const alert = await screen.findByRole('alert', {}, { timeout: 5_000 });
      expect(within(alert).getByRole('heading', { level: 1, name: 'Movie could not be loaded' })).toBeInTheDocument();
      answers.playback = () => jsonResponse(playbackInfo());
      fireEvent.click(within(alert).getByRole('button', { name: 'Retry' }));

      expect(await screen.findByRole('application', { name: 'Player for The Long Night' })).toBeInTheDocument();
      expect(playbackRequests()).toBe(2);
    });

    it('shows the error state when the movie cannot be loaded', async () => {
      answers.movie = () => apiError(500, 'INTERNAL_ERROR', 'Something went wrong');

      renderWatchPage();

      const alert = await screen.findByRole('alert', {}, { timeout: 5_000 });
      expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
      expect(screen.queryByRole('application')).not.toBeInTheDocument();
    });
  });
});

function renderWatchPage(path = `/movies/${MOVIE_ID}/watch`) {
  installCookieSession(
    createAuthSession({ user: VIEWER, accessToken: 'access-token', expiresInSeconds: 900 }),
    window.localStorage
  );
  return render(
    <MemoryRouter future={{ v7_relativeSplatPath: true, v7_startTransition: true }} initialEntries={[path]}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <AuthProvider redirectToLogin={() => undefined}>
          <AppRoutes />
        </AuthProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
}

function playbackRequests(): number {
  return fetchMock.mock.calls
    .map(([input]) => new URL(String(input), 'http://localhost').pathname)
    .filter((path) => path.endsWith('/playback'))
    .length;
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
