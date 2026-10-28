import { render, screen } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { NotFoundPage } from './NotFoundPage';

describe('NotFoundPage', () => {
  it('describes an unknown address and links home by default', () => {
    renderPage(<NotFoundPage />);

    expect(screen.getByRole('heading', { level: 1, name: 'Page not found' })).toBeInTheDocument();
    expect(screen.getByText('This address does not match any page.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Return home' })).toHaveAttribute('href', '/');
  });

  it('takes its own title, description and link', () => {
    renderPage(
      <NotFoundPage
        description="It may have been removed."
        link={{ to: '/movies', label: 'Back to movies' }}
        title="Movie not found"
      />
    );

    expect(screen.getByRole('heading', { level: 1, name: 'Movie not found' })).toBeInTheDocument();
    expect(screen.getByText('It may have been removed.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to movies' })).toHaveAttribute('href', '/movies');
    expect(screen.queryByText('Page not found')).not.toBeInTheDocument();
  });
});

function renderPage(page: ReactElement) {
  return render(
    <MemoryRouter future={{ v7_relativeSplatPath: true, v7_startTransition: true }}>{page}</MemoryRouter>
  );
}
