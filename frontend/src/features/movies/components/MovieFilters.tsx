import { type KeyboardEvent, useId, useRef, useState } from 'react';
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

const SORT_LABELS: Record<MovieSort, string> = {
  latest: 'Recently added',
  title: 'Title A–Z'
};

const YEAR_LENGTH = 4;

const fieldClass = 'mt-2 block w-full rounded-md border bg-slate-950 px-3 py-2 text-sm text-white outline-none '
  + 'transition placeholder:text-slate-400 focus:ring-2';
const validFieldClass = 'border-slate-700 focus:border-sky-500 focus:ring-sky-500/30';
const invalidFieldClass = 'border-rose-500 focus:border-rose-400 focus:ring-rose-500/30';

type FilterValues = Pick<MovieListFilters, 'genre' | 'year'>;

interface MovieFiltersProps {
  /** Filters currently applied, read from the URL. */
  filters: MovieListFilters;
  onFiltersChange(filters: MovieListFilters): void;
}

/**
 * Genre and Year apply on Enter, or when the field loses focus after a change; Sort applies at once.
 * A value the API would reject is flagged next to its field and never sent, and the filter already
 * applied stays in effect until the value is corrected.
 */
export function MovieFilters({ filters, onFiltersChange }: MovieFiltersProps) {
  const fieldId = useId();
  const genreInputRef = useRef<HTMLInputElement>(null);
  const [genreDraft, setGenreDraft] = useState(filters.genre ?? '');
  const [yearDraft, setYearDraft] = useState(yearText(filters.year));
  const [genreChecked, setGenreChecked] = useState(false);
  const [yearChecked, setYearChecked] = useState(false);
  // The URL values this form last rendered, and the values it last asked for itself.
  const [seen, setSeen] = useState<FilterValues>({ genre: filters.genre, year: filters.year });
  const [requested, setRequested] = useState<FilterValues | null>(null);

  // Follow a URL change made elsewhere (Back, Clear filters in the empty state, a filter the API
  // rejected) during render (https://react.dev/learn/you-might-not-need-an-effect). A change this
  // form asked for keeps the fields as they are, so a keystroke typed meanwhile is not lost.
  if (filters.genre !== seen.genre || filters.year !== seen.year) {
    const ownRequest = requested !== null
      && requested.genre === filters.genre
      && requested.year === filters.year;
    setSeen({ genre: filters.genre, year: filters.year });
    setRequested(null);
    if (!ownRequest && filters.genre !== seen.genre) {
      setGenreDraft(filters.genre ?? '');
      setGenreChecked(false);
    }
    if (!ownRequest && filters.year !== seen.year) {
      setYearDraft(yearText(filters.year));
      setYearChecked(false);
    }
  }

  const genreInput = genreDraft.trim();
  const genreInvalid = genreInput !== '' && parseGenre(genreInput) === null;
  const yearInput = yearDraft.trim();
  const yearInvalid = yearInput !== '' && parseReleaseYear(yearInput) === null;
  // A valid draft replaces the applied value; an invalid one leaves the applied value in place.
  const nextGenre = genreInvalid ? filters.genre : parseGenre(genreInput);
  const nextYear = yearInvalid ? filters.year : parseReleaseYear(yearInput);

  const apply = (sort: MovieSort) => {
    setGenreChecked(true);
    setYearChecked(true);
    if (nextGenre === filters.genre && nextYear === filters.year && sort === filters.sort) {
      return;
    }
    setRequested({ genre: nextGenre, year: nextYear });
    onFiltersChange({ genre: nextGenre, year: nextYear, sort });
  };

  const applyOnEnter = (event: KeyboardEvent<HTMLInputElement>) => {
    // Enter that confirms an IME composition is not a request to apply.
    if (event.key === 'Enter' && !event.nativeEvent.isComposing) {
      event.preventDefault();
      apply(filters.sort);
    }
  };

  const clearFilters = () => {
    setGenreDraft('');
    setYearDraft('');
    setGenreChecked(false);
    setYearChecked(false);
    if (hasActiveFilters(filters)) {
      setRequested({ genre: null, year: null });
      onFiltersChange({ genre: null, year: null, sort: filters.sort });
    }
    // The button disables itself, so focus moves to the first field instead of falling to the page.
    genreInputRef.current?.focus();
  };

  const genreId = `${fieldId}-genre`;
  const genreErrorId = `${genreId}-error`;
  const yearId = `${fieldId}-year`;
  const yearErrorId = `${yearId}-error`;
  const sortId = `${fieldId}-sort`;
  const showGenreError = genreInvalid && genreChecked;
  // A year is flagged once it is checked, or as soon as it cannot become valid by typing more.
  const showYearError = yearInvalid
    && (yearChecked || yearInput.length >= YEAR_LENGTH || /\D/.test(yearInput));
  const canClear = hasActiveFilters(filters) || genreDraft !== '' || yearDraft !== '';

  return (
    <form
      aria-label="Movie filters"
      className="grid gap-4 rounded-lg border border-slate-800 bg-slate-900 p-4 sm:grid-cols-2 lg:grid-cols-[minmax(0,1fr)_9rem_12rem_auto]"
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        apply(filters.sort);
      }}
      role="search"
    >
      <div>
        <label className="block text-sm font-medium text-slate-200" htmlFor={genreId}>Genre</label>
        <input
          aria-describedby={showGenreError ? genreErrorId : undefined}
          aria-invalid={showGenreError ? 'true' : 'false'}
          autoComplete="off"
          className={`${fieldClass} ${showGenreError ? invalidFieldClass : validFieldClass}`}
          id={genreId}
          // Counts UTF-16 code units, so the field never exceeds the code-point limit of the API.
          maxLength={GENRE_MAX_LENGTH}
          name="genre"
          onBlur={() => {
            if (genreInvalid || nextGenre !== filters.genre) {
              apply(filters.sort);
            }
          }}
          onChange={(event) => {
            setGenreDraft(event.target.value);
            setGenreChecked(false);
          }}
          onKeyDown={applyOnEnter}
          placeholder="Any genre"
          ref={genreInputRef}
          type="text"
          value={genreDraft}
        />
        {showGenreError ? (
          <p className="mt-2 text-sm text-rose-300" id={genreErrorId} role="alert">
            Enter a genre with at least one Latin letter (a–z) or digit.
          </p>
        ) : null}
      </div>
      <div>
        <label className="block text-sm font-medium text-slate-200" htmlFor={yearId}>Year</label>
        <input
          aria-describedby={showYearError ? yearErrorId : undefined}
          aria-invalid={showYearError ? 'true' : 'false'}
          autoComplete="off"
          className={`${fieldClass} ${showYearError ? invalidFieldClass : validFieldClass}`}
          id={yearId}
          // Text, not number: a number field reports '' for input it cannot parse, which would
          // silently drop the year filter instead of flagging the value.
          inputMode="numeric"
          maxLength={YEAR_LENGTH}
          name="year"
          onBlur={() => {
            if (yearInvalid || nextYear !== filters.year) {
              apply(filters.sort);
            }
          }}
          onChange={(event) => {
            setYearDraft(event.target.value);
            setYearChecked(false);
          }}
          onKeyDown={applyOnEnter}
          pattern="[0-9]*"
          placeholder="Any year"
          type="text"
          value={yearDraft}
        />
        {showYearError ? (
          <p className="mt-2 text-sm text-rose-300" id={yearErrorId} role="alert">
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
          onChange={(event) => apply(parseMovieSort(event.target.value))}
          value={filters.sort}
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
