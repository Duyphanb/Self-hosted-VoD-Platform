import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { MovieListFilters } from '../movieListParams';
import { MovieFilters } from './MovieFilters';

const NO_FILTERS: MovieListFilters = { genre: null, year: null, sort: 'latest' };
const GENRE_ERROR = 'Enter a genre with at least one Latin letter (a–z) or digit.';
const YEAR_ERROR = 'Enter a year from 1888 to 2100.';

describe('MovieFilters', () => {
  it('applies a typed genre on Enter, not while typing', () => {
    const { onFiltersChange } = renderFilters();
    const genre = screen.getByLabelText('Genre');

    fireEvent.change(genre, { target: { value: 'Dra' } });
    fireEvent.change(genre, { target: { value: 'Drama ' } });
    expect(onFiltersChange).not.toHaveBeenCalled();

    fireEvent.keyDown(genre, { key: 'Enter' });

    expect(onFiltersChange.mock.calls).toEqual([[{ genre: 'Drama', year: null, sort: 'latest' }]]);
  });

  it('applies a changed field when it loses focus, and nothing for an unchanged one', () => {
    const { onFiltersChange } = renderFilters({ ...NO_FILTERS, year: 2001 });
    const genre = screen.getByLabelText('Genre');
    const year = screen.getByLabelText('Year');

    fireEvent.blur(genre);
    fireEvent.blur(year);
    expect(onFiltersChange).not.toHaveBeenCalled();

    fireEvent.change(year, { target: { value: '1999' } });
    fireEvent.blur(year);

    expect(onFiltersChange.mock.calls).toEqual([[{ genre: null, year: 1999, sort: 'latest' }]]);
  });

  it('does not apply on the Enter that ends an IME composition', () => {
    const { onFiltersChange } = renderFilters();
    const genre = screen.getByLabelText('Genre');
    fireEvent.change(genre, { target: { value: 'Hài' } });

    const composing = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true });
    Object.defineProperty(composing, 'isComposing', { value: true });
    fireEvent(genre, composing);
    expect(onFiltersChange).not.toHaveBeenCalled();

    fireEvent.keyDown(genre, { key: 'Enter' });
    expect(onFiltersChange).toHaveBeenCalledWith({ genre: 'Hài', year: null, sort: 'latest' });
  });

  it.each(['!!!', '#', '-', 'Драма', '東京'])('flags the genre %j, whose slug is empty, and never sends it', (value) => {
    const { onFiltersChange } = renderFilters();
    const genre = screen.getByLabelText('Genre');

    fireEvent.change(genre, { target: { value } });
    expect(screen.queryByText(GENRE_ERROR)).not.toBeInTheDocument();
    fireEvent.keyDown(genre, { key: 'Enter' });
    fireEvent.blur(genre);

    expect(onFiltersChange).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent(GENRE_ERROR);
    expect(genre).toHaveAttribute('aria-invalid', 'true');
    expect(genre).toHaveAccessibleDescription(GENRE_ERROR);
    expect(genre).toHaveValue(value);
  });

  it('hides the genre error while the value is edited and sends it once it is valid', () => {
    const { onFiltersChange } = renderFilters();
    const genre = screen.getByLabelText('Genre');
    fireEvent.change(genre, { target: { value: '#' } });
    fireEvent.keyDown(genre, { key: 'Enter' });
    expect(screen.getByText(GENRE_ERROR)).toBeInTheDocument();

    fireEvent.change(genre, { target: { value: '#1' } });
    expect(screen.queryByText(GENRE_ERROR)).not.toBeInTheDocument();
    expect(genre).toHaveAttribute('aria-invalid', 'false');
    fireEvent.keyDown(genre, { key: 'Enter' });

    expect(onFiltersChange).toHaveBeenCalledWith({ genre: '#1', year: null, sort: 'latest' });
  });

  it('keeps the applied genre while an invalid draft is flagged', () => {
    const { onFiltersChange } = renderFilters({ ...NO_FILTERS, genre: 'Drama' });
    const genre = screen.getByLabelText('Genre');
    fireEvent.change(genre, { target: { value: '!!!' } });

    fireEvent.change(screen.getByLabelText('Sort by'), { target: { value: 'title' } });

    expect(onFiltersChange.mock.calls).toEqual([[{ genre: 'Drama', year: null, sort: 'title' }]]);
    expect(screen.getByText(GENRE_ERROR)).toBeInTheDocument();
  });

  it('shows no year error while a year is being typed, but flags it on Enter or blur', () => {
    const { onFiltersChange } = renderFilters();
    const year = screen.getByLabelText('Year');

    fireEvent.change(year, { target: { value: '19' } });
    expect(screen.queryByText(YEAR_ERROR)).not.toBeInTheDocument();
    expect(year).toHaveAttribute('aria-invalid', 'false');

    fireEvent.keyDown(year, { key: 'Enter' });
    expect(screen.getByRole('alert')).toHaveTextContent(YEAR_ERROR);
    expect(year).toHaveAccessibleDescription(YEAR_ERROR);

    fireEvent.change(year, { target: { value: '199' } });
    expect(screen.queryByText(YEAR_ERROR)).not.toBeInTheDocument();
    fireEvent.blur(year);
    expect(screen.getByText(YEAR_ERROR)).toBeInTheDocument();

    expect(onFiltersChange).not.toHaveBeenCalled();
  });

  it.each([
    ['1700', 'a fourth digit outside 1888-2100'],
    ['2101', 'a fourth digit outside 1888-2100'],
    ['19a', 'a character that is not a digit'],
    ['-', 'a minus sign'],
    ['1e3', 'an exponent']
  ])('flags the year %j as soon as it has %s', (value) => {
    const { onFiltersChange } = renderFilters();
    const year = screen.getByLabelText('Year');

    fireEvent.change(year, { target: { value } });

    expect(screen.getByRole('alert')).toHaveTextContent(YEAR_ERROR);
    expect(year).toHaveAttribute('aria-invalid', 'true');
    expect(year).toHaveValue(value);
    fireEvent.keyDown(year, { key: 'Enter' });
    expect(onFiltersChange).not.toHaveBeenCalled();
  });

  it('uses a numeric text field, so no typed value is silently dropped', () => {
    renderFilters();
    const year = screen.getByLabelText('Year');

    expect(year).toHaveAttribute('type', 'text');
    expect(year).toHaveAttribute('inputMode', 'numeric');
    expect(year).toHaveAttribute('pattern', '[0-9]*');
    expect(year).toHaveAttribute('maxLength', '4');
  });

  it('keeps the applied year when its draft is invalid', () => {
    const { onFiltersChange } = renderFilters({ ...NO_FILTERS, year: 2000 });
    const year = screen.getByLabelText('Year');
    fireEvent.change(year, { target: { value: '20' } });

    fireEvent.blur(year);
    expect(onFiltersChange).not.toHaveBeenCalled();
    expect(screen.getByText(YEAR_ERROR)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Sort by'), { target: { value: 'title' } });
    expect(onFiltersChange.mock.calls).toEqual([[{ genre: null, year: 2000, sort: 'title' }]]);
  });

  it('applies a sort change at once, together with valid typed values', () => {
    const { onFiltersChange } = renderFilters();
    fireEvent.change(screen.getByLabelText('Genre'), { target: { value: 'Noir' } });

    fireEvent.change(screen.getByLabelText('Sort by'), { target: { value: 'title' } });

    expect(onFiltersChange.mock.calls).toEqual([[{ genre: 'Noir', year: null, sort: 'title' }]]);
  });

  it('clears typed values without a request when no filter is applied, and focuses Genre', () => {
    const { onFiltersChange } = renderFilters();
    const genre = screen.getByLabelText('Genre');
    const year = screen.getByLabelText('Year');
    const clear = screen.getByRole('button', { name: 'Clear filters' });
    expect(clear).toBeDisabled();

    fireEvent.change(genre, { target: { value: 'Noir' } });
    fireEvent.change(year, { target: { value: '19x' } });
    expect(clear).toBeEnabled();
    clear.focus();
    fireEvent.click(clear);

    expect(onFiltersChange).not.toHaveBeenCalled();
    expect(genre).toHaveValue('');
    expect(year).toHaveValue('');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(clear).toBeDisabled();
    expect(genre).toHaveFocus();
  });

  it('clears applied filters, keeps the sort and moves focus to Genre', () => {
    const { onFiltersChange } = renderFilters({ genre: 'Drama', year: 2019, sort: 'title' });
    const clear = screen.getByRole('button', { name: 'Clear filters' });
    clear.focus();

    fireEvent.click(clear);

    expect(onFiltersChange.mock.calls).toEqual([[{ genre: null, year: null, sort: 'title' }]]);
    expect(screen.getByLabelText('Genre')).toHaveFocus();
  });

  it('follows filters changed elsewhere, such as Back or a rejected filter', () => {
    const { rerender, onFiltersChange } = renderFilters({ genre: 'Comedy', year: 2001, sort: 'latest' });
    const genre = screen.getByLabelText('Genre');
    const year = screen.getByLabelText('Year');
    fireEvent.change(year, { target: { value: '20x' } });
    expect(screen.getByText(YEAR_ERROR)).toBeInTheDocument();

    // Back: both values change.
    rerender(<MovieFilters filters={{ genre: 'Drama', year: 1999, sort: 'latest' }} onFiltersChange={onFiltersChange} />);
    expect(genre).toHaveValue('Drama');
    expect(year).toHaveValue('1999');
    expect(screen.queryByText(YEAR_ERROR)).not.toBeInTheDocument();

    // A rejected genre: only the genre changes, so a year being typed stays.
    fireEvent.change(year, { target: { value: '200' } });
    rerender(<MovieFilters filters={{ genre: null, year: 1999, sort: 'latest' }} onFiltersChange={onFiltersChange} />);
    expect(genre).toHaveValue('');
    expect(year).toHaveValue('200');

    // Clear filters in the empty state.
    rerender(<MovieFilters filters={NO_FILTERS} onFiltersChange={onFiltersChange} />);
    expect(year).toHaveValue('');
    expect(onFiltersChange).not.toHaveBeenCalled();
  });

  it('keeps a keystroke typed while its own change reaches the URL', () => {
    const { rerender, onFiltersChange } = renderFilters();
    const genre = screen.getByLabelText('Genre');
    fireEvent.change(genre, { target: { value: 'Dram' } });
    fireEvent.keyDown(genre, { key: 'Enter' });
    expect(onFiltersChange).toHaveBeenLastCalledWith({ genre: 'Dram', year: null, sort: 'latest' });

    // The user types on before the router renders the requested URL.
    fireEvent.change(genre, { target: { value: 'Drama' } });
    rerender(<MovieFilters filters={{ ...NO_FILTERS, genre: 'Dram' }} onFiltersChange={onFiltersChange} />);
    expect(genre).toHaveValue('Drama');

    // A later change made elsewhere is followed again.
    rerender(<MovieFilters filters={NO_FILTERS} onFiltersChange={onFiltersChange} />);
    expect(genre).toHaveValue('');
  });

  it('labels every field and offers both sort orders', () => {
    renderFilters();

    expect(screen.getByRole('search', { name: 'Movie filters' })).toBeInTheDocument();
    expect(screen.getByLabelText('Genre')).toHaveAttribute('placeholder', 'Any genre');
    expect(screen.getByLabelText('Year')).toHaveAttribute('placeholder', 'Any year');
    expect(screen.getByLabelText('Genre')).toHaveClass('placeholder:text-slate-400');
    expect(screen.getByRole('option', { name: 'Recently added' })).toHaveValue('latest');
    expect(screen.getByRole('option', { name: 'Title A–Z' })).toHaveValue('title');
  });
});

function renderFilters(filters: MovieListFilters = NO_FILTERS) {
  const onFiltersChange = vi.fn<(filters: MovieListFilters) => void>();
  const view = render(<MovieFilters filters={filters} onFiltersChange={onFiltersChange} />);
  return { ...view, onFiltersChange };
}
