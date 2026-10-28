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

let nextCreditKey = 0;

export function CreditsEditor({ value, onChange, errors = {}, disabled = false, onForbidden }: CreditsEditorProps) {
  const latest = useRef(value);
  const moveFocus = useRef<{ key: string; direction: 'up' | 'down' } | null>(null);
  useLayoutEffect(() => {
    latest.current = value;
    const moved = moveFocus.current;
    if (!moved) return;
    moveFocus.current = null;
    const position = value.findIndex((credit) => credit.key === moved.key);
    if (position < 0) return;
    const preferred = document.getElementById(`movie-people-${position}-move-${moved.direction}`);
    const opposite = document.getElementById(`movie-people-${position}-move-${moved.direction === 'up' ? 'down' : 'up'}`);
    if (preferred instanceof HTMLButtonElement && !preferred.disabled) preferred.focus();
    else opposite?.focus();
  }, [value]);

  const change = (next: MovieFormCredit[]) => {
    // Async creations can complete in one React batch; the next update must see this array now.
    latest.current = next;
    onChange(next);
  };

  const update = (key: string, patch: Partial<MovieFormCredit>): string | null => {
    const credits = latest.current;
    const current = credits.find((credit) => credit.key === key);
    if (!current) return null;
    const next = { ...current, ...patch };
    if (next.personId && credits.some((credit) => credit.key !== key && credit.personId === next.personId && credit.role === next.role)) {
      return 'This person already has this role.';
    }
    change(credits.map((credit) => credit.key === key ? next : credit));
    return null;
  };

  const move = (index: number, step: number) => {
    if (disabled) return;
    const next = [...latest.current];
    const destination = index + step;
    if (destination < 0 || destination >= next.length) return;
    [next[index], next[destination]] = [next[destination], next[index]];
    moveFocus.current = { key: next[destination].key, direction: step < 0 ? 'up' : 'down' };
    change(next);
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
            change(latest.current.filter((row) => row.key !== credit.key));
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
        onClick={() => {
          if (disabled || latest.current.length >= 50) return;
          change([...latest.current, { key: `new-credit-${++nextCreditKey}`, personId: '', name: '', role: 'ACTOR' }]);
        }}
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
  const rowRef = useRef<HTMLFieldSetElement>(null);
  const personRef = useRef<HTMLInputElement>(null);
  const restoreCreationFocus = useRef(false);
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
  useLayoutEffect(() => {
    if (!creating && !disabled && restoreCreationFocus.current) {
      restoreCreationFocus.current = false;
      personRef.current?.focus();
    }
  }, [creating, disabled]);
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
    // Keep a stable focus target while this row's controls become disabled.
    rowRef.current?.focus();
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
      if (mounted.current) {
        restoreCreationFocus.current = true;
        setCreating(false);
      }
    }
  };

  return (
    <fieldset aria-busy={creating} className="grid min-w-0 gap-3 rounded-md border border-slate-700 p-4 focus:outline-none focus:ring-2 focus:ring-sky-400" disabled={busy} ref={rowRef} tabIndex={-1}>
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
          ref={personRef}
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
        <p aria-atomic="true" aria-label={`People search feedback for credit ${index + 1}`} className="text-sm text-slate-400" role="status">
          {canSearch ? people.isFetching ? 'Searching people…' : people.data ? options.length ? `${people.data.page.totalElements} matching people.` : 'No matching people.' : '' : ''}
        </p>
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
        <button aria-label={`Move credit ${index + 1} up`} className={secondaryButtonClass} disabled={index === 0} id={`movie-people-${index}-move-up`} onClick={() => onMove(-1)} type="button">Move up</button>
        <button aria-label={`Move credit ${index + 1} down`} className={secondaryButtonClass} disabled={index === total - 1} id={`movie-people-${index}-move-down`} onClick={() => onMove(1)} type="button">Move down</button>
        <button aria-label={`Remove credit ${index + 1}`} className={secondaryButtonClass} onClick={onRemove} type="button">Remove</button>
      </div>
    </fieldset>
  );
}
