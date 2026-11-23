import { Link } from 'react-router-dom';
import { movieCardClass } from '../../movies/components/MovieCard';
import { PosterArt } from '../../movies/components/PosterArt';
import type { PlaybackProgress } from '../../movies/moviesApi';
import type { ContinueWatchingItem } from '../continueWatchingApi';
import { formatPlaybackPosition, formatTimeLeft } from '../playbackTime';

interface ResumeDetails {
  /** Where playback resumes, as a player's clock shows it. */
  position: string;
  /** Null when the duration is unknown, or when nothing is left. */
  timeLeft: string | null;
  /** Share of the video watched, 0-100; null when the duration is unknown. */
  percentWatched: number | null;
}

/**
 * One link to the watch page, which resumes at the saved position. It holds the poster with a bar of
 * the share watched, the title, the resume position and the time left, all as plain text with nothing
 * interactive inside. The bar is hidden from assistive technology because the text says the same.
 */
export function ContinueWatchingCard({ item }: { item: ContinueWatchingItem }) {
  const { movie, progress } = item;
  const resume = resumeDetails(progress);
  return (
    <Link className={movieCardClass} to={watchPath(movie.id)}>
      <div className="relative">
        <PosterArt movie={movie} />
        {resume.percentWatched === null ? null : (
          <div aria-hidden="true" className="absolute inset-x-0 bottom-0 h-1.5 bg-slate-950/80">
            <div className="h-full bg-sky-400" style={{ width: `${resume.percentWatched}%` }} />
          </div>
        )}
      </div>
      <div className="grid gap-1 p-3">
        <h2 className="line-clamp-2 text-sm font-semibold leading-5 text-white [overflow-wrap:anywhere]">
          {movie.title}
        </h2>
        <p className="text-xs text-slate-300">Resume at {resume.position}</p>
        {resume.timeLeft === null ? null : <p className="text-xs text-slate-400">{resume.timeLeft}</p>}
      </div>
    </Link>
  );
}

function resumeDetails(progress: PlaybackProgress): ResumeDetails {
  const duration = progress.durationSeconds;
  if (duration === null || duration <= 0) {
    return {
      position: formatPlaybackPosition(progress.currentSeconds),
      timeLeft: null,
      percentWatched: null
    };
  }
  // The server already clamps the position to the duration (ADR-011 D11); clamping here keeps the bar inside.
  const position = Math.min(progress.currentSeconds, duration);
  const remaining = duration - position;
  return {
    position: formatPlaybackPosition(position),
    timeLeft: remaining > 0 ? formatTimeLeft(remaining) : null,
    percentWatched: Math.round((position / duration) * 100)
  };
}

// The decoder accepts only canonical UUIDs; encoding the segment keeps the path safe regardless.
function watchPath(movieId: string): string {
  return `/movies/${encodeURIComponent(movieId)}/watch`;
}
