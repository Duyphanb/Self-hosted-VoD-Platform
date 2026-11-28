import type { MovieDetail, MovieSummary, PlaybackProgress } from '../features/movies/moviesApi';

function episode(title: MovieSummary, progress: PlaybackProgress | null) {
  return {
    id: title.id,
    titleId: title.id,
    ordinal: 1,
    displayLabel: '1',
    name: null,
    videoStatus: title.videoStatus,
    progress
  };
}

export function titleFixture(title: MovieSummary | MovieDetail) {
  const availability = title.videoStatus === 'READY' ? 'PLAYABLE'
    : title.videoStatus === 'UPLOADED' || title.videoStatus === 'QUEUED' || title.videoStatus === 'PROCESSING'
      ? 'PROCESSING' : title.videoStatus === null || title.videoStatus === 'FAILED' ? 'UNAVAILABLE' : title.videoStatus;
  const base = {
    ...title,
    contentType: title.contentType ?? 'MOVIE',
    partLabel: null,
    episodeCount: 1,
    playableEpisodeCount: availability === 'PLAYABLE' ? 1 : 0,
    availability
  };
  return 'maturityRating' in title
    ? { ...base, episode: title.episode ?? episode(title, title.playbackProgress ?? null) }
    : base;
}

export function episodeFixture(title: MovieDetail) {
  return { title: titleFixture(title), episode: episode(title, title.playbackProgress), synopsis: null };
}

export function continueFixture(movie: MovieSummary, progress: PlaybackProgress) {
  return { kind: 'RESUME', title: titleFixture(movie), episode: episode(movie, progress), progress };
}

export function titlePayloadFixture(value: unknown): unknown {
  if (!value || typeof value !== 'object') {
    return value;
  }
  if ('items' in value && Array.isArray(value.items)) {
    return { ...value, items: value.items.map(titlePayloadFixture) };
  }
  if ('id' in value && 'title' in value && 'genres' in value && 'videoStatus' in value && !('episodeCount' in value)) {
    return titleFixture(value as MovieSummary | MovieDetail);
  }
  return value;
}
