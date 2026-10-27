import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { Pagination } from './Pagination';

describe('Pagination', () => {
  it('renders nothing for a single page', () => {
    const { container } = render(<Pagination onPageChange={vi.fn()} page={1} totalPages={1} />);

    expect(container).toBeEmptyDOMElement();
  });

  it('marks the current page inside a labelled navigation landmark', () => {
    render(<Pagination onPageChange={vi.fn()} page={5} totalPages={10} />);

    const nav = screen.getByRole('navigation', { name: 'Pagination' });
    expect(within(nav).getByRole('button', { name: 'Page 5' })).toHaveAttribute('aria-current', 'page');
    expect(within(nav).getByRole('button', { name: 'Page 4' })).not.toHaveAttribute('aria-current');
    expect(within(nav).getAllByRole('button').map((button) => button.textContent)).toEqual([
      'Previous', '1', '4', '5', '6', '10', 'Next'
    ]);
    expect(within(nav).getByText('Page 5 of 10')).toBeInTheDocument();
  });

  it('reports the target page for Previous, Next and numbered buttons', () => {
    const onPageChange = vi.fn();
    render(<Pagination onPageChange={onPageChange} page={5} totalPages={10} />);

    fireEvent.click(screen.getByRole('button', { name: 'Previous' }));
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    fireEvent.click(screen.getByRole('button', { name: 'Page 10' }));
    fireEvent.click(screen.getByRole('button', { name: 'Page 5' }));

    expect(onPageChange.mock.calls).toEqual([[4], [6], [10]]);
  });

  it('disables Previous on the first page and Next on the last page', () => {
    const { rerender } = render(<Pagination onPageChange={vi.fn()} page={1} totalPages={3} />);

    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Next' })).toBeEnabled();

    rerender(<Pagination onPageChange={vi.fn()} page={3} totalPages={3} />);

    expect(screen.getByRole('button', { name: 'Previous' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
  });
});
