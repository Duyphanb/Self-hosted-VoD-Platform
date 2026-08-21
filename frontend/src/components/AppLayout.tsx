import { Link, NavLink, Outlet } from 'react-router-dom';

export function AppLayout() {
  return (
    <div className="min-h-screen bg-slate-950 text-slate-100">
      <header className="border-b border-slate-800 bg-slate-950/95">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-6 py-4">
          <Link className="text-base font-semibold tracking-normal text-white" to="/">
            VoD Platform
          </Link>
          <nav aria-label="Primary navigation" className="flex items-center gap-4 text-sm">
            <NavLink className={navLinkClass} to="/login">Sign in</NavLink>
            <NavLink className={navLinkClass} to="/register">Register</NavLink>
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
