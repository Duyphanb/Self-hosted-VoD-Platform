import { useEffect, useReducer, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { primaryButtonClass, secondaryButtonClass } from '../../../components/buttonStyles';
import { ErrorState, LoadingState } from '../../../components/StateViews';
import { ApiRequestError, AuthenticationSupersededError } from '../../../lib/api/client';
import { isCanonicalUuid } from '../../../lib/uuid';
import { slugify } from '../../../lib/slug';
import { ForbiddenPage } from '../../../pages/AccessPages';
import { NotFoundPage } from '../../../pages/NotFoundPage';
import { useApiClient } from '../../auth/AuthContext';
import { createMovie, updateMovie, type UpsertMovieRequest } from '../adminMoviesApi';
import { getAdminMoviesReturnTo } from '../adminMovieNavigation';
import { CreditsEditor } from '../components/CreditsEditor';
import { GenreEditor } from '../components/GenreEditor';
import { UnsavedMovieChanges } from '../components/UnsavedMovieChanges';
import { createEmptyMovieForm, mapMovieFormError, movieDetailToForm, toMoviePayload, trimCatalogText, validateMovieForm,
  type MovieFormErrors, type MovieFormValues } from '../movieFormModel';
import { MATURITY_RATINGS, movieKeys, type MovieDetail } from '../moviesApi';
import { useMovieDetail } from '../movieQueries';

export function AdminMovieFormPage() {
  const movieId = useParams().movieId;
  return movieId === undefined
    ? <MovieForm initialValues={createEmptyMovieForm()} key="new" />
    : <EditMovieRequest key={movieId.toLowerCase()} movieId={movieId.toLowerCase()} />;
}

function EditMovieRequest({ movieId }: { movieId: string }) {
  const { data, error, isError, isFetching, isFetchedAfterMount, isSuccess, refetch } = useMovieDetail(movieId, { refetchOnMount: 'always' });
  const [initialMovie, setInitialMovie] = useState<MovieDetail | null>(null);
  // Only a settled GET from this visit may initialize a full-replacement edit form.
  if (initialMovie === null && isFetchedAfterMount && isSuccess && !isFetching) setInitialMovie(data);
  const failed = isError && !(error instanceof AuthenticationSupersededError);
  if (!isCanonicalUuid(movieId) || (failed && error instanceof ApiRequestError && [400, 404].includes(error.status))) {
    return <NotFoundPage title="Movie not found" description="The movie could not be found." link={{ to: '/admin/movies', label: 'Back to movies' }} />;
  }
  if (failed && error instanceof ApiRequestError && error.status === 403) return <ForbiddenPage />;
  if (failed && initialMovie === null) return <ErrorState error={error} headingLevel={1} isRetrying={isFetching} onRetry={() => void refetch()} title="Movie could not be loaded" />;
  if (initialMovie === null) return <><h1 className="text-3xl font-semibold">Edit movie</h1><LoadingState label="Loading movie…" /></>;
  // Initial state is captured once: background refetches must not overwrite an unsaved draft.
  return (
    <div className="grid gap-6">
      {failed ? <ErrorState error={error} isRetrying={isFetching} onRetry={() => void refetch()} title="Movie could not be refreshed; your changes are kept" /> : null}
      <MovieForm initialValues={movieDetailToForm(initialMovie)} movieId={movieId} />
    </div>
  );
}

interface FormState { values: MovieFormValues; slugEdited: boolean }
function formReducer(state: FormState, patch: Partial<MovieFormValues>): FormState {
  const values = { ...state.values, ...patch };
  const slugEdited = state.slugEdited || patch.slug !== undefined;
  if (patch.title !== undefined && !slugEdited) values.slug = slugify(patch.title);
  return { values, slugEdited };
}

function MovieForm({ initialValues, movieId }: { initialValues: MovieFormValues; movieId?: string }) {
  const [initialSnapshot] = useState(() => formSnapshot(initialValues));
  const [initialStatus] = useState(initialValues.status);
  const [formState, change] = useReducer(formReducer, { values: initialValues, slugEdited: movieId !== undefined });
  const { values } = formState;
  const [clientErrors, setClientErrors] = useState<MovieFormErrors>({});
  const [serverErrors, setServerErrors] = useState<MovieFormErrors>({});
  const errors = { ...clientErrors, ...serverErrors };
  const touchedCredits = useRef(new Set<string>());
  const [hasSubmitted, setHasSubmitted] = useState(false);
  const [generalErrors, setGeneralErrors] = useState<string[]>([]);
  const [genreDraft, setGenreDraft] = useState(false);
  const [summaryRevision, focusSummary] = useReducer((revision: number) => revision + 1, 0);
  const [forbidden, setForbidden] = useState(false);
  const [missing, setMissing] = useState(false);
  const summaryRef = useRef<HTMLDivElement>(null);
  const allowNavigation = useRef(false);
  const inFlight = useRef(false);
  const mounted = useRef(false);
  const apiClient = useApiClient();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const returnTo = getAdminMoviesReturnTo(useLocation().state);
  const dirty = formSnapshot(values) !== initialSnapshot || genreDraft;
  const mutation = useMutation({
    mutationFn: async (payload: UpsertMovieRequest) => {
      // Accepted writes must still update caches when the page unmounts (e.g. session revocation).
      const saved = movieId ? await updateMovie(apiClient, movieId, payload) : await createMovie(apiClient, payload);
      await queryClient.cancelQueries({ queryKey: movieKeys.detail(saved.id), exact: true });
      queryClient.setQueryData(movieKeys.detail(saved.id), saved);
      void queryClient.invalidateQueries({ queryKey: movieKeys.all });
      return saved;
    }
  });
  const pending = mutation.isPending;

  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  useEffect(() => {
    if (summaryRevision > 0) summaryRef.current?.focus();
  }, [summaryRevision]);

  const update = (patch: Partial<MovieFormValues>) => {
    const next = formReducer(formState, patch).values;
    for (const credit of next.people) {
      const old = values.people.find((row) => row.key === credit.key);
      if (old && (old.name !== credit.name || old.personId !== credit.personId || old.role !== credit.role)) touchedCredits.current.add(credit.key);
    }
    const keepUnchanged = (current: MovieFormErrors) => Object.fromEntries(Object.entries(current).filter(([key]) =>
      JSON.stringify(formFieldValue(values, key)) === JSON.stringify(formFieldValue(next, key))));
    change(patch);
    setClientErrors(keepUnchanged);
    setServerErrors(keepUnchanged);
  };

  const validateField = (field: string) => {
    const validation = validateMovieForm(values);
    setClientErrors((current) => ({
      ...Object.fromEntries(Object.entries(current).filter(([key]) => key !== field && !key.startsWith(`${field}[`))),
      ...Object.fromEntries(Object.entries(validation).filter(([key]) => {
        if (key !== field && !key.startsWith(`${field}[`)) return false;
        const row = /^people\[(\d+)\]/.exec(key);
        return hasSubmitted || !row || touchedCredits.current.has(values.people[Number(row[1])]?.key);
      }))
    }));
  };

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (inFlight.current) return;
    setHasSubmitted(true);
    const validation = validateMovieForm(values);
    if (genreDraft) validation.genres = 'Add or clear the genre you typed before saving.';
    setClientErrors(validation);
    setServerErrors({});
    setGeneralErrors([]);
    if (Object.keys(validation).length) { focusSummary(); return; }
    inFlight.current = true;
    try {
      await mutation.mutateAsync(toMoviePayload(values));
      if (!mounted.current) return;
      allowNavigation.current = true;
      navigate(returnTo, { replace: true, state: { message: movieId ? 'Changes saved' : 'Movie created' } });
    } catch (error) {
      if (!mounted.current) return;
      if (error instanceof ApiRequestError && error.status === 403) setForbidden(true);
      else if (error instanceof ApiRequestError && error.status === 404 && movieId) setMissing(true);
      else if (error instanceof AuthenticationSupersededError) setGeneralErrors(['Your session changed. Review the form and save again.']);
      else {
        const failure = mapMovieFormError(error, values);
        setServerErrors(failure.fields);
        setGeneralErrors(failure.general);
      }
      focusSummary();
    } finally {
      inFlight.current = false;
    }
  };

  if (forbidden) return <ForbiddenPage />;
  if (missing) return <NotFoundPage title="Movie not found" description="The movie could not be found." link={{ to: '/admin/movies', label: 'Back to movies' }} />;

  const field = (name: 'title' | 'slug' | 'description' | 'releaseYear' | 'maturityRating' | 'status', label: string, control?: ReactNode) => (
    <div>
      <label className="mb-2 block font-medium text-slate-200" htmlFor={`movie-${name}`}>{label}</label>
      {control ?? <input aria-describedby={errors[name] ? `movie-${name}-error` : undefined} aria-invalid={Boolean(errors[name])} className={inputClass} id={`movie-${name}`} name={name} onBlur={() => validateField(name)} onChange={(event) => update({ [name]: event.target.value })} type="text" inputMode={name === 'releaseYear' ? 'numeric' : undefined} value={values[name]} />}
      {errors[name] ? <p className="mt-1 text-sm text-rose-300" id={`movie-${name}-error`}>{errors[name]}</p> : null}
    </div>
  );

  return (
    <section className="max-w-3xl">
      <UnsavedMovieChanges allowNavigation={allowNavigation} dirty={dirty} pending={pending} />
      <h1 className="text-3xl font-semibold text-white">{movieId ? 'Edit movie' : 'New movie'}</h1>
      <form className="mt-6 grid gap-6" noValidate onSubmit={(event) => void submit(event)}>
        {hasSubmitted && (Object.keys(errors).length || generalErrors.length) ? (
          <div className="rounded-lg border border-rose-500 bg-rose-950/40 p-4" ref={summaryRef} role="alert" tabIndex={-1}>
            <h2 className="font-semibold">The movie could not be saved</h2>
            <ul className="mt-2 list-inside list-disc">
              {Object.entries(errors).map(([name, message]) => <li key={name}><a className="underline" href={`#${fieldId(name)}`} onClick={(event) => { event.preventDefault(); document.getElementById(fieldId(name))?.focus(); }}>{message}</a></li>)}
              {generalErrors.map((message, index) => <li key={index}>{message}</li>)}
            </ul>
          </div>
        ) : null}
        <fieldset className="grid gap-6 disabled:opacity-60" disabled={pending}>
          <legend className="mb-4 text-xl font-semibold">Basics</legend>
          {field('title', 'Title')}
          {field('slug', 'Slug')}
          {field('description', 'Description', <textarea aria-describedby={errors.description ? 'movie-description-error movie-description-count' : 'movie-description-count'} aria-invalid={Boolean(errors.description)} className={inputClass} id="movie-description" name="description" onBlur={() => validateField('description')} onChange={(event) => update({ description: event.target.value })} rows={6} value={values.description} />)}
          <p className="-mt-4 text-sm text-slate-400" id="movie-description-count">{[...trimCatalogText(values.description)].length} / 5000 characters</p>
          {field('releaseYear', 'Release year (optional)')}
        </fieldset>
        <fieldset className="grid gap-6 disabled:opacity-60" disabled={pending}>
          <legend className="mb-4 text-xl font-semibold">Classification</legend>
          {field('maturityRating', 'Maturity rating', <select aria-describedby={errors.maturityRating ? 'movie-maturityRating-error' : undefined} aria-invalid={Boolean(errors.maturityRating)} className={inputClass} id="movie-maturityRating" name="maturityRating" onBlur={() => validateField('maturityRating')} onChange={(event) => update({ maturityRating: event.target.value })} value={values.maturityRating}>
            <option value="">Not classified</option>{MATURITY_RATINGS.map((rating) => <option key={rating} value={rating}>{rating}</option>)}
            {values.maturityRating === 'UNKNOWN' ? <option value="UNKNOWN">Unknown — choose a classification</option> : null}
          </select>)}
          <div onBlur={(event) => { if (!event.currentTarget.contains(event.relatedTarget)) validateField('genres'); }}><GenreEditor disabled={pending} errors={errors} onChange={(genres) => update({ genres })} onDraftChange={setGenreDraft} onForbidden={() => setForbidden(true)} value={values.genres} /></div>
        </fieldset>
        <fieldset className="grid gap-6 disabled:opacity-60" disabled={pending}>
          <legend className="mb-4 text-xl font-semibold">Credits</legend>
          <div onBlur={(event) => { if (!event.currentTarget.contains(event.relatedTarget)) validateField('people'); }}><CreditsEditor disabled={pending} errors={errors} onChange={(people) => update({ people })} onForbidden={() => setForbidden(true)} value={values.people} /></div>
        </fieldset>
        <fieldset className="grid gap-4 disabled:opacity-60" disabled={pending}>
          <legend className="mb-4 text-xl font-semibold">Publishing</legend>
          {initialStatus === 'ARCHIVED' ? <p>This movie is archived. Choose Draft or Published to restore it.</p> : null}
          {field('status', 'Status', <select aria-describedby={errors.status ? 'movie-status-error' : undefined} aria-invalid={Boolean(errors.status)} className={inputClass} id="movie-status" name="status" onBlur={() => validateField('status')} onChange={(event) => update({ status: event.target.value })} value={values.status}>
            <option value="DRAFT">Draft</option><option value="PUBLISHED">Published</option>
            {initialStatus === 'ARCHIVED' ? <option value="ARCHIVED">Archived (current)</option> : null}
            {values.status === 'UNKNOWN' ? <option value="UNKNOWN">Unknown — choose a status</option> : null}
          </select>)}
        </fieldset>
        <div className="flex flex-wrap items-center gap-3">
          <button className={`${primaryButtonClass} disabled:cursor-wait disabled:opacity-60`} disabled={pending} type="submit">{pending ? 'Saving…' : movieId ? 'Save changes' : 'Create movie'}</button>
          {pending ? <span role="status">Saving movie…</span> : <Link className={secondaryButtonClass} to={returnTo}>Cancel</Link>}
        </div>
      </form>
    </section>
  );
}

const inputClass = 'w-full rounded-md border border-slate-600 bg-slate-950 px-3 py-2 text-white focus:outline-none focus:ring-2 focus:ring-sky-400';

function fieldId(field: string): string {
  if (field.startsWith('genres')) return 'movie-genres';
  const credit = /^people\[(\d+)\](?:\.(personId|name|role))?$/.exec(field);
  if (credit) return `movie-people-${credit[1]}-${credit[2] === 'role' ? 'role' : 'personId'}`;
  return `movie-${field}`;
}

function formSnapshot(values: MovieFormValues): string {
  return JSON.stringify({ ...values, people: values.people.map(({ key: _key, ...credit }) => credit) });
}

function formFieldValue(values: MovieFormValues, field: string): unknown {
  const genre = /^genres\[(\d+)\]$/.exec(field);
  if (genre) return values.genres[Number(genre[1])];
  const credit = /^people\[(\d+)\](?:\.(personId|name|role))?$/.exec(field);
  if (credit) {
    const row = values.people[Number(credit[1])];
    return credit[2] ? row?.[credit[2] as 'personId' | 'name' | 'role'] : row;
  }
  return values[field as keyof MovieFormValues];
}
