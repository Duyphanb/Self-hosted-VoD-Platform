import { useEffect, useReducer, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { primaryButtonClass, secondaryButtonClass } from '../../../components/buttonStyles';
import { ErrorState, LoadingState } from '../../../components/StateViews';
import { ApiRequestError, AuthenticationSupersededError } from '../../../lib/api/client';
import { isCanonicalUuid } from '../../../lib/uuid';
import { slugify } from '../../../lib/slug';
import { ForbiddenPage } from '../../../pages/AccessPages';
import { NotFoundPage } from '../../../pages/NotFoundPage';
import { useApiClient } from '../../auth/AuthContext';
import { createMovie, updateMovie } from '../adminMoviesApi';
import { CreditsEditor } from '../components/CreditsEditor';
import { GenreEditor } from '../components/GenreEditor';
import { UnsavedMovieChanges } from '../components/UnsavedMovieChanges';
import { createEmptyMovieForm, mapMovieFormError, movieDetailToForm, toMoviePayload, validateMovieForm,
  type MovieFormErrors, type MovieFormValues } from '../movieFormModel';
import { MATURITY_RATINGS, movieKeys } from '../moviesApi';
import { useMovieDetail } from '../movieQueries';

export function AdminMovieFormPage() {
  const movieId = useParams().movieId;
  return movieId === undefined
    ? <MovieForm initialValues={createEmptyMovieForm()} key="new" />
    : <EditMovieRequest key={movieId.toLowerCase()} movieId={movieId.toLowerCase()} />;
}

function EditMovieRequest({ movieId }: { movieId: string }) {
  const { data, error, isError, isFetching, refetch } = useMovieDetail(movieId);
  const failed = isError && !(error instanceof AuthenticationSupersededError);
  if (!isCanonicalUuid(movieId) || (failed && error instanceof ApiRequestError && [400, 404].includes(error.status))) {
    return <NotFoundPage title="Movie not found" description="The movie could not be found." link={{ to: '/admin/movies', label: 'Back to movies' }} />;
  }
  if (failed && error instanceof ApiRequestError && error.status === 403) return <ForbiddenPage />;
  if (failed && !data) return <ErrorState error={error} headingLevel={1} isRetrying={isFetching} onRetry={() => void refetch()} title="Movie could not be loaded" />;
  if (!data) return <><h1 className="text-3xl font-semibold">Edit movie</h1><LoadingState label="Loading movie…" /></>;
  // Initial state is captured once: background refetches must not overwrite an unsaved draft.
  return (
    <div className="grid gap-6">
      {failed ? <ErrorState error={error} isRetrying={isFetching} onRetry={() => void refetch()} title="Movie could not be refreshed; your changes are kept" /> : null}
      <MovieForm initialValues={movieDetailToForm(data)} movieId={movieId} />
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
  const [{ values }, change] = useReducer(formReducer, { values: initialValues, slugEdited: movieId !== undefined });
  const [errors, setErrors] = useState<MovieFormErrors>({});
  const [generalErrors, setGeneralErrors] = useState<string[]>([]);
  const [genreDraft, setGenreDraft] = useState(false);
  const [pending, setPending] = useState(false);
  const [summaryRevision, focusSummary] = useReducer((revision: number) => revision + 1, 0);
  const [forbidden, setForbidden] = useState(false);
  const [missing, setMissing] = useState(false);
  const summaryRef = useRef<HTMLDivElement>(null);
  const allowNavigation = useRef(false);
  const inFlight = useRef(false);
  const requestController = useRef<AbortController | null>(null);
  const apiClient = useApiClient();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const dirty = formSnapshot(values) !== initialSnapshot || genreDraft;

  useEffect(() => () => requestController.current?.abort(), []);
  useEffect(() => {
    if (summaryRevision > 0) summaryRef.current?.focus();
  }, [summaryRevision]);

  const update = (patch: Partial<MovieFormValues>) => {
    change(patch);
    const fields = Object.keys(patch);
    setErrors((current) => Object.fromEntries(Object.entries(current).filter(([key]) => !fields.some((field) => key === field || key.startsWith(`${field}[`)))));
  };

  const validateField = (field: string) => {
    const validation = validateMovieForm(values);
    setErrors((current) => ({
      ...Object.fromEntries(Object.entries(current).filter(([key]) => key !== field && !key.startsWith(`${field}[`))),
      ...Object.fromEntries(Object.entries(validation).filter(([key]) => key === field || key.startsWith(`${field}[`)))
    }));
  };

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (inFlight.current) return;
    const validation = validateMovieForm(values);
    if (genreDraft) validation.genres = 'Add or clear the genre you typed before saving.';
    setErrors(validation);
    setGeneralErrors([]);
    if (Object.keys(validation).length) { focusSummary(); return; }
    inFlight.current = true;
    setPending(true);
    const controller = new AbortController();
    requestController.current = controller;
    try {
      const payload = toMoviePayload(values);
      if (movieId) await updateMovie(apiClient, movieId, payload, controller.signal);
      else await createMovie(apiClient, payload, controller.signal);
      if (controller.signal.aborted) return;
      void queryClient.invalidateQueries({ queryKey: movieKeys.all });
      allowNavigation.current = true;
      navigate('/admin/movies', { state: { message: movieId ? 'Changes saved' : 'Movie created' } });
    } catch (error) {
      if (controller.signal.aborted) return;
      if (error instanceof ApiRequestError && error.status === 403) setForbidden(true);
      else if (error instanceof ApiRequestError && error.status === 404 && movieId) setMissing(true);
      else if (error instanceof AuthenticationSupersededError) setGeneralErrors(['Your session changed. Review the form and save again.']);
      else {
        const failure = mapMovieFormError(error, values);
        setErrors(failure.fields);
        setGeneralErrors(failure.general);
      }
      focusSummary();
    } finally {
      if (!controller.signal.aborted) { inFlight.current = false; setPending(false); }
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
      <UnsavedMovieChanges allowNavigation={allowNavigation} dirty={dirty} />
      <h1 className="text-3xl font-semibold text-white">{movieId ? 'Edit movie' : 'New movie'}</h1>
      <form className="mt-6 grid gap-6" noValidate onSubmit={(event) => void submit(event)}>
        {Object.keys(errors).length || generalErrors.length ? (
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
          <p className="-mt-4 text-sm text-slate-400" id="movie-description-count">{[...values.description].length} / 5000 characters</p>
          {field('releaseYear', 'Release year (optional)')}
        </fieldset>
        <fieldset className="grid gap-6 disabled:opacity-60" disabled={pending}>
          <legend className="mb-4 text-xl font-semibold">Classification</legend>
          {field('maturityRating', 'Maturity rating', <select aria-describedby={errors.maturityRating ? 'movie-maturityRating-error' : undefined} aria-invalid={Boolean(errors.maturityRating)} className={inputClass} id="movie-maturityRating" name="maturityRating" onBlur={() => validateField('maturityRating')} onChange={(event) => update({ maturityRating: event.target.value })} value={values.maturityRating}>
            <option value="">Not classified</option>{MATURITY_RATINGS.map((rating) => <option key={rating} value={rating}>{rating}</option>)}
            {values.maturityRating === 'UNKNOWN' ? <option value="UNKNOWN">Unknown — choose a classification</option> : null}
          </select>)}
          <div onBlur={() => validateField('genres')}><GenreEditor disabled={pending} errors={errors} onChange={(genres) => update({ genres })} onDraftChange={setGenreDraft} onForbidden={() => setForbidden(true)} value={values.genres} /></div>
        </fieldset>
        <fieldset className="grid gap-6 disabled:opacity-60" disabled={pending}>
          <legend className="mb-4 text-xl font-semibold">Credits</legend>
          <div onBlur={() => validateField('people')}><CreditsEditor disabled={pending} errors={errors} onChange={(people) => update({ people })} onForbidden={() => setForbidden(true)} value={values.people} /></div>
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
          {pending ? <span role="status">Saving movie…</span> : <Link className={secondaryButtonClass} to="/admin/movies">Cancel</Link>}
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
