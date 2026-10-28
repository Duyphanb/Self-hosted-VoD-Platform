import type { MovieSummary } from './moviesApi';

export interface VideoAvailability {
  label: 'Ready' | 'Processing' | 'Not available';
  canWatch: boolean;
}

/** Viewer-facing availability; the admin panel displays the underlying video status separately. */
export function videoAvailability(videoStatus: MovieSummary['videoStatus']): VideoAvailability {
  switch (videoStatus) {
    case 'READY':
      return { label: 'Ready', canWatch: true };
    case 'UPLOADED':
    case 'QUEUED':
    case 'PROCESSING':
      return { label: 'Processing', canWatch: false };
    default:
      return { label: 'Not available', canWatch: false };
  }
}
