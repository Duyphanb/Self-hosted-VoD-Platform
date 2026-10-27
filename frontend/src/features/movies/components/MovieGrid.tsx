import type { MovieSummary } from '../moviesApi';
import { MovieCard } from './MovieCard';

// 2 columns on phones, then 3 at sm, 4 at md and 5 at lg.
const GRID_CLASS = 'grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5';
const SKELETON_CARD_COUNT = 10;

export function MovieGrid({ movies }: { movies: MovieSummary[] }) {
  return (
    <ul className={GRID_CLASS}>
      {movies.map((movie) => (
        <li key={movie.id}>
          <MovieCard movie={movie} />
        </li>
      ))}
    </ul>
  );
}

/** Card-shaped placeholders; wrap in `LoadingState`, which hides them and announces the status. */
export function MovieGridSkeleton() {
  return (
    <div className={GRID_CLASS}>
      {Array.from({ length: SKELETON_CARD_COUNT }, (_, index) => (
        <div
          className="animate-pulse overflow-hidden rounded-lg border border-slate-800 bg-slate-900 motion-reduce:animate-none"
          key={index}
        >
          <div className="aspect-[2/3] bg-slate-800" />
          <div className="grid gap-2 p-3">
            <div className="h-3 w-3/4 rounded bg-slate-800" />
            <div className="h-3 w-1/3 rounded bg-slate-800" />
          </div>
        </div>
      ))}
    </div>
  );
}
