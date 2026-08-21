export function HomePage() {
  return (
    <section className="grid gap-6">
      <div className="max-w-3xl">
        <h1 className="text-3xl font-semibold tracking-normal text-white sm:text-4xl">
          Your self-hosted video library
        </h1>
        <p className="mt-4 text-base leading-7 text-slate-300">
          Account registration, sign-in, and shared authentication state are ready.
          Protected library, playback, and administration screens arrive in later sprint issues.
        </p>
      </div>
      <div className="grid gap-3 rounded border border-slate-800 bg-slate-900 p-5 text-sm text-slate-300">
        <div className="font-medium text-slate-100">Current auth scope</div>
        <ul className="grid gap-2">
          <li>Create an account with your email and display name.</li>
          <li>Sign in securely and return to your intended app route.</li>
          <li>Authentication errors stay explicit without exposing credentials.</li>
        </ul>
      </div>
    </section>
  );
}
