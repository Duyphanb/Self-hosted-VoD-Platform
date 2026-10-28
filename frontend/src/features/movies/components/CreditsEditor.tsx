import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { secondaryButtonClass } from '../../../components/buttonStyles';
import { ApiRequestError, AuthenticationSupersededError } from '../../../lib/api/client';
import { errorDetails } from '../../../lib/api/errorDetails';
import { useApiClient } from '../../auth/AuthContext';
import { createPerson, type Person } from '../adminMoviesApi';
import { usePeopleSearch } from '../adminMovieQueries';
import { validatePersonName, type MovieFormCredit, type MovieFormErrors } from '../movieFormModel';

interface CreditsEditorProps {
  value: MovieFormCredit[];
  onChange: (credits: MovieFormCredit[]) => void;
  errors?: MovieFormErrors;
  disabled?: boolean;
  onForbidden?: () => void;
}

export function CreditsEditor({ value, onChange, errors = {}, disabled = false, onForbidden }: CreditsEditorProps) {
  const latest = useRef(value);
  useLayoutEffect(() => { latest.current = value; }, [value]);

  const update = (key: string, patch: Partial<MovieFormCredit>): string | null => {
    const credits = latest.current;
    const current = credits.find((credit) => credit.key === key);
    if (!current) return null;
    const next = { ...current, ...patch };
    if (next.personId && credits.some((credit) => credit.key !== key && credit.personId === next.personId && credit.role === next.role)) {
      return 'This person already has this role.';
    }
    onChange(credits.map((credit) => credit.key === key ? next : credit));
    return null;
  };

  const move = (index: number, step: number) => {
    if (disabled) return;
    const next = [...value];
    const destination = index + step;
    if (destination < 0 || destination >= next.length) return;
    [next[index], next[destination]] = [next[destination], next[index]];
    onChange(next);
    window.setTimeout(() => document.getElementById(`movie-people-${destination}-personId`)?.focus(), 0);
  };

  return (
    <div className="grid gap-4">
      <p className="text-sm text-slate-400">Credits are displayed in this order. A new person is saved immediately, even if you cancel this movie.</p>
      {value.map((credit, index) => (
        <CreditRow
          credit={credit}
          disabled={disabled}
          errors={errors}
          index={index}
          key={credit.key}
          onForbidden={onForbidden}
          onMove={(step) => move(index, step)}
          onRemove={() => {
            if (disabled) return;
            onChange(value.filter((row) => row.key !== credit.key));
            window.setTimeout(() => document.getElementById(index < value.length - 1 ? `movie-people-${index}-personId` : 'movie-people')?.focus(), 0);
          }}
          onUpdate={(patch) => update(credit.key, patch)}
          total={value.length}
        />
      ))}
      {errors.people ? <p className="text-sm text-rose-300" id="movie-people-error" role="alert">{errors.people}</p> : null}
      <button
        aria-describedby={errors.people ? 'movie-people-error' : undefined}
        className={`${secondaryButtonClass} justify-self-start`}
        disabled={disabled || value.length >= 50}
        id="movie-people"
        onClick={() => onChange([...value, { key: crypto.randomUUID(), personId: '', name: '', role: 'ACTOR' }])}
        type="button"
      >Add credit</button>
    </div>
  );
}

interface CreditRowProps {
  credit: MovieFormCredit;
  index: number;
  total: number;
  errors: MovieFormErrors;
  disabled: boolean;
  onUpdate: (patch: Partial<MovieFormCredit>) => string | null;
  onMove: (step: number) => void;
  onRemove: () => void;
  onForbidden?: () => void;
}

function CreditRow({ credit, index, total, errors, disabled, onUpdate, onMove, onRemove, onForbidden }: CreditRowProps) {
  const apiClient = useApiClient();
  const queryClient = useQueryClient();
  const [query, setQuery] = useState(credit.name);
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const [creating, setCreating] = useState(false);
  const [localError, setLocalError] = useState<string | null>(null);
  const createInFlight = useRef(false);
  const mounted = useRef(false);
  const latestUpdate = useRef(onUpdate);
  const listId = useId();
  const inputId = `movie-people-${index}-personId`;
  const errorId = `${inputId}-error`;
  const roleId = `movie-people-${index}-role`;
  const personError = localError ?? errors[`people[${index}].personId`] ?? errors[`people[${index}].name`] ?? errors[`people[${index}]`];
  const roleError = errors[`people[${index}].role`];
  const queryTooLong = Array.from(credit.name).length > 100;
  const canSearch = open && !credit.personId && !queryTooLong && query === credit.name;
  const people = usePeopleSearch(query, page, canSearch);
  const options = canSearch ? people.data?.items ?? [] : [];
  const searchFailed = canSearch && people.isError && !(people.error instanceof AuthenticationSupersededError);
  const forbidden = people.error instanceof ApiRequestError && people.error.status === 403;
  const busy = disabled || creating;

  useLayoutEffect(() => { latestUpdate.current = onUpdate; }, [onUpdate]);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);
  useEffect(() => {
    // An unchanged (including initially blank) query must not reset a page the admin just chose.
    if (query === credit.name) return;
    const timeout = window.setTimeout(() => { setQuery(credit.name); setPage(0); }, 250);
    return () => window.clearTimeout(timeout);
  }, [credit.name, query]);
  useEffect(() => {
    if (forbidden) onForbidden?.();
  }, [forbidden, onForbidden]);

  const select = (person: Person) => {
    if (busy) return;
    const message = latestUpdate.current({ personId: person.id, name: person.name });
    setLocalError(message);
    setOpen(false);
  };

  const create = async () => {
    if (disabled || createInFlight.current) return;
    const invalid = validatePersonName(credit.name);
    if (invalid) { setLocalError(invalid); return; }
    createInFlight.current = true;
    setCreating(true);
    setLocalError(null);
    try {
      const person = await createPerson(apiClient, credit.name);
      void queryClient.invalidateQueries({ queryKey: ['admin-catalog', 'people'] });
      if (mounted.current) {
        setLocalError(latestUpdate.current({ personId: person.id, name: person.name }));
        setOpen(false);
      }
    } catch (error) {
      if (error instanceof ApiRequestError && error.status === 403) onForbidden?.();
      if (mounted.current && !(error instanceof AuthenticationSupersededError)) {
        const fieldError = error instanceof ApiRequestError ? error.apiError?.fieldErrors?.find((field) => field.field === 'name')?.message : null;
        setLocalError(fieldError ?? errorDetails(error).message);
      }
    } finally {
      createInFlight.current = false;
      if (mounted.current) setCreating(false);
    }
  };

  return (
    <fieldset className="grid min-w-0 gap-3 rounded-md border border-slate-700 p-4" disabled={busy}>
      <legend className="px-1 text-sm font-medium">Credit {index + 1}</legend>
      <div className="relative grid gap-2" onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
      }}>
        <label className="text-sm font-medium text-slate-200" htmlFor={inputId}>Person {index + 1}</label>
        <input
          aria-activedescendant={open && options[active] ? `${listId}-${active}` : undefined}
          aria-autocomplete="list"
          aria-controls={open ? listId : undefined}
          aria-describedby={personError ? errorId : undefined}
          aria-expanded={open}
          aria-invalid={Boolean(personError)}
          autoComplete="off"
          className="w-full rounded-md border border-slate-600 bg-slate-950 px-3 py-2 text-white focus:ring-2 focus:ring-sky-400"
          id={inputId}
          onChange={(event) => {
            setLocalError(latestUpdate.current({ name: event.target.value, personId: '' }));
            setOpen(true);
            setActive(-1);
          }}
          onFocus={() => { if (!credit.personId) setOpen(true); }}
          onKeyDown={(event) => {
            if (event.nativeEvent.isComposing) return;
            if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
              event.preventDefault();
              setOpen(true);
              if (options.length) setActive((position) => ((position < 0 && event.key === 'ArrowUp' ? options.length : position)
                + (event.key === 'ArrowDown' ? 1 : -1) + options.length) % options.length);
            } else if (event.key === 'Enter') {
              event.preventDefault();
              if (open && options[active]) select(options[active]);
            } else if (event.key === 'Escape') {
              event.preventDefault();
              setOpen(false);
            }
          }}
          role="combobox"
          value={credit.name}
        />
        {open ? (
          <ul aria-label={`People for credit ${index + 1}`} className="grid gap-1 rounded border border-slate-700 bg-slate-900 p-2" id={listId} role="listbox">
            {options.map((person, position) => (
              <li aria-selected={position === active} id={`${listId}-${position}`} key={person.id} role="option">
                <button className="w-full rounded px-2 py-1 text-left hover:bg-slate-700" onClick={() => select(person)} onMouseDown={(event) => event.preventDefault()} tabIndex={-1} type="button">
                  {person.name}<span className="ml-2 text-xs text-slate-400">{person.id}</span>
                </button>
              </li>
            ))}
          </ul>
        ) : null}
        {canSearch && people.isFetching ? <p className="text-sm text-slate-400" role="status">Searching people…</p> : null}
        {canSearch && people.data && !options.length ? <p className="text-sm text-slate-400">No matching people.</p> : null}
        {canSearch && people.data && people.data.page.totalPages > 1 ? (
          <div aria-label={`People search pages for credit ${index + 1}`} className="flex flex-wrap items-center gap-2" role="group">
            <button
              className={secondaryButtonClass}
              disabled={people.isFetching || page === 0}
              onClick={() => { document.getElementById(inputId)?.focus(); setPage((current) => current - 1); setActive(-1); }}
              onMouseDown={(event) => event.preventDefault()}
              type="button"
            >Previous people</button>
            <span className="text-sm text-slate-400">Page {page + 1} of {people.data.page.totalPages}</span>
            <button
              className={secondaryButtonClass}
              disabled={people.isFetching || page + 1 >= people.data.page.totalPages}
              onClick={() => { document.getElementById(inputId)?.focus(); setPage((current) => current + 1); setActive(-1); }}
              onMouseDown={(event) => event.preventDefault()}
              type="button"
            >Next people</button>
          </div>
        ) : null}
        {queryTooLong && !credit.personId ? <p className="text-sm text-slate-400">Use at most 100 characters to search. You can still create a person with a longer name.</p> : null}
        {searchFailed ? (
          <div className="text-sm text-rose-300">
            <p>{errorDetails(people.error).message}</p>
            <button className={secondaryButtonClass} onClick={() => void people.refetch()} onMouseDown={(event) => event.preventDefault()} type="button">Retry people search</button>
          </div>
        ) : null}
        {!credit.personId && credit.name.trim() ? (
          <button className={`${secondaryButtonClass} justify-self-start`} onClick={() => void create()} type="button">
            {creating ? 'Creating person…' : `Create new person '${credit.name.trim()}'`}
          </button>
        ) : null}
        {personError ? <p className="text-sm text-rose-300" id={errorId} role="alert">{personError}</p> : null}
      </div>
      <label className="text-sm font-medium text-slate-200" htmlFor={roleId}>Role {index + 1}</label>
      <select
        aria-describedby={roleError ? `${roleId}-error` : undefined}
        aria-invalid={Boolean(roleError)}
        className="rounded-md border border-slate-600 bg-slate-950 px-3 py-2 text-white"
        id={roleId}
        onChange={(event) => setLocalError(latestUpdate.current({ role: event.target.value }))}
        value={credit.role}
      >
        {credit.role !== 'ACTOR' && credit.role !== 'DIRECTOR' ? <option value={credit.role}>Choose a role</option> : null}
        <option value="ACTOR">Actor</option>
        <option value="DIRECTOR">Director</option>
      </select>
      {roleError ? <p className="text-sm text-rose-300" id={`${roleId}-error`} role="alert">{roleError}</p> : null}
      <div className="flex flex-wrap gap-2">
        <button aria-label={`Move credit ${index + 1} up`} className={secondaryButtonClass} disabled={index === 0} onClick={() => onMove(-1)} type="button">Move up</button>
        <button aria-label={`Move credit ${index + 1} down`} className={secondaryButtonClass} disabled={index === total - 1} onClick={() => onMove(1)} type="button">Move down</button>
        <button aria-label={`Remove credit ${index + 1}`} className={secondaryButtonClass} onClick={onRemove} type="button">Remove</button>
      </div>
    </fieldset>
  );
}
