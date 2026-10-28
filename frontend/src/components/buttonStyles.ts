/**
 * Filled sky action, the colours of the auth forms' submit button; also used on links. The transparent
 * border gives it the height of the outlined button beside it.
 */
export const primaryButtonClass = 'inline-flex items-center justify-center rounded-md border border-transparent '
  + 'bg-sky-500 px-4 py-2 text-sm font-semibold text-slate-950 transition hover:bg-sky-400';

/** Outlined action button used beside the primary sky button. */
export const secondaryButtonClass = 'rounded-md border border-slate-600 bg-slate-900 px-4 py-2 text-sm font-medium '
  + 'text-slate-100 transition hover:border-slate-400 hover:text-white '
  + 'disabled:cursor-not-allowed disabled:border-slate-800 disabled:text-slate-400 disabled:hover:border-slate-800';
