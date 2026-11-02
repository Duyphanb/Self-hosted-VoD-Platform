import { Link, useLocation, useParams } from 'react-router-dom';
import { ErrorState, LoadingState } from '../../../components/StateViews';
import { primaryButtonClass, secondaryButtonClass } from '../../../components/buttonStyles';
import { ApiRequestError } from '../../../lib/api/client';
import { isCanonicalUuid } from '../../../lib/uuid';
import { ForbiddenPage } from '../../../pages/AccessPages';
import { NotFoundPage } from '../../../pages/NotFoundPage';
import { getAdminMoviesReturnTo } from '../adminMovieNavigation';
import { UploadExitGuard } from '../components/UploadExitGuard';
import { useMovieDetail } from '../movieQueries';
import { MAX_UPLOAD_SIZE_MB } from '../uploadLimits';
import { useVideoUpload } from '../useVideoUpload';
import { VIDEO_FILE_ACCEPT } from '../videoUpload';

export function AdminMovieUploadPage() {
  const movieId = (useParams().movieId ?? '').toLowerCase();
  return <MovieUploadRequest key={movieId} movieId={movieId} />;
}

function MovieUploadRequest({ movieId }: { movieId: string }) {
  const movie = useMovieDetail(movieId);
  const returnTo = getAdminMoviesReturnTo(useLocation().state);
  if (!isCanonicalUuid(movieId) || (movie.isError && movie.error instanceof ApiRequestError && [400, 404].includes(movie.error.status))) {
    return <NotFoundPage title="Movie not found" description="The movie could not be found." link={{ to: returnTo, label: 'Back to movies' }} />;
  }
  if (movie.isError && movie.error instanceof ApiRequestError && movie.error.status === 403) return <ForbiddenPage />;
  if (!movie.data && movie.isError) return <ErrorState title="Movie could not be loaded" error={movie.error} headingLevel={1} onRetry={() => void movie.refetch()} isRetrying={movie.isFetching} />;
  if (!movie.data) return <><h1>Upload video</h1><LoadingState label="Loading movie…" /></>;
  return <MovieUploadForm movieId={movieId} title={movie.data.title} returnTo={returnTo} />;
}

function MovieUploadForm({ movieId, title, returnTo }: { movieId: string; title: string; returnTo: string }) {
  const { phase, file, validation, percent, outcome, pending, canUpload, selectFile, submit, cancel,
    statusRef, outcomeRef, fileInputRef } = useVideoUpload(movieId);
  const guard = <UploadExitGuard pending={pending} sending={phase === 'sending'} cancel={cancel}
    outcomeRef={outcomeRef} statusRef={statusRef} />;
  if (outcome?.kind === 'forbidden' || outcome?.kind === 'notFound') return <>{guard}
    <div ref={outcomeRef} tabIndex={-1}>
      {outcome.kind === 'forbidden' ? <ForbiddenPage />
        : <NotFoundPage title="Movie not found" description="The movie could not be found." link={{ to: returnTo, label: 'Back to movies' }} />}
    </div>
  </>;
  const guidance = outcome && ['accepted', 'unknown', 'cancelled'].includes(outcome.kind);
  return <section className="max-w-2xl">
    {guard}
    <h1 className="text-3xl font-semibold">Upload video</h1>
    <p className="mt-2">Movie: {title}</p>
    <Link className={`${secondaryButtonClass} mt-4`} to={returnTo}>Back to movies</Link>
    <form className="mt-6 grid gap-4" onSubmit={(event) => { event.preventDefault(); void submit(); }}>
      <label className="font-medium" htmlFor="movie-video-file">Video file</label>
      <input accept={VIDEO_FILE_ACCEPT} aria-describedby={validation ? 'movie-video-help movie-video-error' : 'movie-video-help'}
        aria-invalid={Boolean(validation)} disabled={pending} id="movie-video-file" onChange={(event) => selectFile(event.target.files?.[0] ?? null)}
        ref={fileInputRef} type="file" />
      <p id="movie-video-help">Choose MP4, MOV, MKV or WebM. Limit: {MAX_UPLOAD_SIZE_MB} MiB including upload overhead.</p>
      {validation ? <p id="movie-video-error" role="alert">{validation}</p> : null}
      {!file ? <p>No file selected.</p> : null}
      <div className="flex gap-3">
        <button className={primaryButtonClass} disabled={!canUpload} type="submit">Upload</button>
        {phase === 'sending' ? <button id="cancel-video-upload" className={secondaryButtonClass} onClick={cancel} type="button">Cancel upload</button> : null}
      </div>
      <div aria-label="Upload status" aria-live="polite" ref={statusRef} role="status" tabIndex={-1}>
        {phase === 'checking' ? 'Checking movie…' : phase === 'saving' ? 'Saving to storage…'
          : phase === 'sending' ? <>
            <label className="sr-only" htmlFor="video-upload-progress">Upload progress</label>
            <progress id="video-upload-progress" max={100} value={percent ?? undefined} />
            <span>{percent === null ? 'Sending file…' : `${percent}% sent`}</span>
          </> : null}
      </div>
    </form>
    {outcome ? <div aria-labelledby="upload-outcome-heading" className="mt-6 rounded border border-slate-600 p-4"
      ref={outcomeRef} role="region" tabIndex={-1}>
      <h2 className="text-xl font-semibold" id="upload-outcome-heading">{outcome.message}</h2>
      {outcome.serverMessage ? <p className="mt-2">{outcome.serverMessage}</p> : null}
      {outcome.requestId ? <p className="mt-2">Request ID: <span className="font-mono">{outcome.requestId}</span></p> : null}
      {guidance ? <div className="mt-3">
        <p>A deliberate re-upload is safe: existing video assets are never changed.</p>
        <p>No automatic re-upload is performed. Check the result before deliberately selecting Upload again.</p>
        <p>If the previous upload is still being processed, a re-upload returns 409 because an upload is already in progress.</p>
        <p>If it has finished, a re-upload creates a newer asset with extra storage cost. Manual purge is planned.</p>
        {outcome.kind === 'cancelled' ? <p>Cancel does not confirm that work on the server was rolled back.</p> : null}
      </div> : null}
    </div> : null}
  </section>;
}
