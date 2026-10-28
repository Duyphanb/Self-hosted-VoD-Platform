import { useLayoutEffect, useRef, type RefObject } from 'react';

interface ModalFocusOptions {
  dialogRef: RefObject<HTMLElement>;
  initialFocusRef?: RefObject<HTMLElement>;
  onEscape: () => void;
  returnFocus?: () => void;
}

/** Keeps a modal exclusive even after backdrop clicks or programmatic focus outside its subtree. */
export function useModalFocus({ dialogRef, initialFocusRef, onEscape, returnFocus }: ModalFocusOptions) {
  const callbacks = useRef({ onEscape, returnFocus });
  useLayoutEffect(() => { callbacks.current = { onEscape, returnFocus }; }, [onEscape, returnFocus]);

  useLayoutEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    const previous = document.activeElement;
    const madeInert: HTMLElement[] = [];
    let ancestor: HTMLElement = dialog;
    while (ancestor.parentElement) {
      const parent = ancestor.parentElement;
      for (const sibling of parent.children) {
        if (sibling instanceof HTMLElement && sibling !== ancestor && !sibling.hasAttribute('inert')) {
          sibling.setAttribute('inert', '');
          madeInert.push(sibling);
        }
      }
      if (parent === document.body) break;
      ancestor = parent;
    }

    const focusable = () => [...dialog.querySelectorAll<HTMLElement>(
      'button, a[href], input, select, textarea, [tabindex]'
    )].filter((element) => element.tabIndex >= 0 && !element.matches(':disabled')
      && !element.closest('[hidden], [aria-hidden="true"]'));
    const focusInitial = () => {
      const initial = initialFocusRef?.current;
      if (initial && !initial.matches(':disabled')) initial.focus();
      else (focusable()[0] ?? dialog).focus();
    };
    const keepFocusInside = (event: FocusEvent) => {
      if (dialog.isConnected && event.target instanceof Node && !dialog.contains(event.target)) focusInitial();
    };
    const handleKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        callbacks.current.onEscape();
      } else if (event.key === 'Tab') {
        event.preventDefault();
        const elements = focusable();
        if (!elements.length) { dialog.focus(); return; }
        const current = elements.findIndex((element) => element === document.activeElement);
        const next = current < 0 ? (event.shiftKey ? elements.length - 1 : 0)
          : (current + (event.shiftKey ? -1 : 1) + elements.length) % elements.length;
        elements[next].focus();
      }
    };
    document.addEventListener('focusin', keepFocusInside, true);
    document.addEventListener('keydown', handleKey, true);
    focusInitial();
    return () => {
      document.removeEventListener('focusin', keepFocusInside, true);
      document.removeEventListener('keydown', handleKey, true);
      madeInert.forEach((element) => element.removeAttribute('inert'));
      if (callbacks.current.returnFocus) callbacks.current.returnFocus();
      else if (previous instanceof HTMLElement && previous.isConnected) previous.focus();
    };
  }, [dialogRef, initialFocusRef]);
}
