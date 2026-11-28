import { Navigate, useLocation, useParams } from 'react-router-dom';
import { ErrorState, LoadingState } from '../../components/StateViews';
import { ApiRequestError } from '../../lib/api/client';
import { isCanonicalUuid } from '../../lib/uuid';
import { NotFoundPage } from '../../pages/NotFoundPage';
import { useMovieDetail } from './movieQueries';

export function LegacyTitleRedirect() {
  const { pathname, search, hash, state } = useLocation();
  const next = pathname.replace(/^((?:\/admin)?)\/movies(?=\/|$)/i,
    (_match, admin: string) => `${admin.toLowerCase()}/titles`);
  return <Navigate replace state={state} to={{ pathname: next, search, hash }} />;
}

export function LegacyWatchRedirect() {
  const titleId = (useParams().movieId ?? '').toLowerCase();
  const { search, hash } = useLocation();
  const title = useMovieDetail(titleId, { refetchOnMount: 'always' });
  const error = title.error;
  if (!isCanonicalUuid(titleId) || (error instanceof ApiRequestError && [400, 404].includes(error.status))) {
    return <NotFoundPage />;
  }
  if (title.isError) {
    return <ErrorState title="Title could not be loaded" error={error} onRetry={() => void title.refetch()} />;
  }
  if (!title.data || !title.isFetchedAfterMount) {
    return <LoadingState label="Loading title…" />;
  }
  const { episode, contentType } = title.data;
  const pathname = contentType === 'MOVIE' && episode ? `/watch/${episode.id}` : `/titles/${titleId}`;
  return <Navigate replace to={{ pathname, search, hash }} />;
}
