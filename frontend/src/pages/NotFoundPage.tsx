import { Link } from 'react-router-dom';

export function NotFoundPage() {
  return (
    <section className="mx-auto max-w-xl rounded-lg border border-slate-800 bg-slate-900 p-8 text-center">
      <p className="text-sm font-medium text-sky-300">404</p>
      <h1 className="mt-2 text-3xl font-semibold text-white">Page not found</h1>
      <p className="mt-4 text-slate-300">
        This address does not match any page.
      </p>
      <Link
        className="mt-6 inline-flex rounded-md bg-slate-100 px-4 py-2 text-sm font-semibold text-slate-950 hover:bg-white"
        to="/"
      >
        Return home
      </Link>
    </section>
  );
}
