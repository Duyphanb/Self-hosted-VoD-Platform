export type PaginationItem = number | 'start-ellipsis' | 'end-ellipsis';

/**
 * Page buttons for a 1-based `page` of `totalPages`: the first and last page, the current page with
 * `siblings` neighbours on each side, and an ellipsis for each gap of two or more pages. The item
 * count stays constant while the current page moves, so the controls do not jump around.
 */
export function paginationItems(page: number, totalPages: number, siblings = 1): PaginationItem[] {
  if (totalPages < 1) {
    return [];
  }
  // First, last, current, its siblings and two ellipses.
  const slots = 2 * siblings + 5;
  if (totalPages <= slots) {
    return range(1, totalPages);
  }

  const current = Math.min(Math.max(page, 1), totalPages);
  const windowStart = Math.max(current - siblings, 1);
  const windowEnd = Math.min(current + siblings, totalPages);
  const startGap = windowStart > 3;
  const endGap = windowEnd < totalPages - 2;
  const edgeCount = slots - 2;

  if (!startGap) {
    return [...range(1, edgeCount), 'end-ellipsis', totalPages];
  }
  if (!endGap) {
    return [1, 'start-ellipsis', ...range(totalPages - edgeCount + 1, totalPages)];
  }
  return [1, 'start-ellipsis', ...range(windowStart, windowEnd), 'end-ellipsis', totalPages];
}

function range(start: number, end: number): number[] {
  return Array.from({ length: end - start + 1 }, (_, index) => start + index);
}
