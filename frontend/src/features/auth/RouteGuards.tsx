import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { LoadingState } from '../../components/StateViews';
import { useAuth } from './AuthContext';

const ADMIN_ROLE = 'ROLE_ADMIN';

export function ProtectedRoute() {
  return <AuthRouteGuard requireAdmin={false} />;
}

export function AdminRoute() {
  return <AuthRouteGuard requireAdmin />;
}

/** Renders guest pages such as the landing page; a signed-in user goes to `signedInPath` instead. */
export function GuestRoute({ signedInPath }: { signedInPath: string }) {
  const { isAuthenticated, isInitializing } = useAuth();

  if (isInitializing) {
    return <LoadingState label="Checking authentication…" />;
  }
  if (isAuthenticated) {
    return <Navigate replace to={signedInPath} />;
  }
  return <Outlet />;
}

function AuthRouteGuard({ requireAdmin }: { requireAdmin: boolean }) {
  const { isAuthenticated, isInitializing, user } = useAuth();
  const location = useLocation();

  if (isInitializing) {
    return <LoadingState label="Checking authentication…" />;
  }

  if (!isAuthenticated) {
    return (
      <Navigate
        replace
        state={{ from: intendedPath(location.pathname, location.search, location.hash) }}
        to="/login"
      />
    );
  }

  if (requireAdmin && !user?.roles.includes(ADMIN_ROLE)) {
    return <Navigate replace to="/forbidden" />;
  }

  // A different user's session remounts the protected page: AuthProvider has cleared the query cache,
  // so the page fetches again as the new user and keeps nothing of the previous user's state.
  return <Outlet key={user?.id} />;
}

function intendedPath(pathname: string, search: string, hash: string): string {
  return `${pathname}${search}${hash}`;
}
