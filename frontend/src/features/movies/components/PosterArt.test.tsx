import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PosterArt } from './PosterArt';

const movie = {
  id: '6f1c0f55-2d4a-4b64-9a43-1d0f3c7b8e21',
  title: 'The Long Night',
  genres: ['Drama', 'Thriller'],
  releaseYear: 2019
};

describe('PosterArt', () => {
  it('gives the same movie id the same gradient on every render', () => {
    const first = gradientOf({ ...movie });
    const second = gradientOf({ ...movie, title: 'Renamed', genres: [], releaseYear: null });

    expect(first).not.toBe('');
    expect(second).toBe(first);
  });

  it('spreads different ids over the palette', () => {
    const gradients = new Set(Array.from({ length: 40 }, (_, index) => gradientOf({
      ...movie,
      id: `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`
    })));

    expect(gradients.size).toBeGreaterThan(4);
  });

  it('shows the title, first genre and year, hidden from assistive technology', () => {
    const { container } = render(<PosterArt movie={movie} />);
    const poster = container.firstElementChild as HTMLElement;

    expect(poster).toHaveAttribute('aria-hidden', 'true');
    expect(poster).toHaveTextContent('Drama');
    expect(poster).not.toHaveTextContent('Thriller');
    expect(poster).toHaveTextContent('The Long Night');
    expect(poster).toHaveTextContent('2019');
    expect(container.querySelector('img')).toBeNull();
  });
});

function gradientOf(props: { id: string; title: string; genres: string[]; releaseYear: number | null }) {
  const { container, unmount } = render(<PosterArt movie={props} />);
  const gradient = (container.firstElementChild as HTMLElement).className
    .split(/\s+/)
    .filter((name) => name.startsWith('from-') || name.startsWith('to-'))
    .join(' ');
  unmount();
  return gradient;
}
