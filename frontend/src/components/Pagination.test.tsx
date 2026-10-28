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

  it('shows only Previous, "Page X of Y" and Next below sm', () => {
    render(<Pagination onPageChange={vi.fn()} page={6} totalPages={11} />);

    const nav = screen.getByRole('navigation', { name: 'Pagination' });
    const numbered = within(nav).getByRole('list');
    // Tailwind: hidden below sm, a flex row from sm.
    expect(numbered).toHaveClass('hidden', 'sm:flex');
    expect(within(numbered).queryByRole('button', { name: 'Previous' })).not.toBeInTheDocument();
    expect(within(numbered).queryByRole('button', { name: 'Next' })).not.toBeInTheDocument();
    // In the DOM between the step buttons; from sm it moves to its own line below them.
    const status = within(nav).getByText('Page 6 of 11');
    expect([...nav.children].map((child) => child.tagName)).toEqual(['BUTTON', 'UL', 'P', 'BUTTON']);
    expect(status).toHaveClass('sm:order-last', 'sm:basis-full');
  });

  it('keeps disabled step buttons at slate-400 or lighter text', () => {
    render(<Pagination onPageChange={vi.fn()} page={1} totalPages={3} />);

    const previous = screen.getByRole('button', { name: 'Previous' });
    expect(previous).toBeDisabled();
    expect(previous).toHaveClass('disabled:text-slate-400');
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
