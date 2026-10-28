import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { primaryButtonClass, secondaryButtonClass } from '../../../components/buttonStyles';
import { useAuth } from '../../auth/AuthContext';
import { videoAvailability } from '../movieAvailability';
import type { MaturityRating, MovieCredit, MovieDetail } from '../moviesApi';
import { MovieStatusBadge, VideoStatusBadge } from './MovieStatusBadges';
import { PosterArt } from './PosterArt';

const MATURITY_DESCRIPTIONS: Record<MaturityRating, string> = {
  P: 'All audiences',
  K: 'Under 13 with a guardian',
  T13: 'Ages 13 and over',
  T16: 'Ages 16 and over',
  T18: 'Ages 18 and over'
};

/** Read-only metadata. Editing, ratings, watchlist changes and playback belong to later issues. */
export function MovieDetails({ movie }: { movie: MovieDetail }) {
  const { user } = useAuth();
  const isAdmin = user?.roles.includes('ROLE_ADMIN') ?? false;
  const availability = videoAvailability(movie.videoStatus);
  const directors = movie.people.filter((credit) => credit.role === 'DIRECTOR');
  const cast = movie.people.filter((credit) => credit.role === 'ACTOR');
  const otherCredits = movie.people.filter((credit) => credit.role === 'UNKNOWN');
  const maturity = movie.maturityRating;

  return (
    <article aria-labelledby="movie-title" className="grid min-w-0 gap-6 md:grid-cols-3">
      <div className="w-3/5 max-w-sm md:w-full">
        <PosterArt className="rounded-lg" movie={movie} />
      </div>
      <div className="grid min-w-0 content-start gap-6 break-words md:col-span-2">
        <h1 className="text-3xl font-semibold text-white" id="movie-title">{movie.title}</h1>
        <dl className="grid gap-3 text-sm">
          <MetadataField label="Release year">{movie.releaseYear ?? 'Not specified'}</MetadataField>
          <MetadataField label="Maturity rating">
            {maturity === null ? 'Not classified' : maturity === 'UNKNOWN' ? 'Unknown' : (
              <abbr className="no-underline" title={MATURITY_DESCRIPTIONS[maturity]}>{maturity}</abbr>
            )}
          </MetadataField>
          <MetadataField label="Genres">{movie.genres.length ? movie.genres.join(' · ') : 'No genres listed'}</MetadataField>
          <MetadataField label="Availability">
            <div className="flex flex-wrap items-center gap-3">
              <VideoStatusBadge status={movie.videoStatus} />
              {availability.canWatch ? (
                <Link className={primaryButtonClass} to={`/movies/${movie.id}/watch`}>Watch</Link>
              ) : null}
            </div>
          </MetadataField>
          <MetadataField label="Average rating">
            {movie.averageRating === null ? 'No ratings yet' : `${movie.averageRating} / 5`}
          </MetadataField>
        </dl>
        <section aria-labelledby="movie-description-heading">
          <h2 className="text-lg font-semibold text-white" id="movie-description-heading">Description</h2>
          <p className="mt-2 whitespace-pre-line text-slate-300">{movie.description}</p>
        </section>
        <Credits credits={directors} heading="Directors" id="movie-directors-heading" />
        <Credits credits={cast} heading="Cast" id="movie-cast-heading" />
        {otherCredits.length ? <Credits credits={otherCredits} heading="Other credits" id="movie-other-credits-heading" /> : null}
        {movie.userRating !== null || movie.inWatchlist ? (
          <dl className="grid gap-3 text-sm">
            {movie.userRating !== null ? <MetadataField label="Your rating">{movie.userRating} / 5</MetadataField> : null}
            {movie.inWatchlist ? <MetadataField label="Watchlist">In your watchlist</MetadataField> : null}
          </dl>
        ) : null}
        {movie.playbackProgress ? <PlaybackProgress movie={movie} /> : null}
        {isAdmin ? <MovieAdministration movie={movie} /> : null}
      </div>
    </article>
  );
}

function MetadataField({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt className="font-medium text-slate-400">{label}</dt>
      <dd className="mt-1 text-slate-100">{children}</dd>
    </div>
  );
}

function Credits({ credits, heading, id }: { credits: MovieCredit[]; heading: string; id: string }) {
  return (
    <section aria-labelledby={id}>
      <h2 className="text-lg font-semibold text-white" id={id}>{heading}</h2>
      {credits.length ? (
        <ul className="mt-2 space-y-1 text-slate-300">
          {credits.map((credit) => <li key={`${credit.personId}-${credit.role}`}>{credit.name}</li>)}
        </ul>
      ) : <p className="mt-2 text-slate-400">Not listed</p>}
    </section>
  );
}

function PlaybackProgress({ movie }: { movie: MovieDetail }) {
  const progress = movie.playbackProgress;
  if (!progress) {
    return null;
  }
  return (
    <section aria-labelledby="movie-progress-heading">
      <h2 className="text-lg font-semibold text-white" id="movie-progress-heading">Playback progress</h2>
      <p className="mt-2 text-slate-300">
        {progress.currentSeconds} seconds{progress.durationSeconds === null ? '' : ` / ${progress.durationSeconds} seconds`}
        {' · '}{progress.finished ? 'Completed' : 'In progress'}
      </p>
      {progress.lastPlayedAt ? (
        <p className="mt-1 text-sm text-slate-400">Last played: <time dateTime={progress.lastPlayedAt}>{progress.lastPlayedAt}</time></p>
      ) : null}
    </section>
  );
}

function MovieAdministration({ movie }: { movie: MovieDetail }) {
  return (
    <section aria-labelledby="movie-admin-heading" className="grid gap-4 rounded-lg border border-slate-700 p-4">
      <h2 className="text-lg font-semibold text-white" id="movie-admin-heading">Movie administration</h2>
      {movie.status === 'DRAFT' || movie.status === 'ARCHIVED' ? (
        <p className="rounded-md border border-amber-600/60 bg-amber-950/50 p-3 text-sm text-amber-200" role="note">
          This movie is {movie.status.toLowerCase()} and is not visible to viewers.
        </p>
      ) : null}
      <dl className="grid gap-3 text-sm">
        <MetadataField label="Movie status"><MovieStatusBadge status={movie.status} /></MetadataField>
        <MetadataField label="Slug">{movie.slug}</MetadataField>
        <MetadataField label="Video status">{movie.videoStatus ?? 'None'}</MetadataField>
      </dl>
      <Link className={`${secondaryButtonClass} justify-self-start`} to={`/admin/movies/${movie.id}/edit`}>Edit movie</Link>
    </section>
  );
}
