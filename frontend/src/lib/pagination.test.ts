import { describe, expect, it } from 'vitest';
import { MAX_LIST_PAGE, paginationItems, parseListPage } from './pagination';

describe('parseListPage', () => {
  it('reads whole pages from 1', () => {
    expect(parseListPage('1')).toBe(1);
    expect(parseListPage('2')).toBe(2);
    expect(parseListPage('007')).toBe(7);
    expect(parseListPage('10000')).toBe(10_000);
  });

  it('keeps the last page the API accepts and clamps anything above it', () => {
    expect(MAX_LIST_PAGE).toBe(10_001);
    expect(parseListPage('10001')).toBe(MAX_LIST_PAGE);
    expect(parseListPage('10002')).toBe(MAX_LIST_PAGE);
    expect(parseListPage('99999999999999999999')).toBe(MAX_LIST_PAGE);
  });

  it.each([null, '', '0', '00', '-1', '1.5', ' 2', '2 ', '+2', '2e3', '0x10', 'abc', '١'])(
    'turns %j into page 1',
    (value) => {
      expect(parseListPage(value)).toBe(1);
    }
  );
});

describe('paginationItems', () => {
  it('lists every page when they fit', () => {
    expect(paginationItems(1, 1)).toEqual([1]);
    expect(paginationItems(4, 7)).toEqual([1, 2, 3, 4, 5, 6, 7]);
  });

  it('returns nothing without pages', () => {
    expect(paginationItems(1, 0)).toEqual([]);
  });

  it('collapses the far end near the first page', () => {
    expect(paginationItems(1, 10)).toEqual([1, 2, 3, 4, 5, 'end-ellipsis', 10]);
    expect(paginationItems(4, 10)).toEqual([1, 2, 3, 4, 5, 'end-ellipsis', 10]);
  });

  it('collapses both sides in the middle', () => {
    expect(paginationItems(5, 10)).toEqual([1, 'start-ellipsis', 4, 5, 6, 'end-ellipsis', 10]);
    expect(paginationItems(500, 10_001)).toEqual([
      1, 'start-ellipsis', 499, 500, 501, 'end-ellipsis', 10_001
    ]);
  });

  it('collapses the near end close to the last page', () => {
    expect(paginationItems(7, 10)).toEqual([1, 'start-ellipsis', 6, 7, 8, 9, 10]);
    expect(paginationItems(10, 10)).toEqual([1, 'start-ellipsis', 6, 7, 8, 9, 10]);
  });

  it('never hides a single page behind an ellipsis', () => {
    for (let total = 1; total <= 30; total += 1) {
      for (let page = 1; page <= total; page += 1) {
        const items = paginationItems(page, total);
        items.forEach((item, index) => {
          if (typeof item !== 'number') {
            const before = items[index - 1] as number;
            const after = items[index + 1] as number;
            expect(after - before, `page ${page} of ${total}`).toBeGreaterThan(2);
          }
        });
        expect(items).toContain(page);
        expect(items[0]).toBe(1);
        expect(items[items.length - 1]).toBe(total);
      }
    }
  });
});
