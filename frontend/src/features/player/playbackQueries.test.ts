import { afterEach, describe, expect, it } from 'vitest';
import type { ApiClient } from '../../lib/api/client';
import { registerClosingPlayerSaves } from './closingPlayerSaves';
import { fetchPlaybackInfoForVisit } from './playbackQueries';

const MOVIE_ID = '6f1c0f55-2d4a-4b64-9a43-1d0f3c7b8e21';
const ASSET_ID = '5f1e2d3c-4b5a-4968-8776-655443322110';
const VIEWER_ID = '3fcf25c2-f096-4462-ae63-4ba4702a9078';

describe('fetchPlaybackInfoForVisit', () => {
  let releaseSave: () => void = () => undefined;

  afterEach(() => {
    releaseSave();
  });

  it('asks for the playback info only after the closed player of this movie has saved', async () => {
    registerClosingPlayerSaves(MOVIE_ID, VIEWER_ID, new Promise<void>((resolve) => {
      releaseSave = resolve;
    }));
    const requests: string[] = [];

    const playbackInfo = fetchPlaybackInfoForVisit(fakeClient(requests), MOVIE_ID);
    await flushMicrotasks();
    expect(requests).toEqual([]);

    releaseSave();
    expect((await playbackInfo).resumeSeconds).toBe(57);
    expect(requests).toEqual([`/movies/${MOVIE_ID}/playback`]);
  });

  it('asks at once when no player of this movie is closing', async () => {
    const requests: string[] = [];

    await fetchPlaybackInfoForVisit(fakeClient(requests), MOVIE_ID);

    expect(requests).toEqual([`/movies/${MOVIE_ID}/playback`]);
  });
});

/** The signed-in viewer and a playback answer; it records the path of each request. */
function fakeClient(requests: string[]): ApiClient {
  return {
    getAuthenticationSnapshot: () => ({ userId: VIEWER_ID, accessToken: 'access-token', generation: 1 }),
    request: async (path: string) => {
      requests.push(path);
      return {
        movieId: MOVIE_ID,
        videoAssetId: ASSET_ID,
        hlsManifestUrl: `/hls/video-assets/${ASSET_ID}/master.m3u8`,
        resumeSeconds: 57,
        durationSeconds: 150
      };
    }
  } as unknown as ApiClient;
}

async function flushMicrotasks(): Promise<void> {
  for (let step = 0; step < 20; step += 1) {
    await Promise.resolve();
  }
}
