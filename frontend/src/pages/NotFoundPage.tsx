import { Link } from 'react-router-dom';

interface NotFoundLink {
  to: string;
  label: string;
}

interface NotFoundPageProps {
  title?: string;
  description?: string;
  /** Where the visitor goes next; the home page by default. */
  link?: NotFoundLink;
}

const HOME_LINK: NotFoundLink = { to: '/', label: 'Return home' };

/** Whole-page not-found view with its own `h1`; a detail page can pass its own copy and link. */
export function NotFoundPage({
  title = 'Page not found',
  description = 'This address does not match any page.',
  link = HOME_LINK
}: NotFoundPageProps) {
  return (
    <section className="mx-auto max-w-xl rounded-lg border border-slate-800 bg-slate-900 p-8 text-center">
      <p className="text-sm font-medium text-sky-300">404</p>
      <h1 className="mt-2 text-3xl font-semibold text-white">{title}</h1>
      <p className="mt-4 text-slate-300">{description}</p>
      <Link
        className="mt-6 inline-flex rounded-md bg-slate-100 px-4 py-2 text-sm font-semibold text-slate-950 hover:bg-white"
        to={link.to}
      >
        {link.label}
      </Link>
    </section>
  );
}
