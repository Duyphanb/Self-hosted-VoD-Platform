const CANONICAL_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/** Whether `value` is a UUID in the canonical lower-case 8-4-4-4-12 form that the API writes. */
export function isCanonicalUuid(value: unknown): value is string {
  return typeof value === 'string' && CANONICAL_UUID.test(value);
}
