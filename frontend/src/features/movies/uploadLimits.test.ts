import { describe, expect, it } from 'vitest';
import { MAX_UPLOAD_SIZE_BYTES, MAX_UPLOAD_SIZE_MB } from './uploadLimits';

describe('upload limits', () => {
  it('uses the frozen 2048 MiB ceiling and binary byte conversion', () => {
    expect(MAX_UPLOAD_SIZE_MB).toBe(2048);
    expect(MAX_UPLOAD_SIZE_BYTES).toBe(2_147_483_648);
    expect(2_000_000_000).toBeLessThan(MAX_UPLOAD_SIZE_BYTES);
  });
});
