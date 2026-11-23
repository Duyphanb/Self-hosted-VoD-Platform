import { describe, expect, it } from 'vitest';
import { decodePlaybackInfo } from './playbackApi';

const MOVIE_ID = '0b9a7c55-6f0e-4b3a-9d1c-2e4f6a8b0c1d';
const ASSET_ID = '5f1e2d3c-4b5a-4968-8776-655443322110';
const OTHER_ASSET_ID = '9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d';

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
