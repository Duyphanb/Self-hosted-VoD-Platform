import { describe, expect, it } from 'vitest';
import { videoAvailability } from './movieAvailability';
import type { MovieSummary } from './moviesApi';

describe('videoAvailability', () => {
  it.each<[MovieSummary['videoStatus'], string, boolean]>([
    ['READY', 'Ready', true],
    ['UPLOADED', 'Processing', false],
    ['QUEUED', 'Processing', false],
    ['PROCESSING', 'Processing', false],
    ['FAILED', 'Not available', false],
    ['UNKNOWN', 'Not available', false],
    [null, 'Not available', false]
  ])('presents %s as %s and allows Watch only for READY', (status, label, canWatch) => {
    expect(videoAvailability(status)).toEqual({ label, canWatch });
  });
});
