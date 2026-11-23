import { describe, expect, it } from 'vitest';
import { formatPlaybackPosition, formatTimeLeft } from './playbackTime';

describe('formatPlaybackPosition', () => {
  it.each([
    [0, '0:00'],
    [45, '0:45'],
    [754, '12:34'],
    [3599, '59:59'],
    [3600, '1:00:00'],
    [3723, '1:02:03'],
    [36_000, '10:00:00']
  ])('shows %i seconds as %s', (seconds, expected) => {
    expect(formatPlaybackPosition(seconds)).toBe(expected);
  });

  it('drops a fraction of a second instead of rounding up', () => {
    expect(formatPlaybackPosition(59.9)).toBe('0:59');
  });

  it('shows a negative position as the start', () => {
    expect(formatPlaybackPosition(-5)).toBe('0:00');
  });
});

describe('formatTimeLeft', () => {
  it.each([
    [1, '1 min left'],
    [60, '1 min left'],
    [61, '2 min left'],
    [2700, '45 min left'],
    [3540, '59 min left'],
    [3600, '1 h left'],
    [3601, '1 h 1 min left'],
    [3900, '1 h 5 min left'],
    [7200, '2 h left']
  ])('shows %i seconds left as "%s", rounded up to whole minutes', (seconds, expected) => {
    expect(formatTimeLeft(seconds)).toBe(expected);
  });
});
