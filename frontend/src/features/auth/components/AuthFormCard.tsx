import type { InputHTMLAttributes, PropsWithChildren } from 'react';
import { Link } from 'react-router-dom';

interface AuthFormCardProps extends PropsWithChildren {
  title: string;
  description: string;
  alternateText: string;
  alternateLinkText: string;
  alternateTo: string;
}

export function AuthFormCard({
  title,
  description,
  alternateText,
  alternateLinkText,
  alternateTo,
  children
}: AuthFormCardProps) {
  return (
    <section className="mx-auto w-full max-w-md rounded-lg border border-slate-800 bg-slate-900 p-6 shadow-xl shadow-black/20 sm:p-8">
      <h1 className="text-2xl font-semibold text-white">{title}</h1>
      <p className="mt-2 text-sm leading-6 text-slate-300">{description}</p>
      <div className="mt-6">{children}</div>
      <p className="mt-6 text-center text-sm text-slate-400">
        {alternateText}{' '}
        <Link className="font-medium text-sky-400 hover:text-sky-300" to={alternateTo}>
          {alternateLinkText}
        </Link>
      </p>
    </section>
  );
}

interface AuthFieldProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string;
  name: string;
  error?: string;
}

export function AuthField({ label, name, error, className, ...inputProps }: AuthFieldProps) {
  const inputId = `auth-${name}`;
  const errorId = `${inputId}-error`;
  return (
    <div>
      <label className="block text-sm font-medium text-slate-200" htmlFor={inputId}>
        {label}
      </label>
      <input
        {...inputProps}
        aria-describedby={error ? errorId : undefined}
        aria-invalid={error ? 'true' : 'false'}
        className={`mt-2 block w-full rounded-md border bg-slate-950 px-3 py-2 text-sm text-white outline-none transition placeholder:text-slate-600 focus:ring-2 ${
          error
            ? 'border-rose-500 focus:border-rose-400 focus:ring-rose-500/30'
            : 'border-slate-700 focus:border-sky-500 focus:ring-sky-500/30'
        } ${className ?? ''}`}
        id={inputId}
        name={name}
      />
      {error ? (
        <p className="mt-2 text-sm text-rose-300" id={errorId} role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}

interface SubmitButtonProps {
  idleLabel: string;
  loadingLabel: string;
  loadingStatusLabel: string;
  loading: boolean;
}

export function SubmitButton({
  idleLabel,
  loadingLabel,
  loadingStatusLabel,
  loading
}: SubmitButtonProps) {
  return (
    <>
      <button
        className="flex w-full items-center justify-center gap-2 rounded-md bg-sky-500 px-4 py-2.5 text-sm font-semibold text-slate-950 transition hover:bg-sky-400 disabled:cursor-not-allowed disabled:bg-sky-800 disabled:text-slate-300"
        disabled={loading}
        type="submit"
      >
        {loading ? (
          <>
            <svg
              aria-hidden="true"
              className="h-4 w-4 animate-spin"
              fill="none"
              viewBox="0 0 24 24"
            >
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
              <path className="opacity-75" d="M4 12a8 8 0 018-8" stroke="currentColor" strokeLinecap="round" strokeWidth="4" />
            </svg>
            {loadingLabel}
          </>
        ) : idleLabel}
      </button>
      <span aria-live="polite" className="sr-only" role="status">
        {loading ? loadingStatusLabel : ''}
      </span>
    </>
  );
}
