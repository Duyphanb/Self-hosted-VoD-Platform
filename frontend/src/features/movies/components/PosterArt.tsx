import type { MovieSummary } from '../moviesApi';

// Full class names so Tailwind keeps them. Each pair runs from its lightest colour to a darker one,
// and white text has a WCAG contrast ratio of at least 4.5:1 along the whole gradient. The minimum,
// at the start colour: slate-600 7.58, sky-700 5.93, indigo-600 6.29, teal-700 5.47, amber-700 5.02,
// sky-800 7.56, teal-800 7.58, amber-800 7.09 (PosterArt.test.tsx recomputes them).
const POSTER_GRADIENTS = [
  'from-slate-600 to-slate-900',
  'from-sky-700 to-slate-900',
  'from-indigo-600 to-slate-900',
  'from-teal-700 to-slate-900',
  'from-amber-700 to-slate-900',
  'from-sky-800 to-indigo-950',
  'from-teal-800 to-sky-950',
  'from-amber-800 to-indigo-950'
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
      <span className="truncate text-xs font-medium uppercase tracking-wide text-white">
        {firstGenre ?? ''}
      </span>
      <span className="line-clamp-5 break-words text-lg font-semibold leading-tight text-white">
        {movie.title}
      </span>
      <span className="text-xs font-medium text-white">{movie.releaseYear ?? ''}</span>
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
