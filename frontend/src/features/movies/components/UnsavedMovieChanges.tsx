import { useEffect, useRef, type RefObject } from 'react';
import { useBlocker } from 'react-router-dom';

/** Data-router navigation blocking also covers header links and browser Back/Forward. */
export function UnsavedMovieChanges({ dirty, allowNavigation }: { dirty: boolean; allowNavigation: RefObject<boolean> }) {
  const blocker = useBlocker(({ currentLocation, nextLocation }) => dirty && !allowNavigation.current
    && currentLocation.pathname !== nextLocation.pathname
    // Authentication/authorization redirects must never wait on a form confirmation.
    && nextLocation.pathname !== '/login' && nextLocation.pathname !== '/forbidden');

  useEffect(() => {
    if (!dirty) return;
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (allowNavigation.current) return;
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, [dirty, allowNavigation]);

  return blocker.state === 'blocked'
    ? <DiscardChangesDialog onDiscard={() => blocker.proceed()} onKeep={() => blocker.reset()} />
    : null;
}

function DiscardChangesDialog({ onDiscard, onKeep }: { onDiscard: () => void; onKeep: () => void }) {
  const cancelRef = useRef<HTMLButtonElement>(null);
  const discardRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const previous = document.activeElement;
    cancelRef.current?.focus();
    return () => { if (previous instanceof HTMLElement && previous.isConnected) previous.focus(); };
  }, []);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-6">
      <div
        aria-describedby="discard-movie-description"
        aria-labelledby="discard-movie-heading"
        aria-modal="true"
        className="w-full max-w-md rounded-lg border border-slate-700 bg-slate-900 p-6"
        onKeyDown={(event) => {
          if (event.key === 'Escape') { event.preventDefault(); onKeep(); }
          if (event.key === 'Tab') {
            event.preventDefault();
            if (document.activeElement === cancelRef.current) discardRef.current?.focus();
            else cancelRef.current?.focus();
          }
        }}
        role="dialog"
      >
        <h2 className="text-lg font-semibold text-white" id="discard-movie-heading">Discard changes?</h2>
        <p className="mt-3 text-slate-300" id="discard-movie-description">Your unsaved movie changes will be lost.</p>
        <div className="mt-6 flex flex-wrap gap-3">
          <button className="rounded-md border border-slate-600 px-4 py-2" onClick={onKeep} ref={cancelRef} type="button">Keep editing</button>
          <button className="rounded-md bg-rose-700 px-4 py-2 font-semibold text-white" onClick={onDiscard} ref={discardRef} type="button">Discard changes</button>
        </div>
      </div>
    </div>
  );
}
