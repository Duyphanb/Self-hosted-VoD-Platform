import { Link } from 'react-router-dom';
import type { MovieSummary } from '../moviesApi';
import { PosterArt } from './PosterArt';

const MAX_CARD_GENRES = 2;

const cardClass = 'flex h-full flex-col overflow-hidden rounded-lg border border-slate-800 bg-slate-900 transition '
  + 'hover:border-slate-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sky-400 '
  + 'focus-visible:ring-offset-2 focus-visible:ring-offset-slate-950';

/**
 * One link to the movie's detail page holding the poster, title, release year (left out when null)
 * and up to two genres, all as plain text with nothing interactive inside.
 */
export function MovieCard({ movie }: { movie: MovieSummary }) {
  const genres = movie.genres.slice(0, MAX_CARD_GENRES);
  return (
    <Link className={cardClass} to={movieDetailPath(movie.id)}>
      <PosterArt movie={movie} />
      <div className="grid gap-1 p-3">
        <h2 className="line-clamp-2 break-words text-sm font-semibold leading-5 text-white">
          {movie.title}
        </h2>
        {movie.releaseYear !== null ? (
          <p className="text-xs text-slate-300">{movie.releaseYear}</p>
        ) : null}
        {genres.length > 0 ? (
          <p className="line-clamp-2 break-words text-xs text-slate-400">{genres.join(' · ')}</p>
        ) : null}
      </div>
    </Link>
  );
}

// The decoder accepts only canonical UUIDs; encoding the segment keeps the path safe regardless.
function movieDetailPath(movieId: string): string {
  return `/movies/${encodeURIComponent(movieId)}`;
}
