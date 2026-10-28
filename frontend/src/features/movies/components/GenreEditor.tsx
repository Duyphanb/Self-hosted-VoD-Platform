import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { secondaryButtonClass } from '../../../components/buttonStyles';
import { ApiRequestError, AuthenticationSupersededError } from '../../../lib/api/client';
import { errorDetails } from '../../../lib/api/errorDetails';
import { slugify } from '../../../lib/slug';
import { useAdminGenres } from '../adminMovieQueries';
import { normalizeCatalogName, validateGenreName, type MovieFormErrors } from '../movieFormModel';

interface GenreEditorProps {
  value: string[];
  onChange: (genres: string[]) => void;
  errors?: MovieFormErrors;
  disabled?: boolean;
  onForbidden?: () => void;
  onDraftChange?: (dirty: boolean) => void;
}

export function GenreEditor({ value, onChange, errors = {}, disabled = false, onForbidden, onDraftChange }: GenreEditorProps) {
  const [draft, setDraft] = useState('');
  const [localError, setLocalError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const removalFocus = useRef<number | null>(null);
  const genres = useAdminGenres();
  const forbidden = genres.error instanceof ApiRequestError && genres.error.status === 403;
  const failed = genres.isError && !(genres.error instanceof AuthenticationSupersededError);
  const indexedErrors = value.map((_, index) => errors[`genres[${index}]`]);
  const error = localError ?? errors.genres;
  const errorIds = [error ? 'movie-genres-error' : '', ...indexedErrors.map((message, index) => message ? `movie-genres-${index}-error` : '')]
    .filter(Boolean).join(' ') || undefined;

  useEffect(() => {
    if (forbidden) onForbidden?.();
  }, [forbidden, onForbidden]);

  useLayoutEffect(() => {
    if (removalFocus.current === null) return;
    const index = removalFocus.current;
    removalFocus.current = null;
    const nextChip = document.getElementById(`movie-genre-${index}-remove`);
    if (nextChip) nextChip.focus();
    else inputRef.current?.focus();
  }, [value]);

  const changeDraft = (next: string) => {
    setDraft(next);
    setLocalError(null);
    onDraftChange?.(next.length > 0);
  };

  const addGenre = () => {
    if (disabled) return;
    const invalid = validateGenreName(draft);
    const normalized = normalizeCatalogName(draft);
    const message = invalid ?? (value.length >= 10 ? 'Choose at most 10 genres.' : null)
      ?? (value.some((genre) => slugify(genre) === slugify(normalized)) ? 'This genre is already selected.' : null);
    if (message) {
      setLocalError(message);
      return;
    }
    onChange([...value, normalized]);
    changeDraft('');
  };

  return (
    <div className="grid gap-3">
      <label className="text-sm font-medium text-slate-200" htmlFor="movie-genres">Add genre</label>
      <ul aria-label="Selected genres" className="flex flex-wrap gap-2">
        {value.map((genre, index) => (
          <li className="rounded-md border border-slate-600 px-3 py-1 text-sm" key={`${genre}-${index}`}>
            <span>{genre}</span>{' '}
            <button
              aria-label={`Remove genre ${genre}`}
              className="ml-2 rounded px-1 text-slate-300 hover:text-white focus-visible:ring-2 focus-visible:ring-sky-400"
              disabled={disabled}
              id={`movie-genre-${index}-remove`}
              onClick={() => {
                removalFocus.current = Math.min(index, value.length - 2);
                onChange(value.filter((_, position) => position !== index));
                setLocalError(null);
              }}
              type="button"
            >×</button>
            {indexedErrors[index] ? <p className="mt-1 text-rose-300" id={`movie-genres-${index}-error`}>{indexedErrors[index]}</p> : null}
          </li>
        ))}
      </ul>
      <div className="flex flex-wrap gap-2">
        <input
          aria-describedby={errorIds ? `movie-genres-hint ${errorIds}` : 'movie-genres-hint'}
          aria-invalid={Boolean(errorIds)}
          className="min-w-0 flex-1 rounded-md border border-slate-600 bg-slate-950 px-3 py-2 text-white focus:ring-2 focus:ring-sky-400"
          disabled={disabled}
          id="movie-genres"
          list="movie-genre-suggestions"
          ref={inputRef}
          onChange={(event) => changeDraft(event.target.value)}
          onKeyDown={(event) => {
            if (disabled || event.nativeEvent.isComposing) return;
            if (event.key === 'Enter' || event.key === ',') {
              event.preventDefault();
              addGenre();
            } else if (event.key === 'Backspace' && draft === '' && value.length) {
              event.preventDefault();
              onChange(value.slice(0, -1));
              setLocalError(null);
            }
          }}
          value={draft}
        />
        <datalist id="movie-genre-suggestions">
          {(genres.data ?? []).filter((genre) => !value.some((selected) => slugify(selected) === genre.slug))
            .map((genre) => <option key={genre.id} value={genre.name} />)}
        </datalist>
        <button className={secondaryButtonClass} disabled={disabled} onClick={addGenre} type="button">Add genre</button>
      </div>
      <p className="text-sm text-slate-400" id="movie-genres-hint">Choose 1–10 genres. Press Enter or comma to add; Backspace in an empty input removes the last genre.</p>
      {error ? <p className="text-sm text-rose-300" id="movie-genres-error" role="alert">{error}</p> : null}
      {failed && !forbidden ? (
        <div className="text-sm text-rose-300">
          <p>Genre suggestions could not be loaded. {errorDetails(genres.error).message}</p>
          <button className={secondaryButtonClass} disabled={genres.isFetching || disabled} onClick={() => void genres.refetch()} type="button">Retry genre suggestions</button>
        </div>
      ) : null}
    </div>
  );
}
