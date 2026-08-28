import { Link } from 'react-router-dom';
import { useAuth } from '../features/auth/AuthContext';

export function AccountPage() {
  const { user } = useAuth();
  return (
    <section className="max-w-2xl rounded-lg border border-slate-800 bg-slate-900 p-6">
      <p className="text-sm font-medium text-sky-300">Protected route</p>
      <h1 className="mt-2 text-3xl font-semibold text-white">Account</h1>
      <p className="mt-4 text-slate-300">
        Signed in as {user?.displayName ?? 'authenticated user'}.
      </p>
    </section>
  );
}

export function AdminPage() {
  return (
    <section className="max-w-2xl rounded-lg border border-slate-800 bg-slate-900 p-6">
      <p className="text-sm font-medium text-amber-300">Admin route</p>
      <h1 className="mt-2 text-3xl font-semibold text-white">Administration</h1>
      <p className="mt-4 text-slate-300">
        This client session includes ROLE_ADMIN. Every admin API still enforces authorization on the backend.
      </p>
    </section>
  );
}

export function ForbiddenPage() {
  return (
    <section className="mx-auto max-w-xl rounded-lg border border-amber-700 bg-amber-950/30 p-8 text-center">
      <p className="text-sm font-medium text-amber-300">403-style access guard</p>
      <h1 className="mt-2 text-3xl font-semibold text-white">Access forbidden</h1>
      <p className="mt-4 text-slate-300">
        This area requires an administrator role.
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
