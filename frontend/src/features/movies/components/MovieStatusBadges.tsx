import { videoAvailability } from '../movieAvailability';
import type { MovieSummary } from '../moviesApi';

const badgeClass = 'inline-flex rounded-full border px-3 py-1 text-sm font-medium';
const neutralClass = 'border-slate-600 bg-slate-800 text-slate-200';

const movieStatusLabels: Record<MovieSummary['status'], string> = {
  DRAFT: 'Draft',
  PUBLISHED: 'Published',
  ARCHIVED: 'Archived',
  UNKNOWN: 'Unknown'
};

/** Availability is always written as text; colour is supplementary. */
export function VideoStatusBadge({ status }: { status: MovieSummary['videoStatus'] }) {
  const availability = videoAvailability(status);
  const colourClass = availability.canWatch
    ? 'border-emerald-700 bg-emerald-950 text-emerald-200'
    : availability.label === 'Processing'
      ? 'border-amber-700 bg-amber-950 text-amber-200'
      : neutralClass;
  return <span className={`${badgeClass} ${colourClass}`}>{availability.label}</span>;
}

/** Render only inside an admin-only view; catalogue status is not viewer-facing metadata. */
export function MovieStatusBadge({ status }: { status: MovieSummary['status'] }) {
  return <span className={`${badgeClass} ${neutralClass}`}>{movieStatusLabels[status]}</span>;
}
