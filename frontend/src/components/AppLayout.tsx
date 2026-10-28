import { useCallback, useRef, useState } from 'react';
import { Link, NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../features/auth/AuthContext';
import { FormExitGuardContext, type FormExitGuard } from './FormExitGuardContext';

export function AppLayout() {
  const { isAuthenticated, isInitializing, logout, user } = useAuth();
  const isAdmin = user?.roles.includes('ROLE_ADMIN') ?? false;
  const [isLoggingOut, setIsLoggingOut] = useState(false);
  const [logoutError, setLogoutError] = useState<string | null>(null);
  const [wasAuthenticated, setWasAuthenticated] = useState(isAuthenticated);
  const logoutInFlight = useRef(false);
  const formExitGuard = useRef<FormExitGuard | null>(null);
  const registerFormExitGuard = useCallback((guard: FormExitGuard) => {
    formExitGuard.current = guard;
    return () => { if (formExitGuard.current === guard) formExitGuard.current = null; };
  }, []);

  // Clear a stale sign-out warning as soon as a session appears, adjusting state during render
  // (https://react.dev/learn/you-might-not-need-an-effect) instead of an extra effect pass.
  if (isAuthenticated !== wasAuthenticated) {
    setWasAuthenticated(isAuthenticated);
    if (isAuthenticated) {
      setLogoutError(null);
    }
  }

  const signOut = async () => {
    if (logoutInFlight.current) {
      return;
    }
    logoutInFlight.current = true;
    try {
      if (formExitGuard.current && !await formExitGuard.current()) return;
      setIsLoggingOut(true);
      setLogoutError(null);
      await logout();
    } catch {
      setLogoutError('Signed out locally, but server token revocation could not be confirmed.');
    } finally {
      logoutInFlight.current = false;
      setIsLoggingOut(false);
    }
  };

  return (
    <FormExitGuardContext.Provider value={registerFormExitGuard}>
    <div className="min-h-screen bg-slate-950 text-slate-100">
      <header className="border-b border-slate-800 bg-slate-950/95">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-x-6 gap-y-3 px-6 py-4">
          <Link className="whitespace-nowrap text-base font-semibold tracking-normal text-white" to="/">
            VoD Platform
          </Link>
          <nav
            aria-busy={isInitializing}
            aria-label="Primary navigation"
            className="flex min-h-5 items-center gap-4 whitespace-nowrap text-sm"
          >
            {!isInitializing && (isAuthenticated || isLoggingOut) ? (
              <>
                {isAuthenticated ? (
                  <>
                    <NavLink className={navLinkClass} to="/movies">Movies</NavLink>
                    <NavLink className={navLinkClass} to="/account">Account</NavLink>
                    {isAdmin ? <NavLink className={navLinkClass} to="/admin/movies">Admin</NavLink> : null}
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
    </FormExitGuardContext.Provider>
  );
}

function navLinkClass({ isActive }: { isActive: boolean }) {
  return isActive ? 'font-medium text-sky-300' : 'text-slate-400 hover:text-slate-200';
}
