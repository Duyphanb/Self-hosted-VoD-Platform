import { useEffect, useLayoutEffect, useRef, type RefObject } from 'react';
import { useBlocker } from 'react-router-dom';
import { useModalFocus } from '../../../components/useModalFocus';

/** Checking/saving must settle before leaving; only an active sender can be cancelled. */
export function UploadExitGuard({ pending, sending, cancel, outcomeRef, statusRef }: {
  pending: boolean; sending: boolean; cancel: () => boolean;
  outcomeRef: RefObject<HTMLDivElement>; statusRef: RefObject<HTMLDivElement>;
}) {
  const blocker = useBlocker(({ currentLocation, nextLocation }) => pending
    && currentLocation.pathname !== nextLocation.pathname
    && nextLocation.pathname !== '/login' && nextLocation.pathname !== '/forbidden');
  useEffect(() => {
    if (!pending) return;
    const beforeUnload = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; };
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, [pending]);
  useLayoutEffect(() => {
    // A response while the dialog is open cancels the navigation, never proceeds.
    if (!pending && blocker.state === 'blocked') blocker.reset();
  }, [pending, blocker]);
  return pending && blocker.state === 'blocked'
    ? <UploadExitDialog sending={sending} onStay={blocker.reset}
      returnFocus={() => (pending ? statusRef.current : outcomeRef.current)?.focus()}
      onLeave={() => { if (cancel()) blocker.proceed(); }} />
    : null;
}

function UploadExitDialog({ sending, onStay, onLeave, returnFocus }: {
  sending: boolean; onStay: () => void; onLeave: () => void; returnFocus: () => void;
}) {
  const dialogRef = useRef<HTMLDivElement>(null);
  const stayRef = useRef<HTMLButtonElement>(null);
  useModalFocus({ dialogRef, initialFocusRef: stayRef, onEscape: onStay, returnFocus });
  useLayoutEffect(() => {
    // The focused leave button can disappear when sending finishes.
    if (!sending) stayRef.current?.focus();
  }, [sending]);
  return <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-6">
    <div aria-describedby="leave-upload-description" aria-labelledby="leave-upload-heading" aria-modal="true"
      className="max-w-md rounded-lg border border-slate-700 bg-slate-900 p-6" ref={dialogRef} role="dialog" tabIndex={-1}>
      <h2 id="leave-upload-heading">Upload in progress</h2>
      <p className="mt-3" id="leave-upload-description">Wait for the upload result before leaving. Cancelling does not roll back server work.</p>
      <div className="mt-4 flex gap-3">
        <button className="rounded border px-3 py-2" onClick={onStay} ref={stayRef} type="button">Stay on page</button>
        {sending ? <button className="rounded border px-3 py-2" onClick={onLeave} type="button">Cancel upload and leave</button> : null}
      </div>
    </div>
  </div>;
}
