import { paginationItems } from '../lib/pagination';

interface PaginationProps {
  /** Current 1-based page. */
  page: number;
  totalPages: number;
  onPageChange(page: number): void;
}

const stepButtonClass = 'rounded-md border border-slate-700 px-3 py-2 text-sm font-medium text-slate-200 '
  + 'transition hover:border-slate-500 hover:text-white '
  + 'disabled:cursor-not-allowed disabled:border-slate-800 disabled:text-slate-400 disabled:hover:border-slate-800';
const pageButtonClass = 'min-w-10 rounded-md border px-3 py-2 text-sm font-medium transition';
const currentPageClass = 'border-sky-500 bg-sky-500 text-slate-950';
const otherPageClass = 'border-slate-700 text-slate-200 hover:border-slate-500 hover:text-white';

/**
 * Previous, numbered pages with ellipses, Next and "Page X of Y"; renders nothing for a single page.
 * Below `sm` the numbered pages are hidden and "Page X of Y" sits between Previous and Next.
 */
export function Pagination({ page, totalPages, onPageChange }: PaginationProps) {
  if (totalPages <= 1) {
    return null;
  }

  return (
    <nav aria-label="Pagination" className="flex flex-wrap items-center justify-center gap-x-2 gap-y-3">
      <button
        className={stepButtonClass}
        disabled={page <= 1}
        onClick={() => onPageChange(page - 1)}
        type="button"
      >
        Previous
      </button>
      <ul className="hidden flex-wrap items-center justify-center gap-2 sm:flex">
        {paginationItems(page, totalPages).map((item) => (typeof item === 'number' ? (
          <li key={item}>
            <button
              aria-current={item === page ? 'page' : undefined}
              aria-label={`Page ${item}`}
              className={`${pageButtonClass} ${item === page ? currentPageClass : otherPageClass}`}
              onClick={() => {
                if (item !== page) {
                  onPageChange(item);
                }
              }}
              type="button"
            >
              {item}
            </button>
          </li>
        ) : (
          <li aria-hidden="true" className="px-1 text-slate-400" key={item}>…</li>
        )))}
      </ul>
      {/* Between the step buttons on narrow screens; on its own line below them from sm. */}
      <p className="flex-1 text-center text-sm text-slate-400 sm:order-last sm:basis-full">
        Page {page} of {totalPages}
      </p>
      <button
        className={stepButtonClass}
        disabled={page >= totalPages}
        onClick={() => onPageChange(page + 1)}
        type="button"
      >
        Next
      </button>
    </nav>
  );
}
