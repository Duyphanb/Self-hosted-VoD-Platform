import { describe, expect, it } from 'vitest';
import { decodeContinueWatching, InvalidContinueWatchingResponseError } from './continueWatchingApi';

const FIRST_MOVIE_ID = '6f1c0f55-2d4a-4b64-9a43-1d0f3c7b8e21';
const SECOND_MOVIE_ID = '7334b2f1-664f-4bc1-b8f1-10de58c1d74a';

describe('decodeContinueWatching', () => {
  it('keeps the order the API returns', () => {
    const items = decodeContinueWatching([item(SECOND_MOVIE_ID), item(FIRST_MOVIE_ID)]);

    expect(items.map((decoded) => decoded.movie.id)).toEqual([SECOND_MOVIE_ID, FIRST_MOVIE_ID]);
    expect(items[0].progress).toEqual({
      episodeId: SECOND_MOVIE_ID,
      currentSeconds: 754,
      durationSeconds: 3600,
      finished: false,
      lastPlayedAt: '2026-11-22T10:00:00Z'
    });
  });

  it('accepts an empty list', () => {
    expect(decodeContinueWatching([])).toEqual([]);
  });

  it('accepts an unknown duration, which the contract allows', () => {
    const [decoded] = decodeContinueWatching([item(FIRST_MOVIE_ID, { durationSeconds: null })]);

    expect(decoded.progress?.durationSeconds).toBeNull();
  });

  it('rejects a page object instead of an array', () => {
    expect(() => decodeContinueWatching({ items: [] })).toThrow(InvalidContinueWatchingResponseError);
  });

  it('rejects an item without progress', () => {
    const { title, episode } = item(FIRST_MOVIE_ID);

    expect(() => decodeContinueWatching([{ kind: 'RESUME', title, episode }]))
      .toThrow(InvalidContinueWatchingResponseError);
  });

  it('rejects an item whose movie is invalid', () => {
    const invalid = { ...item(FIRST_MOVIE_ID), title: { id: FIRST_MOVIE_ID } };

    expect(() => decodeContinueWatching([invalid])).toThrow(InvalidContinueWatchingResponseError);
  });

  it('rejects progress that belongs to another movie', () => {
    const mismatched = { ...item(FIRST_MOVIE_ID), progress: item(SECOND_MOVIE_ID).progress };

    expect(() => decodeContinueWatching([mismatched])).toThrow(InvalidContinueWatchingResponseError);
  });
});

function item(movieId: string, progressOverrides: Record<string, unknown> = {}) {
  const progress = {
    episodeId: movieId,
    currentSeconds: 754,
    durationSeconds: 3600,
    finished: false,
    lastPlayedAt: '2026-11-22T10:00:00Z',
    ...progressOverrides
  };
  return {
    kind: 'RESUME',
    title: {
      id: movieId,
      title: 'The Long Night',
      slug: 'the-long-night',
      description: 'A storm traps a crew at sea.',
      releaseYear: 2019,
      posterUrl: null,
      genres: ['Drama'],
      averageRating: null,
      status: 'PUBLISHED',
      contentType: 'MOVIE',
      partLabel: null,
      episodeCount: 1,
      playableEpisodeCount: 1,
      availability: 'PLAYABLE'
    },
    episode: { id: movieId, titleId: movieId, ordinal: 1, displayLabel: '1', name: null,
      videoStatus: 'READY', progress },
    progress
  };
}
