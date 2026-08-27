import { Link, NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../features/auth/AuthContext';

export function AppLayout() {
  const { isAuthenticated, isInitializing, user } = useAuth();
  const isAdmin = user?.roles.includes('ROLE_ADMIN') ?? false;

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
            {!isInitializing && isAuthenticated ? (
              <>
                <NavLink className={navLinkClass} to="/account">Account</NavLink>
                {isAdmin ? <NavLink className={navLinkClass} to="/admin">Admin</NavLink> : null}
              </>
            ) : null}
            {!isInitializing && !isAuthenticated ? (
              <>
                <NavLink className={navLinkClass} to="/login">Sign in</NavLink>
                <NavLink className={navLinkClass} to="/register">Register</NavLink>
              </>
            ) : null}
          </nav>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-6 py-12">
        <Outlet />
      </main>
    </div>
  );
}

function navLinkClass({ isActive }: { isActive: boolean }) {
  return isActive ? 'font-medium text-sky-300' : 'text-slate-400 hover:text-slate-200';
}
