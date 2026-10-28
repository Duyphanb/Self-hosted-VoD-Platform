import { render } from '@testing-library/react';
import colors from 'tailwindcss/colors';
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

  it('keeps white text at 4.5:1 or more along every gradient of the palette', () => {
    const gradients = new Set(Array.from({ length: 200 }, (_, index) => gradientOf({
      ...movie,
      id: `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`
    })));
    expect(gradients.size).toBe(8);

    for (const gradient of gradients) {
      const [from, to] = gradient.split(' ').map((name) => tailwindColor(name.replace(/^(from|to)-/, '')));
      let lowest = Infinity;
      for (let step = 0; step <= 100; step += 1) {
        const background = from.map((channel, index) => channel + (to[index] - channel) * (step / 100));
        lowest = Math.min(lowest, contrastRatio([255, 255, 255], background));
      }
      expect(lowest, gradient).toBeGreaterThanOrEqual(4.5);
    }
  });

  it('renders every label in full white', () => {
    const { container } = render(<PosterArt movie={movie} />);
    const labels = [...(container.firstElementChild as HTMLElement).children];

    expect(labels).toHaveLength(3);
    for (const label of labels) {
      expect(label).toHaveClass('text-white');
      expect(label.className).not.toMatch(/text-white\//);
    }
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

function tailwindColor(name: string): number[] {
  const [hue, shade] = name.split('-');
  const hex = (colors as unknown as Record<string, Record<string, string>>)[hue][shade];
  return [1, 3, 5].map((offset) => parseInt(hex.slice(offset, offset + 2), 16));
}

// WCAG 2 contrast ratio of two sRGB colours given as 0-255 channels.
function contrastRatio(first: number[], second: number[]): number {
  const [lighter, darker] = [luminance(first), luminance(second)].sort((a, b) => b - a);
  return (lighter + 0.05) / (darker + 0.05);
}

function luminance(rgb: number[]): number {
  const [red, green, blue] = rgb.map((channel) => {
    const value = channel / 255;
    return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
}
