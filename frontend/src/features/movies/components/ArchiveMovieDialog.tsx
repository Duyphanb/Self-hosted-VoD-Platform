import { useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { secondaryButtonClass } from '../../../components/buttonStyles';
import { ErrorState } from '../../../components/StateViews';
import { useModalFocus } from '../../../components/useModalFocus';
import { ApiRequestError, AuthenticationSupersededError } from '../../../lib/api/client';
import { useApiClient } from '../../auth/AuthContext';
import { archiveMovie } from '../adminMoviesApi';
import { movieKeys, type MovieSummary } from '../moviesApi';

interface ArchiveMovieDialogProps {
  movie: Pick<MovieSummary, 'id' | 'title'>;
  returnFocusTo: HTMLElement;
  onClose(): void;
  onArchived(message: string): void;
  onForbidden(): void;
}

/** Archive is a confirmed soft delete; a failed request leaves the dialog open for another try. */
export function ArchiveMovieDialog({ movie, returnFocusTo, onClose, onArchived, onForbidden }: ArchiveMovieDialogProps) {
  const apiClient = useApiClient();
  const queryClient = useQueryClient();
  const dialogRef = useRef<HTMLDivElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const inFlight = useRef(false);
  const completed = useRef(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);

  useModalFocus({
    dialogRef, initialFocusRef: cancelRef,
    onEscape: () => { if (!inFlight.current) onClose(); },
    returnFocus: () => {
      if (!completed.current && returnFocusTo.isConnected) returnFocusTo.focus();
      else document.getElementById('admin-movies-heading')?.focus();
    }
  });

  const confirm = async () => {
    if (inFlight.current) return;
    inFlight.current = true;
    // Both buttons become disabled while saving; keep focus inside the still-focusable dialog.
    dialogRef.current?.focus();
    setPending(true);
    setError(null);
    try {
      await archiveMovie(apiClient, movie.id);
      queryClient.removeQueries({ queryKey: movieKeys.detail(movie.id), exact: true });
      completed.current = true;
      onArchived('Archived');
      void queryClient.invalidateQueries({ queryKey: movieKeys.all });
    } catch (failure) {
      if (failure instanceof ApiRequestError && failure.status === 404) {
        queryClient.removeQueries({ queryKey: movieKeys.detail(movie.id), exact: true });
        completed.current = true;
        onArchived('Movie no longer exists');
        void queryClient.invalidateQueries({ queryKey: movieKeys.all });
      } else if (failure instanceof ApiRequestError && failure.status === 403) {
        onForbidden();
      } else if (!(failure instanceof AuthenticationSupersededError)) {
        setError(failure);
      }
    } finally {
      inFlight.current = false;
      setPending(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/80 p-4" onMouseDown={(event) => { if (event.target === event.currentTarget) event.preventDefault(); }}>
      <div
        aria-describedby="archive-movie-description"
        aria-labelledby="archive-movie-title"
        aria-modal="true"
        className="grid w-full max-w-lg gap-5 rounded-lg border border-slate-700 bg-slate-900 p-6 shadow-xl"
        ref={dialogRef}
        role="dialog"
        tabIndex={-1}
      >
        <h2 className="break-words text-xl font-semibold text-white" id="archive-movie-title">Archive '{movie.title}'?</h2>
        <p className="text-sm text-slate-300" id="archive-movie-description">
          Hidden from users. Video and user data are kept. Restore by setting Draft or Published.
        </p>
        {error !== null ? <ErrorState error={error} title="Movie could not be archived" /> : null}
        <div className="flex justify-end gap-3">
          <button className={secondaryButtonClass} disabled={pending} onClick={onClose} ref={cancelRef} type="button">Cancel</button>
          <button
            className="rounded-md bg-rose-600 px-4 py-2 text-sm font-semibold text-white hover:bg-rose-500 disabled:cursor-wait disabled:opacity-60"
            disabled={pending}
            onClick={() => void confirm()}
            type="button"
          >
            {pending ? 'Archiving…' : 'Archive'}
          </button>
        </div>
        <span className="sr-only" role="status">{pending ? 'Archiving movie…' : ''}</span>
      </div>
    </div>
  );
}
