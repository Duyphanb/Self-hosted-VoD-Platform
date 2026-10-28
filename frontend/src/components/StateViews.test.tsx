import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ApiRequestError } from '../lib/api/client';
import { EmptyState, ErrorState, LoadingState } from './StateViews';

describe('LoadingState', () => {
  it('announces the label next to a reduced-motion spinner when there is no skeleton', () => {
    render(<LoadingState label="Checking authentication…" />);

    const status = screen.getByRole('status');
    expect(status).toHaveTextContent('Checking authentication…');
    const spinner = status.querySelector('[aria-hidden="true"]');
    expect(spinner).toHaveClass('animate-spin', 'motion-reduce:animate-none');
  });

  it('keeps the label in the status and hides the skeleton from assistive technology', () => {
    render(
      <LoadingState label="Loading movies…">
        <div data-testid="skeleton">placeholder card</div>
      </LoadingState>
    );

    const status = screen.getByRole('status');
    expect(within(status).getByText('Loading movies…')).toHaveClass('sr-only');
    expect(screen.getByTestId('skeleton').parentElement).toHaveAttribute('aria-hidden', 'true');
    expect(screen.getByTestId('skeleton').closest('[role="status"]')).toBe(status);
  });
});

describe('EmptyState', () => {
  it('renders an h2 title, the description and the actions', () => {
    render(
      <EmptyState description="Try another genre." title="No movies match">
        <button type="button">Clear filters</button>
      </EmptyState>
    );

    expect(screen.getByRole('heading', { level: 2, name: 'No movies match' })).toBeInTheDocument();
    expect(screen.getByText('Try another genre.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Clear filters' })).toBeInTheDocument();
  });

  it('renders an h1 title when it is the whole page', () => {
    render(<EmptyState headingLevel={1} title="Movie not found" />);

    expect(screen.getByRole('heading', { level: 1, name: 'Movie not found' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 2 })).not.toBeInTheDocument();
  });
});

describe('ErrorState', () => {
  const apiError = (requestId?: string | null) => new ApiRequestError(503, {
    timestamp: '2026-10-28T00:00:00Z',
    status: 503,
    code: 'SERVICE_UNAVAILABLE',
    message: 'The catalog is temporarily unavailable.',
    ...(requestId === undefined ? {} : { requestId })
  });

  it('shows the server message and request id in an alert', () => {
    render(<ErrorState error={apiError('req-7f3a')} title="Movies could not be loaded" />);

    const alert = screen.getByRole('alert');
    expect(within(alert).getByRole('heading', { level: 2, name: 'Movies could not be loaded' })).toBeInTheDocument();
    expect(alert).toHaveTextContent('The catalog is temporarily unavailable.');
    expect(alert).toHaveTextContent('Request ID: req-7f3a');
    expect(within(alert).queryByRole('button')).not.toBeInTheDocument();
  });

  it.each([undefined, null, ''])('leaves out the request id when it is %j', (requestId) => {
    render(<ErrorState error={apiError(requestId)} title="Movies could not be loaded" />);

    expect(screen.getByRole('alert')).not.toHaveTextContent('Request ID');
  });

  it('shows a generic message for a failure that is not an API error', () => {
    render(<ErrorState error={new TypeError('Failed to fetch')} title="Movies could not be loaded" />);

    expect(screen.getByRole('alert')).toHaveTextContent(
      'The server could not be reached or sent an unexpected response.'
    );
    expect(screen.getByRole('alert')).not.toHaveTextContent('Failed to fetch');
  });

  it('retries on request and disables Retry while retrying', () => {
    const onRetry = vi.fn();
    const { rerender } = render(<ErrorState error={apiError()} onRetry={onRetry} title="Failed" />);

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledTimes(1);

    rerender(<ErrorState error={apiError()} isRetrying onRetry={onRetry} title="Failed" />);

    const button = screen.getByRole('button', { name: 'Retrying…' });
    expect(button).toBeDisabled();
    fireEvent.click(button);
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('renders an h1 title when it is the whole page', () => {
    render(<ErrorState error={apiError()} headingLevel={1} title="Movie could not be loaded" />);

    expect(screen.getByRole('heading', { level: 1, name: 'Movie could not be loaded' })).toBeInTheDocument();
  });
});
