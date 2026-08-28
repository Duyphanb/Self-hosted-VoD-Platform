import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from './AuthContext';

const ADMIN_ROLE = 'ROLE_ADMIN';

export function ProtectedRoute() {
  return <AuthRouteGuard requireAdmin={false} />;
}

export function AdminRoute() {
  return <AuthRouteGuard requireAdmin />;
}

function AuthRouteGuard({ requireAdmin }: { requireAdmin: boolean }) {
  const { isAuthenticated, isInitializing, user } = useAuth();
  const location = useLocation();

  if (isInitializing) {
    return (
      <div className="flex min-h-48 items-center justify-center" role="status">
        <span className="inline-flex items-center gap-3 text-sm text-slate-300">
          <span
            aria-hidden="true"
            className="h-4 w-4 animate-spin rounded-full border-2 border-slate-600 border-t-sky-400"
          />
          Checking authentication…
        </span>
      </div>
    );
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

  return <Outlet />;
}

function intendedPath(pathname: string, search: string, hash: string): string {
  return `${pathname}${search}${hash}`;
}
