import { onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../../../app/AppRoutes';
import type { UserProfile } from '../../../lib/api/types';
import { installCookieSession } from '../../../test/cookieSession';
import { AuthProvider } from '../../auth/AuthContext';
import * as authApi from '../../auth/authApi';
import { AUTH_GENERATION_STORAGE_KEY, createAuthSession } from '../../auth/authStorage';
import type { MovieSummary, PlaybackProgress } from '../../movies/moviesApi';
import { continueFixture, filmEpisodeId } from '../../../test/titleWireFixture';
import { progressKeys } from '../continueWatchingQueries';

const FIRST_MOVIE_ID = '6f1c0f55-2d4a-4b64-9a43-1d0f3c7b8e21';
const SECOND_MOVIE_ID = '7334b2f1-664f-4bc1-b8f1-10de58c1d74a';
const THIRD_MOVIE_ID = '5a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d';
const VIEWER: UserProfile = {
  id: '3fcf25c2-f096-4462-ae63-4ba4702a9078',
  email: 'viewer@example.com',
  displayName: 'Viewer',
  roles: ['ROLE_USER']
};
const PAGE_PATH = '/continue-watching';
const LIST_PATH = '/api/v1/me/continue-watching';
const LOAD_TIMEOUT = { timeout: 5_000 };

const fetchMock = vi.fn<typeof fetch>();

describe('ContinueWatchingPage through AppRoutes', () => {
  let answerList: () => Response | Promise<Response>;

  beforeEach(() => {
    answerList = () => jsonResponse([
      item(FIRST_MOVIE_ID, 'The Long Night', { currentSeconds: 754, durationSeconds: 3600 }),
      item(SECOND_MOVIE_ID, 'Second Movie', { currentSeconds: 3725, durationSeconds: 7200 })
    ]);
    fetchMock.mockReset();
    fetchMock.mockImplementation(async (input) => {
      const path = new URL(String(input), 'http://localhost').pathname;
      if (path === LIST_PATH) {
        return answerList();
      }
      return apiError(404, 'NOT_FOUND', 'Unexpected request');
    });
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  describe('the list', () => {
    it('shows each movie in the order of the API, with where it resumes and the time left', async () => {
      renderPage();

      const cards = await findCards();
      expect(cards).toHaveLength(2);
      expect(cards[0]).toHaveTextContent('The Long Night');
      expect(cards[0]).toHaveTextContent('Resume at 12:34');
      expect(cards[0]).toHaveTextContent('48 min left');
      expect(cards[1]).toHaveTextContent('Second Movie');
      expect(cards[1]).toHaveTextContent('Resume at 1:02:05');
      expect(cards[1]).toHaveTextContent('58 min left');
      expect(screen.getByText('2 movies')).toBeInTheDocument();
    });

    it('keeps the order of the API, never sorting by ID or title', async () => {
      // Most recently watched first, as the API orders them; neither the IDs nor the titles are in this order.
      answerList = () => jsonResponse([
        item(SECOND_MOVIE_ID, 'Beta', { lastPlayedAt: '2026-11-22T12:00:00Z' }),
        item(THIRD_MOVIE_ID, 'Charlie', { lastPlayedAt: '2026-11-22T11:00:00Z' }),
        item(FIRST_MOVIE_ID, 'Alpha', { lastPlayedAt: '2026-11-22T10:00:00Z' })
      ]);

      renderPage();

      const titles = (await findCards()).map((card) => within(card).getByRole('heading').textContent);
      expect(titles).toEqual(['Beta', 'Charlie', 'Alpha']);
    });

    it('links each card to the watch page of its movie, named by its title and resume details', async () => {
      renderPage();

      const cards = await findCards();
      expect(cards[0]).toHaveAttribute('href', `/watch/${filmEpisodeId(FIRST_MOVIE_ID)}`);
      expect(cards[1]).toHaveAttribute('href', `/watch/${filmEpisodeId(SECOND_MOVIE_ID)}`);
      // The poster repeats the title but is hidden, so the title is read once.
      expect(cards[0]).toHaveAccessibleName('The Long Night Resume at 12:34 48 min left');
    });

    it('draws the share watched as a bar hidden from assistive technology', async () => {
      renderPage();

      const [firstCard] = await findCards();
      const bar = firstCard.querySelector<HTMLElement>('[style]');
      // 754 of 3600 seconds is 20.94%.
      expect(Number.parseFloat(bar?.style.width ?? '')).toBeCloseTo(20.94, 2);
      expect(bar?.closest('[aria-hidden="true"]')).not.toBeNull();
    });

    it('shows only where it resumes when the duration is unknown', async () => {
      answerList = () => jsonResponse([
        item(FIRST_MOVIE_ID, 'The Long Night', { currentSeconds: 754, durationSeconds: null })
      ]);

      renderPage();

      const [card] = await findCards();
      expect(card).toHaveTextContent('Resume at 12:34');
      expect(card).not.toHaveTextContent('left');
      expect(card.querySelector('[style]')).toBeNull();
    });

    it('says that it lists the 50 most recently watched movies, and shows all 50 without pagination', async () => {
      answerList = () => jsonResponse(Array.from({ length: 50 }, (_, index) => (
        item(movieIdAt(index), `Movie ${index + 1}`, { currentSeconds: 60, durationSeconds: 600 })
      )));

      renderPage();

      expect(await findCards()).toHaveLength(50);
      expect(screen.getByText(/Your 50 most recently watched movies that you have not finished/))
        .toBeInTheDocument();
      expect(screen.queryByRole('navigation', { name: /pagination/i })).not.toBeInTheDocument();
      expect(screen.getByText('50 movies')).toBeInTheDocument();
      expect(listRequests()).toBe(1);
    });

    it('is reached from the primary navigation', async () => {
      renderPage('/titles');
      const navigation = await screen.findByRole('navigation', { name: 'Primary navigation' }, LOAD_TIMEOUT);

      fireEvent.click(within(navigation).getByRole('link', { name: 'Continue watching' }));

      expect(await screen.findByRole('heading', { level: 1, name: 'Continue watching' })).toBeInTheDocument();
      expect(await findCards()).toHaveLength(2);
    });
  });

  describe('loading', () => {
    it('announces the loading state, and announces the count in the same live region once loaded', async () => {
      let releaseList: (response: Response) => void = () => undefined;
      answerList = () => new Promise<Response>((resolve) => {
        releaseList = resolve;
      });

      renderPage();

      const loading = await screen.findByText('Loading continue watching…', {}, LOAD_TIMEOUT);
      expect(loading.closest('[role="status"]')).not.toBeNull();
      const countRegion = screen.getByRole('heading', { level: 1, name: 'Continue watching' })
        .parentElement?.querySelector('[aria-live="polite"]');
      expect(countRegion).toHaveTextContent('');
      await act(async () => {
        releaseList(jsonResponse([item(FIRST_MOVIE_ID, 'The Long Night')]));
      });
      await waitFor(() => expect(countRegion).toHaveTextContent('1 movie'));
    });
  });

  describe('offline', () => {
    afterEach(() => {
      act(() => {
        onlineManager.setOnline(true);
      });
    });

    it('says that the browser is offline instead of loading, and loads once the connection is back', async () => {
      onlineManager.setOnline(false);

      renderPage();

      expect(await screen.findByRole('heading', { level: 2, name: 'You are offline' }, LOAD_TIMEOUT))
        .toBeInTheDocument();
      expect(screen.queryByText('Loading continue watching…')).not.toBeInTheDocument();
      expect(listRequests()).toBe(0);
      act(() => {
        onlineManager.setOnline(true);
      });

      expect(await findCards()).toHaveLength(2);
      expect(screen.queryByRole('heading', { name: 'You are offline' })).not.toBeInTheDocument();
    });
  });

  describe('empty', () => {
    it('says that nothing was started yet and links to the movie list', async () => {
      answerList = () => jsonResponse([]);

      renderPage();

      expect(await screen.findByRole('heading', { level: 2, name: 'Nothing to continue yet' }, LOAD_TIMEOUT))
        .toBeInTheDocument();
      expect(screen.getByRole('link', { name: 'Browse movies' })).toHaveAttribute('href', '/titles');
      expect(screen.getByText('0 movies')).toBeInTheDocument();
    });
  });

  describe('errors', () => {
    it('shows the error state with Retry, and shows the list once Retry succeeds', async () => {
      answerList = () => apiError(503, 'SERVICE_UNAVAILABLE', 'Try again later');
      renderPage();
      const alert = await screen.findByRole('alert', {}, LOAD_TIMEOUT);
      expect(within(alert).getByRole('heading', { name: 'Continue watching could not be loaded' }))
        .toBeInTheDocument();
      answerList = () => jsonResponse([item(FIRST_MOVIE_ID, 'The Long Night')]);

      fireEvent.click(within(alert).getByRole('button', { name: 'Retry' }));

      expect(screen.getByRole('heading', { level: 1, name: 'Continue watching' })).toHaveFocus();
      expect(await findCards()).toHaveLength(1);
      expect(listRequests()).toBe(2);
    });

    it('keeps the list when a refresh fails, and refreshes it on Retry', async () => {
      const { queryClient } = renderPage();
      await findCards();
      answerList = () => apiError(503, 'SERVICE_UNAVAILABLE', 'Try again later');

      await act(async () => {
        await queryClient.refetchQueries({ queryKey: progressKeys.continueWatching() });
      });
      await renderQueryNotifications();

      const notice = screen.getByRole('alert');
      expect(notice).toHaveTextContent('The list could not be refreshed and may be out of date.');
      expect(await findCards()).toHaveLength(2);
      answerList = () => jsonResponse([item(FIRST_MOVIE_ID, 'The Long Night', { currentSeconds: 900 })]);

      fireEvent.click(within(notice).getByRole('button', { name: 'Retry' }));

      await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
      const cards = await findCards();
      expect(cards).toHaveLength(1);
      expect(cards[0]).toHaveTextContent('Resume at 15:00');
    });

    it('keeps the list through a reconnect whose refresh fails', async () => {
      renderPage();
      await findCards();
      answerList = () => apiError(503, 'SERVICE_UNAVAILABLE', 'Try again later');

      act(() => {
        onlineManager.setOnline(false);
        onlineManager.setOnline(true);
      });
      await waitFor(() => expect(listRequests()).toBe(2));
      await renderQueryNotifications();

      expect(await findCards()).toHaveLength(2);
      expect(screen.getByRole('alert')).toHaveTextContent('The list could not be refreshed');
    });
  });

  describe('freshness', () => {
    it('fetches the list again on every visit and shows the new positions, even when kept fresh', async () => {
      // A long default freshness must not keep the old positions: every visit asks the server.
      renderPage(PAGE_PATH, { withNavigation: true, staleTime: Infinity });
      const [firstVisitCard] = await findCards();
      expect(firstVisitCard).toHaveTextContent('Resume at 12:34');
      answerList = () => jsonResponse([item(FIRST_MOVIE_ID, 'The Long Night', { currentSeconds: 1800 })]);

      fireEvent.click(screen.getByRole('button', { name: 'Leave the page' }));
      await screen.findByRole('heading', { level: 1, name: 'Account' }, LOAD_TIMEOUT);
      fireEvent.click(screen.getByRole('button', { name: 'Come back' }));

      await waitFor(() => expect(listRequests()).toBe(2));
      await waitFor(async () => expect((await findCards())[0]).toHaveTextContent('Resume at 30:00'));
    });

    it('fetches the list again when the list is marked stale while open', async () => {
      const { queryClient } = renderPage();
      await findCards();
      answerList = () => jsonResponse([item(FIRST_MOVIE_ID, 'The Long Night', { currentSeconds: 1800 })]);

      await act(async () => {
        await queryClient.invalidateQueries({ queryKey: progressKeys.continueWatching() });
      });

      await waitFor(async () => expect(await findCards()).toHaveLength(1));
      expect(listRequests()).toBe(2);
    });

    it('moves focus to the page heading when a refresh removes the focused card', async () => {
      const { queryClient } = renderPage();
      const [firstCard] = await findCards();
      firstCard.focus();
      // The first movie was finished on another device.
      answerList = () => jsonResponse([item(SECOND_MOVIE_ID, 'Second Movie')]);

      await act(async () => {
        await queryClient.refetchQueries({ queryKey: progressKeys.continueWatching() });
      });
      await renderQueryNotifications();

      expect(await findCards()).toHaveLength(1);
      expect(screen.getByRole('heading', { level: 1, name: 'Continue watching' })).toHaveFocus();
    });

    it('leaves focus on a card that a refresh keeps', async () => {
      const { queryClient } = renderPage();
      const cards = await findCards();
      cards[1].focus();
      answerList = () => jsonResponse([item(SECOND_MOVIE_ID, 'Second Movie')]);

      await act(async () => {
        await queryClient.refetchQueries({ queryKey: progressKeys.continueWatching() });
      });
      await renderQueryNotifications();

      const [remainingCard] = await findCards();
      expect(remainingCard).toHaveFocus();
    });

    it('does not take focus when the viewer had moved it away from a card that a refresh keeps', async () => {
      const { queryClient } = renderPage();
      const cards = await findCards();
      cards[1].focus();
      cards[1].blur();
      answerList = () => jsonResponse([item(SECOND_MOVIE_ID, 'Second Movie')]);

      await act(async () => {
        await queryClient.refetchQueries({ queryKey: progressKeys.continueWatching() });
      });
      await renderQueryNotifications();

      expect(await findCards()).toHaveLength(1);
      expect(document.body).toHaveFocus();
    });

    it('does not take focus when the viewer had moved it away from a card that a refresh then removes', async () => {
      const { queryClient } = renderPage();
      const [firstCard] = await findCards();
      firstCard.focus();
      firstCard.blur();
      answerList = () => jsonResponse([item(SECOND_MOVIE_ID, 'Second Movie')]);

      await act(async () => {
        await queryClient.refetchQueries({ queryKey: progressKeys.continueWatching() });
      });
      await renderQueryNotifications();

      expect(await findCards()).toHaveLength(1);
      expect(document.body).toHaveFocus();
    });

    it('fetches the list again when the same viewer gets a new session', async () => {
      renderPage();
      await findCards();
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

      await waitFor(() => expect(listRequests()).toBe(2));
      expect(await findCards()).toHaveLength(2);
    });
  });
});

function renderPage(path = PAGE_PATH, { withNavigation = false, staleTime = 0 } = {}) {
  installCookieSession(
    createAuthSession({ user: VIEWER, accessToken: 'access-token', expiresInSeconds: 900 }),
    window.localStorage
  );
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime } } });
  const view = render(
    <MemoryRouter future={{ v7_relativeSplatPath: true, v7_startTransition: true }} initialEntries={[path]}>
      <QueryClientProvider client={queryClient}>
        <AuthProvider redirectToLogin={() => undefined}>
          {withNavigation ? <LeaveAndComeBack /> : null}
          <AppRoutes />
        </AuthProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
  return { ...view, queryClient };
}

/** In-app navigation away from the page and back to it, as a viewer who watched a movie meanwhile. */
function LeaveAndComeBack() {
  const navigate = useNavigate();
  return (
    <>
      <button onClick={() => navigate('/account')} type="button">Leave the page</button>
      <button onClick={() => navigate(PAGE_PATH)} type="button">Come back</button>
    </>
  );
}

/** The cards are the links inside the list; each card is one link. */
async function findCards(): Promise<HTMLElement[]> {
  const list = await screen.findByRole('list', {}, LOAD_TIMEOUT);
  return within(list).getAllByRole('link');
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

/** TanStack Query notifies components in a later task, so wait for one before checking what renders. */
async function renderQueryNotifications(): Promise<void> {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 20));
  });
}

function listRequests(): number {
  return fetchMock.mock.calls
    .map(([input]) => new URL(String(input), 'http://localhost').pathname)
    .filter((path) => path === LIST_PATH)
    .length;
}

function movieIdAt(index: number): string {
  return `6f1c0f55-2d4a-4b64-9a43-${String(index).padStart(12, '0')}`;
}

function item(movieId: string, title: string, progress: Partial<PlaybackProgress> = {}) {
  const movie: MovieSummary = {
    id: movieId,
    title,
    slug: title.toLowerCase().replace(/ /g, '-'),
    description: 'A storm traps a crew at sea.',
    releaseYear: 2019,
    posterUrl: null,
    genres: ['Drama'],
    averageRating: null,
    status: 'PUBLISHED', contentType: 'MOVIE',
    videoStatus: 'READY'
  };
  return continueFixture(movie, {
    episodeId: filmEpisodeId(movieId),
    currentSeconds: 754,
    durationSeconds: 3600,
    finished: false,
    lastPlayedAt: '2026-11-22T10:00:00Z',
    ...progress
  });
}

function apiError(status: number, code: string, message: string): Response {
  return jsonResponse({ timestamp: '2026-11-22T00:00:00Z', status, code, message }, status);
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}
