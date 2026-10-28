const ADMIN_MOVIES_PATH = '/admin/movies';

/** Only a local admin list URL may be carried back from an edit/create form. */
export function getAdminMoviesReturnTo(state: unknown): string {
  if (!state || typeof state !== 'object' || !('returnTo' in state) || typeof state.returnTo !== 'string') return ADMIN_MOVIES_PATH;
  return /^\/admin\/movies(?:\?[^#]*)?$/.test(state.returnTo) && !/\p{Cc}/u.test(state.returnTo)
    ? state.returnTo : ADMIN_MOVIES_PATH;
}
