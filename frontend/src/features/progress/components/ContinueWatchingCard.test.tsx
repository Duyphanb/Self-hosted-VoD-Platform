import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import type { PlaybackProgress } from '../../movies/moviesApi';
import type { ContinueWatchingItem } from '../continueWatchingApi';
import { ContinueWatchingCard } from './ContinueWatchingCard';

const MOVIE_ID = '6f1c0f55-2d4a-4b64-9a43-1d0f3c7b8e21';

// The server lists only positions above 0 and below a known duration (ADR-011 D11). The contract still
// allows the values below, so the card must stay correct for them.
describe('ContinueWatchingCard with values the server does not list today', () => {
  it('shows only where it resumes when the duration is 0', () => {
    renderCard({ currentSeconds: 30, durationSeconds: 0 });

    const card = screen.getByRole('link');
    expect(card).toHaveTextContent('Resume at 0:30');
    expect(card).not.toHaveTextContent('left');
    expect(card.querySelector('[style]')).toBeNull();
  });

  it('shows no time left and a full bar when the position is at the end', () => {
    renderCard({ currentSeconds: 600, durationSeconds: 600 });

    const card = screen.getByRole('link');
    expect(card).toHaveTextContent('Resume at 10:00');
    expect(card).not.toHaveTextContent('left');
    expect(card.querySelector<HTMLElement>('[style]')?.style.width).toBe('100%');
  });

  it('never shows a full bar for a position one second before the end', () => {
    renderCard({ currentSeconds: 599, durationSeconds: 600 });

    const card = screen.getByRole('link');
    expect(card).toHaveTextContent('1 min left');
    expect(card.querySelector<HTMLElement>('[style]')?.style.width).toBe('99%');
  });

  it('keeps the bar and the position inside the duration when the position is beyond it', () => {
    renderCard({ currentSeconds: 900, durationSeconds: 600 });

    const card = screen.getByRole('link');
    expect(card).toHaveTextContent('Resume at 10:00');
    expect(card.querySelector<HTMLElement>('[style]')?.style.width).toBe('100%');
  });
});

function renderCard(progress: Pick<PlaybackProgress, 'currentSeconds' | 'durationSeconds'>) {
  const item: ContinueWatchingItem = {
    movie: {
      id: MOVIE_ID,
      title: 'The Long Night',
      slug: 'the-long-night',
      description: 'A storm traps a crew at sea.',
      releaseYear: 2019,
      posterUrl: null,
      genres: ['Drama'],
      averageRating: null,
      status: 'PUBLISHED',
      videoStatus: 'READY'
    },
    progress: {
      movieId: MOVIE_ID,
      finished: false,
      lastPlayedAt: '2026-11-22T10:00:00Z',
      ...progress
    }
  };
  render(
    <MemoryRouter future={{ v7_relativeSplatPath: true, v7_startTransition: true }}>
      <ContinueWatchingCard item={item} />
    </MemoryRouter>
  );
}
