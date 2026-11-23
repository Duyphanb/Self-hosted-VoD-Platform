import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ApiClient, AuthenticationSnapshot } from '../../lib/api/client';
import { decodePlaybackInfo, fetchPlaybackInfo, RUNNING_SAVE_WAIT_MS, saveProgress } from './playbackApi';

const MOVIE_ID = '0b9a7c55-6f0e-4b3a-9d1c-2e4f6a8b0c1d';
const ASSET_ID = '5f1e2d3c-4b5a-4968-8776-655443322110';
const OTHER_ASSET_ID = '9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d';
const OTHER_MOVIE_ID = '2c3d4e5f-6a7b-4c8d-9e0f-1a2b3c4d5e6f';
const VIEWER: AuthenticationSnapshot = {
  userId: '3fcf25c2-f096-4462-ae63-4ba4702a9078',
  accessToken: 'access-token',
  generation: 1
};

describe('decodePlaybackInfo', () => {
  it('accepts the playback info of the API contract', () => {
    expect(decodePlaybackInfo(playback())).toEqual({
      movieId: MOVIE_ID,
      videoAssetId: ASSET_ID,
      hlsManifestUrl: `/hls/video-assets/${ASSET_ID}/master.m3u8`,
      resumeSeconds: 754,
      durationSeconds: 3600
    });
  });

  it('reads a missing resume position as 0 and a missing duration as unknown', () => {
    const { resumeSeconds: _resume, durationSeconds: _duration, ...withoutOptionalFields } = playback();

    expect(decodePlaybackInfo(withoutOptionalFields)).toMatchObject({ resumeSeconds: 0, durationSeconds: null });
  });

  it.each([
    ['another origin', `https://media.example.net/hls/video-assets/${ASSET_ID}/master.m3u8`],
    ['a protocol-relative URL', `//media.example.net/hls/video-assets/${ASSET_ID}/master.m3u8`],
    ['another path', `/api/v1/movies/${MOVIE_ID}`],
    ['the playlist of another asset', `/hls/video-assets/${OTHER_ASSET_ID}/master.m3u8`]
  ])('rejects a manifest URL on %s, so the bearer token never leaves the HLS proxy', (_name, hlsManifestUrl) => {
    expect(decodePlaybackInfo({ ...playback(), hlsManifestUrl })).toBeNull();
  });

  it.each([
    ['a negative resume position', { resumeSeconds: -1 }],
    ['a fractional resume position', { resumeSeconds: 1.5 }],
    ['a movie ID that is not a UUID', { movieId: 'movie-1' }]
  ])('rejects %s', (_name, override) => {
    expect(decodePlaybackInfo({ ...playback(), ...override })).toBeNull();
  });
});

describe('fetchPlaybackInfo after a progress save of the same movie', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('waits for the running save, so the resume position includes it', async () => {
    const client = new FakeClient();
    const save = saveProgress(client.asApiClient(), MOVIE_ID, progressSave(), VIEWER);

    const playbackInfo = fetchPlaybackInfo(client.asApiClient(), MOVIE_ID);
    await flushMicrotasks();

    expect(client.paths()).toEqual([`/movies/${MOVIE_ID}/progress`]);
    client.answer(0, {});
    await save;
    await flushMicrotasks();
    expect(client.paths()).toEqual([`/movies/${MOVIE_ID}/progress`, `/movies/${MOVIE_ID}/playback`]);
    client.answer(1, playback());
    expect((await playbackInfo).resumeSeconds).toBe(754);
  });

  it('asks at once when the running save is for another movie', async () => {
    const client = new FakeClient();
    void saveProgress(client.asApiClient(), OTHER_MOVIE_ID, progressSave(), VIEWER);

    void fetchPlaybackInfo(client.asApiClient(), MOVIE_ID);
    await flushMicrotasks();

    expect(client.paths()).toEqual([`/movies/${OTHER_MOVIE_ID}/progress`, `/movies/${MOVIE_ID}/playback`]);
    client.answer(0, {});
  });

  it('asks after a save that failed', async () => {
    const client = new FakeClient();
    const save = saveProgress(client.asApiClient(), MOVIE_ID, progressSave(), VIEWER);

    void fetchPlaybackInfo(client.asApiClient(), MOVIE_ID);
    client.fail(0, new Error('Save failed'));
    await expect(save).rejects.toThrow('Save failed');
    await flushMicrotasks();

    expect(client.paths()).toEqual([`/movies/${MOVIE_ID}/progress`, `/movies/${MOVIE_ID}/playback`]);
  });

  it('stops waiting for a save that stalls, after the wait limit', async () => {
    vi.useFakeTimers();
    const client = new FakeClient();
    void saveProgress(client.asApiClient(), MOVIE_ID, progressSave(), VIEWER);

    void fetchPlaybackInfo(client.asApiClient(), MOVIE_ID);
    await vi.advanceTimersByTimeAsync(RUNNING_SAVE_WAIT_MS - 1);
    expect(client.paths()).toEqual([`/movies/${MOVIE_ID}/progress`]);
    await vi.advanceTimersByTimeAsync(1);

    expect(client.paths()).toEqual([`/movies/${MOVIE_ID}/progress`, `/movies/${MOVIE_ID}/playback`]);
    client.answer(0, {});
  });
});

/** Records each request and lets the test answer them one by one, by request order. */
class FakeClient {
  private readonly requests: Array<{
    path: string;
    resolve: (value: unknown) => void;
    reject: (error: unknown) => void;
  }> = [];

  asApiClient(): ApiClient {
    const request = (path: string) => new Promise<unknown>((resolve, reject) => {
      this.requests.push({ path, resolve, reject });
    });
    return { request } as unknown as ApiClient;
  }

  paths(): string[] {
    return this.requests.map((request) => request.path);
  }

  answer(index: number, value: unknown): void {
    this.requests[index].resolve(value);
  }

  fail(index: number, error: unknown): void {
    this.requests[index].reject(error);
  }
}

async function flushMicrotasks(): Promise<void> {
  for (let step = 0; step < 20; step += 1) {
    await Promise.resolve();
  }
}

function progressSave() {
  return { videoAssetId: ASSET_ID, currentSeconds: 57, finished: false };
}

function playback() {
  return {
    movieId: MOVIE_ID,
    videoAssetId: ASSET_ID,
    status: 'READY',
    hlsManifestUrl: `/hls/video-assets/${ASSET_ID}/master.m3u8`,
    resumeSeconds: 754,
    durationSeconds: 3600
  };
}
