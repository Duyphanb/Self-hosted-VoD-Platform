import { useEffect, useRef, useState } from 'react';
import { Link, NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../features/auth/AuthContext';

export function AppLayout() {
  const { isAuthenticated, isInitializing, logout, user } = useAuth();
  const isAdmin = user?.roles.includes('ROLE_ADMIN') ?? false;
  const [isLoggingOut, setIsLoggingOut] = useState(false);
  const [logoutError, setLogoutError] = useState<string | null>(null);
  const logoutInFlight = useRef(false);

  useEffect(() => {
    if (isAuthenticated) {
      setLogoutError(null);
    }
  }, [isAuthenticated]);

  const signOut = async () => {
    if (logoutInFlight.current) {
      return;
    }
    logoutInFlight.current = true;
    setIsLoggingOut(true);
    setLogoutError(null);
    try {
      await logout();
    } catch {
      setLogoutError('Signed out locally, but server token revocation could not be confirmed.');
    } finally {
      logoutInFlight.current = false;
      setIsLoggingOut(false);
    }
  };

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100">
      <header className="border-b border-slate-800 bg-slate-950/95">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-6 py-4">
          <Link className="text-base font-semibold tracking-normal text-white" to="/">
            VoD Platform
          </Link>
          <nav
            aria-busy={isInitializing}
            aria-label="Primary navigation"
            className="flex min-h-5 items-center gap-4 text-sm"
          >
            {!isInitializing && (isAuthenticated || isLoggingOut) ? (
              <>
                {isAuthenticated ? (
                  <>
                    <NavLink className={navLinkClass} to="/account">Account</NavLink>
                    {isAdmin ? <NavLink className={navLinkClass} to="/admin">Admin</NavLink> : null}
                  </>
                ) : null}
                <button
                  aria-live="polite"
                  className={isLoggingOut
                    ? 'cursor-wait text-slate-500'
                    : 'text-slate-400 hover:text-slate-200'}
                  disabled={isLoggingOut}
                  onClick={() => void signOut()}
                  type="button"
                >
                  {isLoggingOut ? 'Signing out…' : 'Sign out'}
                </button>
              </>
            ) : null}
            {!isInitializing && !isAuthenticated && !isLoggingOut ? (
              <>
                <NavLink className={navLinkClass} to="/login">Sign in</NavLink>
                <NavLink className={navLinkClass} to="/register">Register</NavLink>
              </>
            ) : null}
          </nav>
        </div>
      </header>
      {logoutError ? (
        <div className="mx-auto mt-4 max-w-6xl px-6">
          <p
            className="rounded-md border border-amber-600/60 bg-amber-950/50 p-3 text-sm text-amber-200"
            role="alert"
          >
            {logoutError}
          </p>
        </div>
      ) : null}
      <main className="mx-auto max-w-6xl px-6 py-12">
        <Outlet />
      </main>
    </div>
  );
}

function navLinkClass({ isActive }: { isActive: boolean }) {
  return isActive ? 'font-medium text-sky-300' : 'text-slate-400 hover:text-slate-200';
}
