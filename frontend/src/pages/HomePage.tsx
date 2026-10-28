import { Link } from 'react-router-dom';
import { primaryButtonClass, secondaryButtonClass } from '../components/buttonStyles';

/** Landing page for visitors who are not signed in; GuestRoute sends a signed-in user to /movies. */
export function HomePage() {
  return (
    <section className="grid gap-6">
      <div className="max-w-3xl">
        <h1 className="text-3xl font-semibold tracking-normal text-white sm:text-4xl">
          Your self-hosted video library
        </h1>
        <p className="mt-4 text-base leading-7 text-slate-300">
          Sign in to browse the movie library. New here? Register for an account first.
        </p>
      </div>
      <div className="flex flex-wrap gap-3">
        <Link className={primaryButtonClass} to="/login">Sign in</Link>
        <Link className={secondaryButtonClass} to="/register">Register</Link>
      </div>
    </section>
  );
}
