import type { MovieSummary } from '../moviesApi';
import { PosterArt } from './PosterArt';

const MAX_CARD_GENRES = 2;

/** Poster, title, release year (left out when null) and up to two genres, all as plain text. */
export function MovieCard({ movie }: { movie: MovieSummary }) {
  const genres = movie.genres.slice(0, MAX_CARD_GENRES);
  return (
    <article className="flex h-full flex-col overflow-hidden rounded-lg border border-slate-800 bg-slate-900">
      <PosterArt movie={movie} />
      <div className="grid gap-1 p-3">
        <h2 className="line-clamp-2 break-words text-sm font-semibold leading-5 text-white">
          {movie.title}
        </h2>
        {movie.releaseYear !== null ? (
          <p className="text-xs text-slate-300">{movie.releaseYear}</p>
        ) : null}
        {genres.length > 0 ? (
          <p className="truncate text-xs text-slate-400">{genres.join(' · ')}</p>
        ) : null}
      </div>
    </article>
  );
}
