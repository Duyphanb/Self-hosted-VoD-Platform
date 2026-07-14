import { Outlet } from 'react-router-dom';

export function AppLayout() {
  return (
    <div className="min-h-screen bg-slate-950 text-slate-100">
      <header className="border-b border-slate-800 bg-slate-950/95">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-6 py-4">
          <div className="text-base font-semibold tracking-normal">VoD Platform</div>
          <nav aria-label="Primary navigation" className="text-sm text-slate-400">
            <span>Routing placeholder</span>
          </nav>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-6 py-12">
        <Outlet />
      </main>
    </div>
  );
}
