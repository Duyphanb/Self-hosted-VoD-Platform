import type { MovieSummary } from '../moviesApi';

// Full class names so Tailwind keeps them; white text stays readable on every pair.
const POSTER_GRADIENTS = [
  'from-slate-600 to-slate-900',
  'from-sky-600 to-slate-900',
  'from-indigo-600 to-slate-900',
  'from-teal-600 to-slate-900',
  'from-amber-600 to-slate-900',
  'from-sky-700 to-indigo-950',
  'from-teal-700 to-sky-950',
  'from-amber-700 to-indigo-950'
] as const;

interface PosterArtProps {
  movie: Pick<MovieSummary, 'id' | 'title' | 'genres' | 'releaseYear'>;
  className?: string;
}

/**
 * Generated 2:3 poster. `posterUrl` has no delivery path yet, so it is never loaded. The gradient
 * follows from the movie id, so a movie keeps its colours everywhere. Hidden from assistive
 * technology because the title is always rendered as text next to it.
 */
export function PosterArt({ movie, className = '' }: PosterArtProps) {
  const firstGenre = movie.genres[0];
  return (
    <div
      aria-hidden="true"
      className={`flex aspect-[2/3] w-full flex-col justify-between overflow-hidden bg-gradient-to-br p-3 ${posterGradient(movie.id)} ${className}`}
    >
      <span className="truncate text-xs font-medium uppercase tracking-wide text-white/80">
        {firstGenre ?? ''}
      </span>
      <span className="line-clamp-5 break-words text-lg font-semibold leading-tight text-white">
        {movie.title}
      </span>
      <span className="text-xs font-medium text-white/80">{movie.releaseYear ?? ''}</span>
    </div>
  );
}

function posterGradient(id: string): string {
  return POSTER_GRADIENTS[fnv1a(id) % POSTER_GRADIENTS.length];
}

// 32-bit FNV-1a: a stable, well-spread hash of the id string.
function fnv1a(value: string): number {
  let hash = 0x811c9dc5;
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193);
  }
  return hash >>> 0;
}
