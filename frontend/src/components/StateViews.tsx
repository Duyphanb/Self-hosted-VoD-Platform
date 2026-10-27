import type { PropsWithChildren, ReactNode } from 'react';
import { errorDetails } from '../lib/api/errorDetails';
import { secondaryButtonClass } from './buttonStyles';

interface LoadingStateProps {
  label: string;
  /** Skeleton of the content being loaded; a spinner is shown without one. */
  children?: ReactNode;
}

/** Announces `label` as a status; the skeleton itself is hidden from assistive technology. */
export function LoadingState({ label, children }: LoadingStateProps) {
  if (!children) {
    return (
      <div className="flex min-h-48 items-center justify-center" role="status">
        <span className="inline-flex items-center gap-3 text-sm text-slate-300">
          <span
            aria-hidden="true"
            className="h-4 w-4 animate-spin rounded-full border-2 border-slate-600 border-t-sky-400 motion-reduce:animate-none"
          />
          {label}
        </span>
      </div>
    );
  }
  return (
    <div role="status">
      <span className="sr-only">{label}</span>
      <div aria-hidden="true">{children}</div>
    </div>
  );
}

interface EmptyStateProps extends PropsWithChildren {
  title: string;
  description?: string;
}

/** Message for a result with nothing to show; `children` holds the follow-up actions. */
export function EmptyState({ title, description, children }: EmptyStateProps) {
  return (
    <div className="rounded-lg border border-dashed border-slate-700 bg-slate-900/60 px-6 py-12 text-center">
      <h2 className="text-lg font-semibold text-white">{title}</h2>
      {description ? <p className="mt-2 text-sm text-slate-300">{description}</p> : null}
      {children ? <div className="mt-6 flex flex-wrap justify-center gap-3">{children}</div> : null}
    </div>
  );
}

interface ErrorStateProps {
  title: string;
  error: unknown;
  onRetry?: () => void;
  isRetrying?: boolean;
}

/** Failed request: the server message, its request id when present, and an optional Retry. */
export function ErrorState({ title, error, onRetry, isRetrying = false }: ErrorStateProps) {
  const { message, requestId } = errorDetails(error);
  return (
    <div className="rounded-lg border border-rose-500/50 bg-rose-500/10 p-6" role="alert">
      <h2 className="text-lg font-semibold text-rose-100">{title}</h2>
      <p className="mt-2 text-sm text-rose-200">{message}</p>
      {requestId ? (
        <p className="mt-2 text-xs text-rose-200">
          Request ID: <span className="break-all font-mono">{requestId}</span>
        </p>
      ) : null}
      {onRetry ? (
        <button
          className={`${secondaryButtonClass} mt-4`}
          disabled={isRetrying}
          onClick={onRetry}
          type="button"
        >
          {isRetrying ? 'Retrying…' : 'Retry'}
        </button>
      ) : null}
    </div>
  );
}
