import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type PropsWithChildren, useCallback } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { AuthProvider } from '../features/auth/AuthContext';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false
    }
  }
});

export function AppProviders({ children }: PropsWithChildren) {
  const location = useLocation();
  const navigate = useNavigate();
  const redirectToLogin = useCallback(() => {
    if (location.pathname.toLowerCase() === '/login') {
      return;
    }
    navigate('/login', {
      replace: true,
      state: { from: `${location.pathname}${location.search}${location.hash}` }
    });
  }, [location.hash, location.pathname, location.search, navigate]);

  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider redirectToLogin={redirectToLogin}>{children}</AuthProvider>
    </QueryClientProvider>
  );
}
