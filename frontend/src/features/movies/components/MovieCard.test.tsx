import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import type { MovieSummary } from '../moviesApi';
import { MovieCard } from './MovieCard';

const movie: MovieSummary = {
  id: '6f1c0f55-2d4a-4b64-9a43-1d0f3c7b8e21',
  title: 'The Long Night',
  slug: 'the-long-night',
  description: 'A storm traps a crew at sea.',
  releaseYear: 2019,
  posterUrl: null,
  genres: ['Science Fiction', 'Documentary', 'Drama'],
  averageRating: null,
  status: 'PUBLISHED',
  videoStatus: null
};

describe('MovieCard', () => {
  it('is one link to the detail page with the title, year and two genres inside', () => {
    renderCard(movie);

    const link = screen.getByRole('link');
    expect(link).toHaveAttribute('href', `/movies/${movie.id}`);
    expect(within(link).getByRole('heading', { level: 2, name: 'The Long Night' })).toBeInTheDocument();
    expect(within(link).getByText('2019', { selector: 'p' })).toBeInTheDocument();
    // Wraps onto a second line instead of cutting off the second genre.
    expect(within(link).getByText('Science Fiction · Documentary')).toHaveClass('line-clamp-2', 'break-words');
    expect(link.querySelectorAll('a, button, input, select, textarea, [tabindex]')).toHaveLength(0);
  });

  it('encodes the id as a single path segment', () => {
    renderCard({ ...movie, id: '../admin?x=1#y' });

    expect(screen.getByRole('link')).toHaveAttribute('href', '/movies/..%2Fadmin%3Fx%3D1%23y');
  });

  it('leaves out a missing year and an empty genre list', () => {
    renderCard({ ...movie, releaseYear: null, genres: [] });

    const link = screen.getByRole('link');
    expect(link.querySelectorAll('p')).toHaveLength(0);
  });
});

function renderCard(summary: MovieSummary) {
  return render(
    <MemoryRouter future={{ v7_relativeSplatPath: true, v7_startTransition: true }}>
      <MovieCard movie={summary} />
    </MemoryRouter>
  );
}
