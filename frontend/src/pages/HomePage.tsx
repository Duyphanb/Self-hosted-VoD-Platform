export function HomePage() {
  return (
    <section className="grid gap-6">
      <div className="max-w-3xl">
        <h1 className="text-3xl font-semibold tracking-normal text-white sm:text-4xl">
          Frontend scaffold is ready
        </h1>
        <p className="mt-4 text-base leading-7 text-slate-300">
          React, TypeScript, Vite, Tailwind CSS, and a routing placeholder are initialized.
          Auth, API integration, playback, and admin screens are deferred to later sprint issues.
        </p>
      </div>
      <div className="grid gap-3 rounded border border-slate-800 bg-slate-900 p-5 text-sm text-slate-300">
        <div className="font-medium text-slate-100">Current scope</div>
        <ul className="grid gap-2">
          <li>Vite dev server uses port 5173.</li>
          <li>TypeScript strict mode is enabled.</li>
          <li>Tailwind CSS is configured.</li>
        </ul>
      </div>
    </section>
  );
}
