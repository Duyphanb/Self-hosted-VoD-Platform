const COMBINING_MARKS = /\p{M}+/gu;
const NON_ALPHANUMERIC_RUN = /[^a-z0-9]+/g;
const EDGE_HYPHENS = /^-+|-+$/g;

/**
 * ADR-009 slug rule, the same steps as the backend's `SlugNormalizer.slugify`: decompose (NFD),
 * remove combining marks, map đ/Đ to d, lower-case, replace every run of characters other than a-z
 * and 0-9 with a hyphen, and trim leading and trailing hyphens.
 *
 * Letters without a decomposition into a-z (ß, Ø, Cyrillic, CJK) act as separators, so the result is
 * empty when the value has no a-z or 0-9 character after normalization. The API rejects a genre
 * filter or genre name whose slug is empty.
 */
export function slugify(value: string): string {
  return value
    .normalize('NFD')
    .replace(COMBINING_MARKS, '')
    .replace(/[đĐ]/g, 'd')
    .toLowerCase()
    .replace(NON_ALPHANUMERIC_RUN, '-')
    .replace(EDGE_HYPHENS, '');
}
