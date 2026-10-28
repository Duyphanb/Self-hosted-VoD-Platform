import { useCallback, useContext, useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react';
import { useBlocker } from 'react-router-dom';
import { FormExitGuardContext } from '../../../components/FormExitGuardContext';
import { useModalFocus } from '../../../components/useModalFocus';

/** Navigation may discard a draft, but cannot cancel a mutation already sent to the server. */
export function UnsavedMovieChanges({ dirty, pending, allowNavigation }: {
  dirty: boolean; pending: boolean; allowNavigation: RefObject<boolean>;
}) {
  const registerExitGuard = useContext(FormExitGuardContext);
  const latest = useRef({ dirty, pending });
  const signOutResolver = useRef<((proceed: boolean) => void) | null>(null);
  const [signOutRequested, setSignOutRequested] = useState(false);
  useLayoutEffect(() => { latest.current = { dirty, pending }; }, [dirty, pending]);

  const confirmSignOut = useCallback(() => {
    if (allowNavigation.current || (!latest.current.dirty && !latest.current.pending)) return Promise.resolve(true);
    return new Promise<boolean>((resolve) => {
      signOutResolver.current = resolve;
      setSignOutRequested(true);
    });
  }, [allowNavigation]);
  useEffect(() => registerExitGuard?.(confirmSignOut), [registerExitGuard, confirmSignOut]);
  useEffect(() => () => { signOutResolver.current?.(false); }, []);

  const blocker = useBlocker(({ currentLocation, nextLocation }) => (dirty || pending) && !allowNavigation.current
    && currentLocation.pathname !== nextLocation.pathname
    // Automatic expiry/revocation redirects still take effect immediately.
    && nextLocation.pathname !== '/login' && nextLocation.pathname !== '/forbidden');

  useEffect(() => {
    if (!dirty && !pending) return;
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (allowNavigation.current) return;
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, [dirty, pending, allowNavigation]);

  const stay = () => {
    if (blocker.state === 'blocked') blocker.reset();
    signOutResolver.current?.(false);
    signOutResolver.current = null;
    setSignOutRequested(false);
  };
  const discard = () => {
    if (pending) return;
    if (signOutRequested) {
      signOutResolver.current?.(true);
      signOutResolver.current = null;
      setSignOutRequested(false);
    } else if (blocker.state === 'blocked') blocker.proceed();
  };

  return blocker.state === 'blocked' || signOutRequested
    ? <ExitDialog onDiscard={discard} onStay={stay} pending={pending} />
    : null;
}

function ExitDialog({ onDiscard, onStay, pending }: { onDiscard: () => void; onStay: () => void; pending: boolean }) {
  const dialogRef = useRef<HTMLDivElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  useModalFocus({ dialogRef, initialFocusRef: cancelRef, onEscape: onStay });

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-6" onMouseDown={(event) => {
      if (event.target === event.currentTarget) event.preventDefault();
    }}>
      <div
        aria-describedby="discard-movie-description"
        aria-labelledby="discard-movie-heading"
        aria-modal="true"
        className="w-full max-w-md rounded-lg border border-slate-700 bg-slate-900 p-6"
        ref={dialogRef}
        role="dialog"
        tabIndex={-1}
      >
        <h2 className="text-lg font-semibold text-white" id="discard-movie-heading">{pending ? 'Saving movie…' : 'Discard changes?'}</h2>
        <p className="mt-3 text-slate-300" id="discard-movie-description">{pending ? 'The save is already in progress. Please wait for the result before leaving.' : 'Your unsaved movie changes will be lost.'}</p>
        <div className="mt-6 flex flex-wrap gap-3">
          <button className="rounded-md border border-slate-600 px-4 py-2" onClick={onStay} ref={cancelRef} type="button">{pending ? 'Stay on page' : 'Keep editing'}</button>
          {!pending ? <button className="rounded-md bg-rose-700 px-4 py-2 font-semibold text-white" onClick={onDiscard} type="button">Discard changes</button> : null}
        </div>
      </div>
    </div>
  );
}
