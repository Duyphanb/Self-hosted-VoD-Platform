import { type FormEvent, type KeyboardEvent, useEffect, useId, useState } from 'react';
import { secondaryButtonClass } from '../../../components/buttonStyles';
import {
  GENRE_MAX_LENGTH,
  hasActiveFilters,
  MAX_RELEASE_YEAR,
  MIN_RELEASE_YEAR,
  type MovieListFilters,
  parseGenre,
  parseMovieSort,
  parseReleaseYear
} from '../movieListParams';
import type { MovieSort } from '../moviesApi';

/** Typing pauses this long before a genre or year is applied; Enter applies at once. */
const FILTER_DEBOUNCE_MILLISECONDS = 400;

const SORT_LABELS: Record<MovieSort, string> = {
  latest: 'Recently added',
  title: 'Title A–Z'
};

const fieldClass = 'mt-2 block w-full rounded-md border bg-slate-950 px-3 py-2 text-sm text-white outline-none '
  + 'transition placeholder:text-slate-500 focus:ring-2';
const validFieldClass = 'border-slate-700 focus:border-sky-500 focus:ring-sky-500/30';
const invalidFieldClass = 'border-rose-500 focus:border-rose-400 focus:ring-rose-500/30';

interface MovieFiltersProps {
  /** Filters currently applied, read from the URL. */
  filters: MovieListFilters;
  onFiltersChange(filters: MovieListFilters): void;
}

export function MovieFilters({ filters, onFiltersChange }: MovieFiltersProps) {
  const fieldId = useId();
  const [genreDraft, setGenreDraft] = useState(filters.genre ?? '');
  const [yearDraft, setYearDraft] = useState(yearText(filters.year));
  const [appliedGenre, setAppliedGenre] = useState(filters.genre);
  const [appliedYear, setAppliedYear] = useState(filters.year);
  const [yearTouched, setYearTouched] = useState(false);

  const draftGenre = parseGenre(genreDraft);
  const yearEmpty = yearDraft.trim() === '';
  const draftYear = yearEmpty ? null : parseReleaseYear(yearDraft);
  const yearInvalid = !yearEmpty && draftYear === null;

  // Follow URL changes made elsewhere (Back, Clear filters, a rejected filter) during render
  // (https://react.dev/learn/you-might-not-need-an-effect), but keep a draft that already matches
  // the URL, such as "Drama " while the user is still typing.
  if (filters.genre !== appliedGenre) {
    setAppliedGenre(filters.genre);
    if (draftGenre !== filters.genre) {
      setGenreDraft(filters.genre ?? '');
    }
  }
  if (filters.year !== appliedYear) {
    setAppliedYear(filters.year);
    if (yearInvalid || draftYear !== filters.year) {
      setYearDraft(yearText(filters.year));
      setYearTouched(false);
    }
  }

  const { sort } = filters;
  const hasPendingDraft = !yearInvalid
    && (draftGenre !== filters.genre || draftYear !== filters.year);

  useEffect(() => {
    if (!hasPendingDraft) {
      return undefined;
    }
    const timer = window.setTimeout(
      () => onFiltersChange({ genre: draftGenre, year: draftYear, sort }),
      FILTER_DEBOUNCE_MILLISECONDS
    );
    return () => window.clearTimeout(timer);
  }, [draftGenre, draftYear, hasPendingDraft, onFiltersChange, sort]);

  const applyDrafts = () => {
    if (yearInvalid) {
      setYearTouched(true);
      return;
    }
    if (hasPendingDraft) {
      onFiltersChange({ genre: draftGenre, year: draftYear, sort });
    }
  };

  const applyOnEnter = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      applyDrafts();
    }
  };

  const changeSort = (value: string) => {
    const drafts = yearInvalid
      ? { genre: filters.genre, year: filters.year }
      : { genre: draftGenre, year: draftYear };
    onFiltersChange({ ...drafts, sort: parseMovieSort(value) });
  };

  const clearFilters = () => {
    setGenreDraft('');
    setYearDraft('');
    setYearTouched(false);
    if (hasActiveFilters(filters)) {
      onFiltersChange({ genre: null, year: null, sort });
    }
  };

  const genreId = `${fieldId}-genre`;
  const yearId = `${fieldId}-year`;
  const yearErrorId = `${yearId}-error`;
  const sortId = `${fieldId}-sort`;
  const showYearError = yearInvalid && (yearTouched || yearDraft.trim().length >= 4);
  const canClear = hasActiveFilters(filters) || genreDraft !== '' || yearDraft !== '';

  return (
    <form
      aria-label="Movie filters"
      className="grid gap-4 rounded-lg border border-slate-800 bg-slate-900 p-4 sm:grid-cols-2 lg:grid-cols-[minmax(0,1fr)_9rem_12rem_auto]"
      noValidate
      onSubmit={(event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        applyDrafts();
      }}
      role="search"
    >
      <div>
        <label className="block text-sm font-medium text-slate-200" htmlFor={genreId}>Genre</label>
        <input
          autoComplete="off"
          className={`${fieldClass} ${validFieldClass}`}
          id={genreId}
          maxLength={GENRE_MAX_LENGTH}
          name="genre"
          onChange={(event) => setGenreDraft(event.target.value)}
          onKeyDown={applyOnEnter}
          placeholder="Any genre"
          type="text"
          value={genreDraft}
        />
      </div>
      <div>
        <label className="block text-sm font-medium text-slate-200" htmlFor={yearId}>Year</label>
        <input
          aria-describedby={showYearError ? yearErrorId : undefined}
          aria-invalid={showYearError ? 'true' : 'false'}
          className={`${fieldClass} ${showYearError ? invalidFieldClass : validFieldClass}`}
          id={yearId}
          inputMode="numeric"
          max={MAX_RELEASE_YEAR}
          min={MIN_RELEASE_YEAR}
          name="year"
          onBlur={() => setYearTouched(true)}
          onChange={(event) => {
            setYearDraft(event.target.value);
            setYearTouched(false);
          }}
          onKeyDown={applyOnEnter}
          placeholder="Any year"
          step={1}
          type="number"
          value={yearDraft}
        />
        {showYearError ? (
          <p className="mt-2 text-sm text-rose-300" id={yearErrorId}>
            Enter a year from {MIN_RELEASE_YEAR} to {MAX_RELEASE_YEAR}.
          </p>
        ) : null}
      </div>
      <div>
        <label className="block text-sm font-medium text-slate-200" htmlFor={sortId}>Sort by</label>
        <select
          className={`${fieldClass} ${validFieldClass}`}
          id={sortId}
          name="sort"
          onChange={(event) => changeSort(event.target.value)}
          value={sort}
        >
          {Object.entries(SORT_LABELS).map(([value, label]) => (
            <option key={value} value={value}>{label}</option>
          ))}
        </select>
      </div>
      <div className="flex items-start lg:pt-7">
        <button
          className={secondaryButtonClass}
          disabled={!canClear}
          onClick={clearFilters}
          type="button"
        >
          Clear filters
        </button>
      </div>
    </form>
  );
}

function yearText(year: number | null): string {
  return year === null ? '' : String(year);
}
