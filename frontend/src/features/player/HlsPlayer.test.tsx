import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import {
  XhrLoader,
  type Loader,
  type LoaderCallbacks,
  type LoaderConfiguration,
  type LoaderContext
} from 'hls.js';
import { type PropsWithChildren, useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiRequestError } from '../../lib/api/client';
import type { UserProfile } from '../../lib/api/types';
import { installCookieSession } from '../../test/cookieSession';
import { FakeHls } from '../../test/fakeHls';
import { FakeLoaderXhr } from '../../test/fakeLoaderXhr';
import { AuthProvider, useAuth } from '../auth/AuthContext';
import * as authApi from '../auth/authApi';
import { createAuthSession } from '../auth/authStorage';
import { HlsPlayer } from './HlsPlayer';
import type { PlaybackInfo } from './playbackApi';

vi.mock('hls.js', async (importOriginal) => {
  const actual = await importOriginal<typeof import('hls.js')>();
  const { FakeHls: Fake } = await import('../../test/fakeHls');
  Fake.adopt(actual.default);
  return { ...actual, default: Fake };
});

const MOVIE_ID = '0b9a7c55-6f0e-4b3a-9d1c-2e4f6a8b0c1d';
const ASSET_ID = '5f1e2d3c-4b5a-4968-8776-655443322110';
const NEW_ASSET_ID = '9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d';
const THIRD_ASSET_ID = '3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f';
const MANIFEST_URL = `/hls/video-assets/${ASSET_ID}/master.m3u8`;
const NEW_MANIFEST_URL = `/hls/video-assets/${NEW_ASSET_ID}/master.m3u8`;
const SEGMENT_URL = `${window.location.origin}/hls/video-assets/${ASSET_ID}/segments/seg_00003.ts`;
const TITLE = 'Big Buck Bunny';
const ALICE: UserProfile = { id: 'alice-id', email: 'alice@example.com', displayName: 'Alice', roles: ['ROLE_USER'] };
const BOB: UserProfile = { id: 'bob-id', email: 'bob@example.com', displayName: 'Bob', roles: ['ROLE_USER'] };

const fetchMock = vi.fn<typeof fetch>();

describe('HlsPlayer', () => {
  beforeEach(() => {
    FakeHls.reset();
    FakeLoaderXhr.reset();
    vi.stubGlobal('XMLHttpRequest', FakeLoaderXhr);
    fetchMock.mockReset();
    fetchMock.mockImplementation(async () => jsonResponse(progressResponse()));
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  describe('engine selection (ADR-011 D1, D5)', () => {
    it('shows the unsupported-browser state when Hls.isSupported() is false', async () => {
      FakeHls.supported = false;

      await renderPlayer({ expectHls: false });

      expect(playerAlert()).toHaveTextContent('This browser cannot play this video.');
      expect(FakeHls.instances).toHaveLength(0);
      expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
    });

    it('takes the hls.js path even when canPlayType answers positively for HLS', async () => {
      const canPlayType = vi.spyOn(HTMLMediaElement.prototype, 'canPlayType').mockReturnValue('probably');

      const { video } = await renderPlayer();

      expect(FakeHls.latest().media).toBe(video);
      expect(FakeHls.latest().loadedSources).toEqual([MANIFEST_URL]);
      expect(canPlayType).not.toHaveBeenCalled();
    });

    it('never assigns the manifest URL to video.src or a source element', async () => {
      const { video } = await renderPlayer();

      expect(video.getAttribute('src')).toBeNull();
      expect(video.src).not.toContain('master.m3u8');
      expect(video.querySelector('source')).toBeNull();
    });
  });

  describe('start position and autoplay (ADR-011 D8)', () => {
    it('loads paused at the resume position through startPosition', async () => {
      const play = vi.spyOn(HTMLMediaElement.prototype, 'play');

      const { video } = await renderPlayer({ playbackInfo: playbackInfo({ resumeSeconds: 754 }) });
      fireEvent(video, new Event('canplay'));

      expect(FakeHls.latest().config.startPosition).toBe(754);
      expect(video).not.toHaveAttribute('autoplay');
      expect(play).not.toHaveBeenCalled();
    });

    it('starts at the beginning when the resume position is 0', async () => {
      await renderPlayer({ playbackInfo: playbackInfo({ resumeSeconds: 0 }) });

      expect(FakeHls.latest().config.startPosition).toBe(-1);
    });

    it('shows no error and saves nothing when play() is rejected', async () => {
      const notAllowed = new DOMException('Playback needs a user gesture', 'NotAllowedError');
      vi.spyOn(HTMLMediaElement.prototype, 'play').mockRejectedValue(notAllowed);
      const { video } = await renderPlayer();
      fireEvent(video, new Event('canplay'));

      await expect(video.play()).rejects.toBe(notAllowed);
      await settle();

      expect(playerAlert()).toBeNull();
      expect(FakeHls.latest().destroyed).toBe(false);
      expect(progressSaves()).toEqual([]);
    });
  });

  describe('controls and accessibility (ADR-011 D6, D7)', () => {
    it('renders a native video with controls and playsinline, labelled with the movie title', async () => {
      const { video } = await renderPlayer();

      expect(screen.getByLabelText(TITLE)).toBe(video);
      expect(video).toHaveAttribute('controls');
      expect(video).toHaveAttribute('playsinline');
    });

    it('announces the initial load in a status region until the video can play', async () => {
      const { video } = await renderPlayer();

      expect(screen.getByRole('status')).toHaveTextContent('Loading video');
      fireEvent(video, new Event('canplay'));

      expect(screen.getByRole('status')).toHaveTextContent('');
    });

    it('shows a rebuffering spinner that is hidden from assistive technology', async () => {
      const { video } = await renderPlayer();
      fireEvent(video, new Event('canplay'));

      fireEvent(video, new Event('waiting'));
      expect(screen.getByTestId('player-buffering')).toHaveAttribute('aria-hidden', 'true');

      fireEvent(video, new Event('playing'));
      expect(screen.queryByTestId('player-buffering')).not.toBeInTheDocument();
    });
  });

  describe('error states (ADR-011 D7)', () => {
    it('ignores a non-fatal error', async () => {
      const { video } = await renderPlayer();
      fireEvent(video, new Event('canplay'));

      emitError({ type: FakeHls.ErrorTypes.NETWORK_ERROR, details: 'fragLoadError', fatal: false });

      expect(playerAlert()).toBeNull();
      expect(FakeHls.latest().destroyed).toBe(false);
    });

    it('recovers the first fatal media error and shows the playback error on the second', async () => {
      const { video } = await renderPlayer();
      seekTo(video, 64);
      const mediaError = { type: FakeHls.ErrorTypes.MEDIA_ERROR, details: 'bufferAppendError', fatal: true };

      emitError(mediaError);
      expect(FakeHls.latest().recoverMediaErrorCount).toBe(1);
      expect(FakeHls.latest().startLoadPositions).toEqual([64]);
      expect(playerAlert()).toBeNull();

      emitError(mediaError);
      expect(FakeHls.latest().recoverMediaErrorCount).toBe(1);
      expect(FakeHls.latest().destroyed).toBe(true);
      expect(playerAlert()).toHaveTextContent('This video cannot be played right now.');
    });

    it('counts the reset hls.js runs itself as the one recovery and refuses a second one', async () => {
      const { video } = await renderPlayer();
      seekTo(video, 64);

      // hls.js 1.7.4 resets the media source itself before its listeners see the error.
      act(() => {
        FakeHls.latest().recoverMediaError();
      });
      emitError(libraryResetError());
      expect(FakeHls.latest().recoverMediaErrorCount).toBe(1);
      expect(FakeHls.latest().startLoadPositions).toEqual([64]);
      expect(playerAlert()).toBeNull();

      act(() => {
        FakeHls.latest().recoverMediaError();
      });
      emitError(libraryResetError());
      expect(FakeHls.latest().recoverMediaErrorCount).toBe(1);
      expect(playerAlert()).toHaveTextContent('This video cannot be played right now.');
    });

    it('shows the unsupported state for a codec the browser cannot decode', async () => {
      await renderPlayer();

      emitError({
        type: FakeHls.ErrorTypes.MEDIA_ERROR,
        details: FakeHls.ErrorDetails.MANIFEST_INCOMPATIBLE_CODECS_ERROR,
        fatal: true
      });

      expect(playerAlert()).toHaveTextContent('This browser cannot play this video.');
      expect(FakeHls.latest().recoverMediaErrorCount).toBe(0);
    });

    it.each([
      ['503', 503],
      ['no status', undefined]
    ])('shows Retry for a fatal network error with %s and retries at the current position', async (_name, code) => {
      const { video } = await renderPlayer();
      seekTo(video, 321.6);

      emitError(fatalNetworkError(SEGMENT_URL, code));
      await settle();

      expect(FakeHls.instances[0].destroyed).toBe(true);
      expect(playerAlert()).toHaveTextContent('The video could not be loaded.');
      fireEvent.click(within(playerAlert()!).getByRole('button', { name: 'Retry' }));

      await waitFor(() => expect(FakeHls.instances).toHaveLength(2));
      expect(FakeHls.latest().config.startPosition).toBe(321.6);
      expect(FakeHls.latest().loadedSources).toEqual([MANIFEST_URL]);
    });

    it('reloads the player with the new current asset at the current position after a 404', async () => {
      const reloadPlaybackInfo = vi.fn(async () => playbackInfo({ videoAssetId: NEW_ASSET_ID }));
      const { video } = await renderPlayer({ reloadPlaybackInfo });
      seekTo(video, 88.2);

      emitError(fatalNetworkError(SEGMENT_URL, 404));

      await waitFor(() => expect(FakeHls.instances).toHaveLength(2));
      expect(reloadPlaybackInfo).toHaveBeenCalledTimes(1);
      expect(FakeHls.instances[0].destroyed).toBe(true);
      expect(FakeHls.latest().loadedSources).toEqual([NEW_MANIFEST_URL]);
      expect(FakeHls.latest().config.startPosition).toBe(88.2);
    });

    it('keeps the resume position for a reload before the video has reached it', async () => {
      const replacement = playbackInfo({ videoAssetId: NEW_ASSET_ID, durationSeconds: 3600 });
      const reloadPlaybackInfo = vi.fn(async () => replacement);
      const initial = playbackInfo({ resumeSeconds: 754, durationSeconds: 3600 });
      const { video } = await renderPlayer({ playbackInfo: initial, reloadPlaybackInfo });
      // hls.js has not moved the video to its start position yet, so currentTime still reads 0.
      setPlayback(video, { time: 0, paused: true });
      fireEvent(video, new Event('timeupdate'));

      emitError(fatalNetworkError(SEGMENT_URL, 404));

      await waitFor(() => expect(FakeHls.instances).toHaveLength(2));
      expect(FakeHls.latest().config.startPosition).toBe(754);
    });

    it.each([
      ['404 MOVIE_NOT_FOUND', 404, 'MOVIE_NOT_FOUND'],
      ['409 VIDEO_NOT_READY', 409, 'VIDEO_NOT_READY']
    ])('shows "no longer available" when the refetch after a 404 answers %s', async (_name, status, code) => {
      const reloadPlaybackInfo = vi.fn(async (): Promise<PlaybackInfo> => {
        throw new ApiRequestError(status, apiError(status, code));
      });
      await renderPlayer({ reloadPlaybackInfo });

      emitError(fatalNetworkError(SEGMENT_URL, 404));

      expect(await findPlayerAlert()).toHaveTextContent('This video is no longer available.');
      expect(FakeHls.instances).toHaveLength(1);
      expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
    });

    it('shows Retry when the refetch after a 404 fails', async () => {
      const reloadPlaybackInfo = vi.fn(async (): Promise<PlaybackInfo> => {
        throw new TypeError('Failed to fetch');
      });
      await renderPlayer({ reloadPlaybackInfo });

      emitError(fatalNetworkError(SEGMENT_URL, 404));

      expect(await findPlayerAlert()).toHaveTextContent('The video could not be loaded.');
      expect(reloadPlaybackInfo).toHaveBeenCalledTimes(1);
    });
  });

  describe('load errors that stop loading at once (ADR-011 addendum 2026-11-22)', () => {
    it('stops at once on a non-fatal fragment 404 and refetches the playback info once', async () => {
      const reloadPlaybackInfo = vi.fn(async (): Promise<PlaybackInfo> => {
        throw new ApiRequestError(404, apiError(404, 'MOVIE_NOT_FOUND'));
      });
      await renderPlayer({ reloadPlaybackInfo });

      emitError(nonFatalLoadError(SEGMENT_URL, 404));
      emitError(fatalNetworkError(SEGMENT_URL, 404));

      expect(await findPlayerAlert()).toHaveTextContent('This video is no longer available.');
      expect(FakeHls.latest().stopLoadCount).toBe(1);
      expect(reloadPlaybackInfo).toHaveBeenCalledTimes(1);
    });

    it('stops at once and ends the session when the resend after the recovery gets 401', async () => {
      await renderPlayer();
      mockRefresh(async () => authResponse(ALICE, 'alice-token-2'));
      const load = loadThroughPlayer(SEGMENT_URL);
      await waitFor(() => expect(FakeLoaderXhr.sentRequests()).toHaveLength(1));
      FakeLoaderXhr.latestSent().respond(401);
      await waitFor(() => expect(FakeLoaderXhr.sentRequests()).toHaveLength(2));

      // hls.js reports the load error before React renders the ended session, as in the browser.
      act(() => {
        FakeLoaderXhr.latestSent().respond(401);
        FakeHls.latest().emitError(nonFatalLoadError(SEGMENT_URL, 401, load.lastErrorDetails()));
      });

      expect(load.callbacks.onError).toHaveBeenCalledTimes(1);
      expect(FakeHls.latest().stopLoadCount).toBe(1);
      expect(await findPlayerAlert()).toHaveTextContent('Your session has ended.');
      await waitFor(() => expect(screen.getByTestId('identity')).toHaveTextContent('signed-out'));
    });

    it.each([
      ['503', new ApiRequestError(503, apiError(503, 'AUTH_STATE_UNAVAILABLE'))],
      ['a network error', new TypeError('Failed to fetch')],
      ['a timeout', new DOMException('Authentication request timed out', 'TimeoutError')]
    ])('stops at once and shows Retry, keeping the session, when the refresh fails with %s', async (
      _name,
      refreshFailure
    ) => {
      await renderPlayer();
      mockRefresh(async () => {
        throw refreshFailure;
      });
      const load = loadThroughPlayer(SEGMENT_URL);
      await waitFor(() => expect(FakeLoaderXhr.sentRequests()).toHaveLength(1));
      FakeLoaderXhr.latestSent().respond(401);
      await waitFor(() => expect(load.callbacks.onError).toHaveBeenCalledTimes(1));

      emitError(nonFatalLoadError(SEGMENT_URL, 0, load.lastErrorDetails()));

      expect(FakeHls.latest().stopLoadCount).toBe(1);
      expect(await findPlayerAlert()).toHaveTextContent('The video could not be loaded.');
      expect(within(playerAlert()!).getByRole('button', { name: 'Retry' })).toBeInTheDocument();
      expect(screen.getByTestId('identity')).toHaveTextContent(ALICE.id);
      expect(FakeLoaderXhr.sentRequests()).toHaveLength(1);
    });

    it('leaves a non-fatal fragment 503 to the hls.js backoff', async () => {
      await renderPlayer();

      emitError(nonFatalLoadError(SEGMENT_URL, 503));
      await settle();

      expect(FakeHls.latest().stopLoadCount).toBe(0);
      expect(playerAlert()).toBeNull();
    });
  });

  describe('authentication (ADR-011 D2)', () => {
    it('shows Retry for a fatal 401 that authentication did not decide', async () => {
      await renderPlayer();

      emitError(fatalNetworkError(SEGMENT_URL, 401));

      expect(await findPlayerAlert()).toHaveTextContent('The video could not be loaded.');
      expect(screen.getByTestId('identity')).toHaveTextContent(ALICE.id);
    });

    it('loads playlists and fragments through the authenticated loader', async () => {
      await renderPlayer();
      const config = FakeHls.latest().config;

      expect(config.pLoader).toBe(config.fLoader);
      expect((config.fLoader as unknown as { prototype: object }).prototype).toBeInstanceOf(XhrLoader);
      expect(config.xhrSetup).toBeUndefined();
    });

    it('sends the current token on every HLS request', async () => {
      await renderPlayer();

      loadThroughPlayer(SEGMENT_URL);
      await waitFor(() => expect(FakeLoaderXhr.sentRequests()).toHaveLength(1));

      const request = FakeLoaderXhr.latestSent();
      expect(request.headers.authorization).toBe('Bearer alice-token-1');
      expect(request.url).not.toContain('token');
      expect(request.withCredentials).toBe(false);
    });

    it('recovers a 401 once through the shared refresh and resends with the new token', async () => {
      await renderPlayer();
      const refresh = mockRefresh(async () => authResponse(ALICE, 'alice-token-2'));
      const load = loadThroughPlayer(SEGMENT_URL);
      await waitFor(() => expect(FakeLoaderXhr.sentRequests()).toHaveLength(1));

      FakeLoaderXhr.latestSent().respond(401);
      await waitFor(() => expect(FakeLoaderXhr.sentRequests()).toHaveLength(2));
      FakeLoaderXhr.latestSent().respond(200, 'segment');

      expect(refresh).toHaveBeenCalledTimes(1);
      expect(FakeLoaderXhr.latestSent().headers.authorization).toBe('Bearer alice-token-2');
      expect(load.callbacks.onSuccess).toHaveBeenCalledTimes(1);
      expect(load.callbacks.onError).not.toHaveBeenCalled();
    });

    it('destroys the player when another account signs in, and the new session stays', async () => {
      await renderPlayer();
      vi.spyOn(authApi, 'login').mockResolvedValue(authResponse(BOB, 'bob-token'));

      fireEvent.click(screen.getByRole('button', { name: 'Sign in Bob' }));

      await waitFor(() => expect(screen.getByTestId('identity')).toHaveTextContent(BOB.id));
      expect(FakeHls.latest().destroyed).toBe(true);
      expect(playerAlert()).toHaveTextContent('Playback stopped because a different account signed in.');
      expect(FakeHls.instances).toHaveLength(1);
    });

    it('destroys the player when the viewer signs out', async () => {
      await renderPlayer();
      vi.spyOn(authApi, 'logout').mockResolvedValue(undefined);

      fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));

      await waitFor(() => expect(FakeHls.latest().destroyed).toBe(true));
      expect(playerAlert()).toHaveTextContent('Your session has ended.');
    });
  });

  describe('progress saves (ADR-011 D13)', () => {
    it('saves nothing before playback first starts', async () => {
      const { video, unmount } = await renderPlayer();
      setPlayback(video, { time: 30, paused: true });

      fireEvent(video, new Event('pause'));
      fireEvent(video, new Event('seeked'));
      hidePage();
      window.dispatchEvent(new Event('pagehide'));
      unmount();
      await settle();

      expect(progressSaves()).toEqual([]);
    });

    it('saves every 10 seconds while playing, in whole seconds, with the video asset ID', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      const { video } = await renderPlayer();

      startPlaying(video, 4.2);
      setPlayback(video, { time: 14.9 });
      await advanceSeconds(10);
      setPlayback(video, { time: 24.1 });
      await advanceSeconds(10);

      expect(progressSaves()).toEqual([save(14), save(24)]);
      expect(progressRequestUrls()).toEqual([progressUrl(), progressUrl()]);
    });

    it('stops the 10-second saves while paused', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      const { video } = await renderPlayer();
      startPlaying(video, 10);

      setPlayback(video, { time: 12.5, paused: true });
      fireEvent(video, new Event('pause'));
      await settle();
      await advanceSeconds(30);

      expect(progressSaves()).toEqual([save(12)]);
    });

    it('sends the ended save after a pause save in the same second', async () => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
      const { video } = await renderPlayer();
      startPlaying(video, 590);

      setPlayback(video, { time: 596.4, paused: true });
      fireEvent(video, new Event('pause'));
      setPlayback(video, { time: 596.7, paused: true, ended: true });
      fireEvent(video, new Event('ended'));
      await settle();

      expect(progressSaves()).toEqual([save(596), save(596, true)]);
    });

    it('saves after a seek and when the page becomes hidden', async () => {
      const { video } = await renderPlayer();
      startPlaying(video, 5);

      setPlayback(video, { time: 120.8 });
      fireEvent(video, new Event('seeked'));
      await settle();
      setPlayback(video, { time: 131.2 });
      hidePage();
      await settle();

      expect(progressSaves()).toEqual([save(120), save(131)]);
    });

    it('saves at pagehide with a keepalive fetch and the latest position', async () => {
      const { video } = await renderPlayer();
      startPlaying(video, 5);

      setPlayback(video, { time: 47.3 });
      window.dispatchEvent(new Event('pagehide'));

      const keepaliveCalls = fetchMock.mock.calls.filter(([, init]) => init?.keepalive === true);
      expect(keepaliveCalls).toHaveLength(1);
      expect(JSON.parse(String(keepaliveCalls[0][1]?.body))).toEqual(save(47));
      expect(new Headers(keepaliveCalls[0][1]?.headers).get('Authorization')).toBe('Bearer alice-token-1');
      expect(String(keepaliveCalls[0][0])).not.toContain('token');
    });

    it('saves when the player unmounts', async () => {
      const { video, unmount } = await renderPlayer();
      startPlaying(video, 5);
      setPlayback(video, { time: 63.9 });

      unmount();
      await settle();

      expect(progressSaves()).toEqual([save(63)]);
    });

    it('keeps playing when a save fails, and sends the position again at the next trigger', async () => {
      const { video } = await renderPlayer();
      startPlaying(video, 5);
      fetchMock.mockImplementationOnce(async () => jsonResponse(apiError(500, 'INTERNAL_ERROR'), 500));

      setPlayback(video, { time: 20, paused: true });
      fireEvent(video, new Event('pause'));
      await settle();
      fireEvent(video, new Event('seeked'));
      await settle();

      expect(progressSaves()).toEqual([save(20), save(20)]);
      expect(playerAlert()).toBeNull();
      expect(FakeHls.latest().destroyed).toBe(false);
    });

    it('reloads with the new asset after 409 VIDEO_ASSET_NOT_CURRENT and saves for that asset', async () => {
      const reloadPlaybackInfo = vi.fn(async () => playbackInfo({ videoAssetId: NEW_ASSET_ID }));
      const { video } = await renderPlayer({ reloadPlaybackInfo });
      startPlaying(video, 5);
      const notCurrent = apiError(409, 'VIDEO_ASSET_NOT_CURRENT');
      fetchMock.mockImplementationOnce(async () => jsonResponse(notCurrent, 409));

      setPlayback(video, { time: 70.4, paused: true });
      fireEvent(video, new Event('pause'));

      await waitFor(() => expect(FakeHls.instances).toHaveLength(2));
      expect(reloadPlaybackInfo).toHaveBeenCalledTimes(1);
      expect(FakeHls.latest().loadedSources).toEqual([NEW_MANIFEST_URL]);
      expect(FakeHls.latest().config.startPosition).toBe(70.4);

      const newVideo = screen.getByLabelText(TITLE) as HTMLVideoElement;
      setPlayback(newVideo, { time: 75, paused: true });
      fireEvent(newVideo, new Event('seeked'));
      await settle();
      expect(progressSaves().at(-1)).toEqual({ ...save(75), videoAssetId: NEW_ASSET_ID });
    });
  });

  describe('progress saves at the end of the video (ADR-011 D13)', () => {
    it('leaves the end on a seek to an earlier second, even less than one second back', async () => {
      const { video } = await renderPlayer();
      startPlaying(video, 90);
      setPlayback(video, { time: 100.2, paused: true, ended: true });
      fireEvent(video, new Event('ended'));
      await settle();

      seekTo(video, 99.5);
      await settle();

      expect(progressSaves()).toEqual([save(100, true), save(99)]);
    });
  });

  describe('asset checks shared by HLS 404 and progress 409 (ADR-011 D7, D13)', () => {
    it('fetches the playback info again for an HLS 404 after a progress 409 found the same asset', async () => {
      const reloadPlaybackInfo = vi.fn<() => Promise<PlaybackInfo>>()
        .mockResolvedValueOnce(playbackInfo())
        .mockResolvedValueOnce(playbackInfo({ videoAssetId: NEW_ASSET_ID }));
      const { video } = await renderPlayer({ reloadPlaybackInfo });
      startPlaying(video, 5);
      const notCurrent = apiError(409, 'VIDEO_ASSET_NOT_CURRENT');
      fetchMock.mockImplementationOnce(async () => jsonResponse(notCurrent, 409));
      setPlayback(video, { time: 30, paused: true });
      fireEvent(video, new Event('pause'));
      await settle();
      expect(reloadPlaybackInfo).toHaveBeenCalledTimes(1);

      emitError(fatalNetworkError(SEGMENT_URL, 404));

      await waitFor(() => expect(FakeHls.instances).toHaveLength(2));
      expect(reloadPlaybackInfo).toHaveBeenCalledTimes(2);
      expect(FakeHls.latest().loadedSources).toEqual([NEW_MANIFEST_URL]);
    });
  });

  describe('asset replacement (ADR-011 D7, D11, D13)', () => {
    it('does not carry finished over to a replacement of an asset that ended', async () => {
      const replacement = playbackInfo({ videoAssetId: NEW_ASSET_ID, durationSeconds: 900 });
      const { video } = await renderPlayer({ reloadPlaybackInfo: vi.fn(async () => replacement) });
      startPlaying(video, 590);
      const notCurrent = apiError(409, 'VIDEO_ASSET_NOT_CURRENT');
      fetchMock.mockImplementationOnce(async () => jsonResponse(notCurrent, 409));

      setPlayback(video, { time: 596, paused: true, ended: true });
      fireEvent(video, new Event('ended'));
      await waitFor(() => expect(FakeHls.instances).toHaveLength(2));
      seekTo(screen.getByLabelText(TITLE) as HTMLVideoElement, 596);
      await settle();

      expect(progressSaves().at(-1)).toEqual({ ...save(596), videoAssetId: NEW_ASSET_ID });
    });

    it('ignores a late 409 about the previous asset after the player switched assets', async () => {
      const reloadPlaybackInfo = vi.fn<() => Promise<PlaybackInfo>>()
        .mockResolvedValueOnce(playbackInfo({ videoAssetId: NEW_ASSET_ID }))
        .mockResolvedValueOnce(playbackInfo({ videoAssetId: THIRD_ASSET_ID }));
      const { video } = await renderPlayer({ reloadPlaybackInfo });
      startPlaying(video, 5);
      const slowSave = deferredResponse();
      fetchMock.mockImplementationOnce(() => slowSave.promise);
      setPlayback(video, { time: 30, paused: true });
      fireEvent(video, new Event('pause'));

      emitError(fatalNetworkError(SEGMENT_URL, 404));
      await waitFor(() => expect(FakeHls.instances).toHaveLength(2));
      slowSave.resolve(jsonResponse(apiError(409, 'VIDEO_ASSET_NOT_CURRENT'), 409));
      await settle();
      expect(reloadPlaybackInfo).toHaveBeenCalledTimes(1);

      emitError(fatalNetworkError(SEGMENT_URL, 404));
      await waitFor(() => expect(FakeHls.instances).toHaveLength(3));
      expect(reloadPlaybackInfo).toHaveBeenCalledTimes(2);
      expect(FakeHls.latest().loadedSources).toEqual([`/hls/video-assets/${THIRD_ASSET_ID}/master.m3u8`]);
    });

    it('retries a replacement at its own start position when it fails before reaching it', async () => {
      const replacement = playbackInfo({ videoAssetId: NEW_ASSET_ID, durationSeconds: 30, resumeSeconds: 10 });
      const { video } = await renderPlayer({ reloadPlaybackInfo: vi.fn(async () => replacement) });
      startPlaying(video, 5);
      const notCurrent = apiError(409, 'VIDEO_ASSET_NOT_CURRENT');
      fetchMock.mockImplementationOnce(async () => jsonResponse(notCurrent, 409));
      setPlayback(video, { time: 120, paused: true });
      fireEvent(video, new Event('pause'));
      await waitFor(() => expect(FakeHls.instances).toHaveLength(2));
      expect(FakeHls.latest().config.startPosition).toBe(10);

      emitError(fatalNetworkError(SEGMENT_URL, 503));
      await settle();
      fireEvent.click(within(playerAlert()!).getByRole('button', { name: 'Retry' }));

      await waitFor(() => expect(FakeHls.instances).toHaveLength(3));
      expect(FakeHls.latest().config.startPosition).toBe(10);
    });
  });

  describe('progress and asset changes across reloads and accounts (ADR-011 D7, D11, D13)', () => {
    it('never sends the viewer\'s progress with the account that signs in next', async () => {
      const { video } = await renderPlayer();
      startPlaying(video, 5);
      setPlayback(video, { time: 42 });
      vi.spyOn(authApi, 'login').mockResolvedValue(authResponse(BOB, 'bob-token'));

      fireEvent.click(screen.getByRole('button', { name: 'Sign in Bob' }));
      await waitFor(() => expect(screen.getByTestId('identity')).toHaveTextContent(BOB.id));
      await settle();

      const progressWithBobToken = fetchMock.mock.calls.filter(([url, init]) => String(url).endsWith('/progress')
        && new Headers(init?.headers).get('Authorization') === 'Bearer bob-token');
      expect(progressWithBobToken).toEqual([]);
    });

    it('keeps reporting finished after the end until the viewer plays again', async () => {
      const { video, unmount } = await renderPlayer();
      startPlaying(video, 590);
      const pauseSave = deferredResponse();
      fetchMock.mockImplementationOnce(() => pauseSave.promise);

      setPlayback(video, { time: 596.4, paused: true });
      fireEvent(video, new Event('pause'));
      setPlayback(video, { time: 596.7, paused: true, ended: true });
      fireEvent(video, new Event('ended'));
      hidePage();
      pauseSave.resolve(jsonResponse(progressResponse()));
      await settle();
      unmount();
      await settle();

      expect(progressSaves()).toEqual([save(596), save(596, true)]);
    });

    it('keeps saves in order across a Retry', async () => {
      const { video } = await renderPlayer();
      startPlaying(video, 5);
      const slowSave = deferredResponse();
      fetchMock.mockImplementationOnce(() => slowSave.promise);
      setPlayback(video, { time: 40, paused: true });
      fireEvent(video, new Event('pause'));
      emitError(fatalNetworkError(SEGMENT_URL, 503));
      await settle();

      fireEvent.click(within(playerAlert()!).getByRole('button', { name: 'Retry' }));
      await waitFor(() => expect(FakeHls.instances).toHaveLength(2));
      seekTo(screen.getByLabelText(TITLE) as HTMLVideoElement, 50);
      await settle();
      expect(progressSaves()).toEqual([save(40)]);

      slowSave.resolve(jsonResponse(progressResponse()));
      await settle();
      expect(progressSaves()).toEqual([save(40), save(50)]);
    });

    it('fetches the playback info again for an HLS 404 after a failed refetch for a progress 409', async () => {
      const reloadPlaybackInfo = vi.fn<() => Promise<PlaybackInfo>>()
        .mockRejectedValueOnce(new TypeError('Failed to fetch'))
        .mockResolvedValueOnce(playbackInfo({ videoAssetId: NEW_ASSET_ID }));
      const { video } = await renderPlayer({ reloadPlaybackInfo });
      startPlaying(video, 5);
      const notCurrent = apiError(409, 'VIDEO_ASSET_NOT_CURRENT');
      fetchMock.mockImplementationOnce(async () => jsonResponse(notCurrent, 409));
      setPlayback(video, { time: 30, paused: true });
      fireEvent(video, new Event('pause'));
      await settle();
      expect(reloadPlaybackInfo).toHaveBeenCalledTimes(1);
      expect(playerAlert()).toBeNull();

      emitError(fatalNetworkError(SEGMENT_URL, 404));

      await waitFor(() => expect(FakeHls.instances).toHaveLength(2));
      expect(reloadPlaybackInfo).toHaveBeenCalledTimes(2);
      expect(FakeHls.latest().loadedSources).toEqual([NEW_MANIFEST_URL]);
    });

    it('starts a shorter replacement at its resume position when the viewer was beyond its end', async () => {
      const replacement = playbackInfo({ videoAssetId: NEW_ASSET_ID, durationSeconds: 30, resumeSeconds: 0 });
      const { video } = await renderPlayer({ reloadPlaybackInfo: vi.fn(async () => replacement) });
      seekTo(video, 120);

      emitError(fatalNetworkError(SEGMENT_URL, 404));

      await waitFor(() => expect(FakeHls.instances).toHaveLength(2));
      expect(FakeHls.latest().config.startPosition).toBe(-1);
    });
  });
});

interface RenderOptions {
  playbackInfo?: PlaybackInfo;
  reloadPlaybackInfo?: () => Promise<PlaybackInfo>;
  expectHls?: boolean;
}

async function renderPlayer({
  playbackInfo: info = playbackInfo(),
  reloadPlaybackInfo = vi.fn(async () => info),
  expectHls = true
}: RenderOptions = {}) {
  const session = createAuthSession(authResponse(ALICE, 'alice-token-1'));
  installCookieSession(session, window.localStorage);
  const view = render(
    <QueryClientProvider client={new QueryClient()}>
      <AuthProvider redirectToLogin={() => undefined}>
        <SessionControls />
        <MountedOnceSignedIn>
          <HlsPlayer movieId={MOVIE_ID} title={TITLE} playbackInfo={info} reloadPlaybackInfo={reloadPlaybackInfo} />
        </MountedOnceSignedIn>
      </AuthProvider>
    </QueryClientProvider>
  );
  const video = (await screen.findByLabelText(TITLE)) as HTMLVideoElement;
  if (expectHls) {
    await waitFor(() => expect(FakeHls.instances).toHaveLength(1));
  }
  return { video, unmount: view.unmount };
}

/** Mounts the player after sign-in and keeps it mounted, so the player itself sees identity changes. */
function MountedOnceSignedIn({ children }: PropsWithChildren) {
  const { isAuthenticated } = useAuth();
  const [mounted, setMounted] = useState(false);
  if (isAuthenticated && !mounted) {
    setMounted(true);
  }
  return mounted ? children : null;
}

function SessionControls() {
  const auth = useAuth();
  const signInBob = () => {
    void auth.login({ email: BOB.email, password: 'fixture-password' }).catch(() => undefined);
  };
  const signOut = () => {
    void auth.logout().catch(() => undefined);
  };
  return (
    <div>
      <p data-testid="identity">{auth.user?.id ?? 'signed-out'}</p>
      <button type="button" onClick={signInBob}>Sign in Bob</button>
      <button type="button" onClick={signOut}>Sign out</button>
    </div>
  );
}

function playerAlert(): HTMLElement | null {
  const alerts = screen.queryAllByRole('alert');
  return alerts.find((alert) => alert.closest('div.relative')) ?? null;
}

async function findPlayerAlert(): Promise<HTMLElement> {
  await waitFor(() => expect(playerAlert()).not.toBeNull());
  return playerAlert()!;
}

function emitError(data: Record<string, unknown>): void {
  act(() => {
    FakeHls.latest().emitError(data);
  });
}

function fatalNetworkError(url: string, code: number | undefined): Record<string, unknown> {
  return {
    type: FakeHls.ErrorTypes.NETWORK_ERROR,
    details: 'fragLoadError',
    fatal: true,
    response: { url, code }
  };
}

function nonFatalLoadError(url: string, code: number, networkDetails?: unknown): Record<string, unknown> {
  return {
    type: FakeHls.ErrorTypes.NETWORK_ERROR,
    details: FakeHls.ErrorDetails.FRAG_LOAD_ERROR,
    fatal: false,
    response: { url, code },
    networkDetails
  };
}

/** A playlist or fragment load through the loader class that the player installed in hls.js. */
function loadThroughPlayer(url: string) {
  const LoaderClass = FakeHls.latest().config.fLoader as unknown as new (config: object) => Loader<LoaderContext>;
  const loader = new LoaderClass({});
  const callbacks = {
    onSuccess: vi.fn(),
    onError: vi.fn(),
    onTimeout: vi.fn(),
    onAbort: vi.fn()
  };
  const context = { url, responseType: 'text', type: 'media-fragment' } as LoaderContext;
  loader.load(context, loaderConfiguration(), callbacks as unknown as LoaderCallbacks<LoaderContext>);
  return {
    loader,
    callbacks,
    lastErrorDetails: (): unknown => callbacks.onError.mock.calls.at(-1)?.[2]
  };
}

function loaderConfiguration(): LoaderConfiguration {
  return {
    loadPolicy: { maxTimeToFirstByteMs: 10_000, maxLoadTimeMs: 20_000, timeoutRetry: null, errorRetry: null },
    timeout: 20_000,
    maxRetry: 0,
    retryDelay: 0,
    maxRetryDelay: 0
  };
}

function mockRefresh(implementation: () => Promise<ReturnType<typeof authResponse>>) {
  return vi.spyOn(authApi, 'refresh').mockImplementation(implementation);
}

function setPlayback(
  video: HTMLVideoElement,
  { time, paused = false, ended = false }: { time: number; paused?: boolean; ended?: boolean }
): void {
  Object.defineProperty(video, 'currentTime', { configurable: true, writable: true, value: time });
  Object.defineProperty(video, 'paused', { configurable: true, value: paused });
  Object.defineProperty(video, 'ended', { configurable: true, value: ended });
}

/** The video reached `time` by a seek, such as hls.js moving it to the start position. */
function seekTo(video: HTMLVideoElement, time: number): void {
  setPlayback(video, { time, paused: true });
  fireEvent(video, new Event('seeked'));
}

function libraryResetError(): Record<string, unknown> {
  return {
    type: FakeHls.ErrorTypes.MEDIA_ERROR,
    details: FakeHls.ErrorDetails.MEDIA_SOURCE_REQUIRES_RESET,
    fatal: true,
    errorAction: { action: 0, flags: 16 }
  };
}

function deferredResponse() {
  let resolve: (response: Response) => void = () => undefined;
  const promise = new Promise<Response>((complete) => {
    resolve = complete;
  });
  return { promise, resolve: (response: Response) => resolve(response) };
}

function startPlaying(video: HTMLVideoElement, time: number): void {
  setPlayback(video, { time });
  fireEvent(video, new Event('canplay'));
  fireEvent(video, new Event('playing'));
}

function hidePage(): void {
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' });
  document.dispatchEvent(new Event('visibilitychange'));
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
}

async function advanceSeconds(seconds: number): Promise<void> {
  vi.advanceTimersByTime(seconds * 1000);
  await settle();
}

/** Let promise chains (progress saves, recoveries) run to completion. */
async function settle(): Promise<void> {
  await act(async () => {
    for (let step = 0; step < 20; step += 1) {
      await Promise.resolve();
    }
  });
}

function progressCalls() {
  return fetchMock.mock.calls.filter(([url, init]) => String(url).endsWith('/progress') && !init?.keepalive);
}

function progressSaves(): unknown[] {
  return progressCalls().map(([, init]) => JSON.parse(String(init?.body)));
}

function progressRequestUrls(): string[] {
  return progressCalls().map(([url, init]) => `${init?.method} ${String(url)}`);
}

function progressUrl(): string {
  return `PUT /api/v1/movies/${MOVIE_ID}/progress`;
}

function save(currentSeconds: number, finished = false) {
  return { videoAssetId: ASSET_ID, currentSeconds, finished };
}

function playbackInfo(overrides: Partial<PlaybackInfo> = {}): PlaybackInfo {
  const videoAssetId = overrides.videoAssetId ?? ASSET_ID;
  return {
    movieId: MOVIE_ID,
    videoAssetId,
    hlsManifestUrl: `/hls/video-assets/${videoAssetId}/master.m3u8`,
    resumeSeconds: 0,
    durationSeconds: 600,
    ...overrides
  };
}

function authResponse(user: UserProfile, accessToken: string) {
  return { user, accessToken, expiresInSeconds: 900 };
}

function progressResponse() {
  return { movieId: MOVIE_ID, currentSeconds: 0, durationSeconds: 600, finished: false, lastPlayedAt: null };
}

function apiError(status: number, code: string) {
  return { timestamp: '2026-11-22T00:00:00Z', status, code, message: 'Fixture' };
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}
