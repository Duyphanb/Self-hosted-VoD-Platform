import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { act, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AuthenticationSupersededError } from '../../lib/api/client';
import { useSupersededQueryRecovery } from './supersededQueryRecovery';

describe('useSupersededQueryRecovery', () => {
  it('runs a superseded query again once a new session is in place', async () => {
    const queryFn = vi.fn<() => Promise<string>>()
      .mockRejectedValueOnce(new AuthenticationSupersededError())
      .mockResolvedValue('movies');
    const { renewSession } = renderRecovery(queryFn);

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('error'));
    await settle();
    expect(queryFn).toHaveBeenCalledTimes(1);

    renewSession();

    expect(await screen.findByText('data: movies')).toBeInTheDocument();
    expect(queryFn).toHaveBeenCalledTimes(2);
  });

  it('runs it again when the error arrives after the new session is in place', async () => {
    let rejectFirst: ((error: unknown) => void) | undefined;
    const queryFn = vi.fn<() => Promise<string>>()
      .mockImplementationOnce(() => new Promise<string>((_resolve, reject) => {
        rejectFirst = reject;
      }))
      .mockResolvedValue('movies');
    const { renewSession } = renderRecovery(queryFn);
    await waitFor(() => expect(rejectFirst).toBeDefined());

    renewSession();
    await settle();
    expect(queryFn).toHaveBeenCalledTimes(1);
    act(() => rejectFirst?.(new AuthenticationSupersededError()));

    expect(await screen.findByText('data: movies')).toBeInTheDocument();
    expect(queryFn).toHaveBeenCalledTimes(2);
  });

  it('runs a query at most once per session, so a repeated failure cannot loop', async () => {
    const queryFn = vi.fn<() => Promise<string>>().mockRejectedValue(new AuthenticationSupersededError());
    const { renewSession } = renderRecovery(queryFn);
    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('error'));

    renewSession();
    await waitFor(() => expect(queryFn).toHaveBeenCalledTimes(2));
    await settle();
    expect(queryFn).toHaveBeenCalledTimes(2);
    expect(screen.getByTestId('status')).toHaveTextContent('error');
  });

  it('leaves other errors and signed-out sessions alone', async () => {
    const queryFn = vi.fn<() => Promise<string>>().mockRejectedValue(new Error('boom'));
    const { renewSession, signOut } = renderRecovery(queryFn);
    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('error'));

    renewSession();
    signOut();
    await settle();

    expect(queryFn).toHaveBeenCalledTimes(1);
  });
});

function renderRecovery(queryFn: () => Promise<string>) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const sessions = { current: { revision: 0 } as object | null };
  const readSession = () => sessions.current;

  function Harness({ session }: { session: object | null }) {
    useSupersededQueryRecovery(queryClient, session, readSession);
    return <Probe queryFn={queryFn} />;
  }

  const view = render(
    <QueryClientProvider client={queryClient}>
      <Harness session={sessions.current} />
    </QueryClientProvider>
  );
  const commit = (next: object | null) => {
    sessions.current = next;
    view.rerender(
      <QueryClientProvider client={queryClient}>
        <Harness session={next} />
      </QueryClientProvider>
    );
  };
  return {
    renewSession: () => commit({ revision: Math.random() }),
    signOut: () => commit(null)
  };
}

function Probe({ queryFn }: { queryFn: () => Promise<string> }) {
  const query = useQuery({ queryKey: ['recovery-test'], queryFn });
  return (
    <>
      <p data-testid="status">{query.status}</p>
      {query.data ? <p>data: {query.data}</p> : null}
    </>
  );
}

// Lets every pending promise and the recovery's deferred pass run before asserting that nothing more happened.
async function settle() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
}
