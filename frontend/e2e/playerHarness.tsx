import { StrictMode, useCallback, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider, useApiClient, useAuth } from '../src/features/auth/AuthContext';
import { HlsPlayer } from '../src/features/player/HlsPlayer';
import { fetchPlaybackInfo, type PlaybackInfo } from '../src/features/player/playbackApi';

const EPISODE_ID = '0b9a7c55-6f0e-4b3a-9d1c-2e4f6a8b0c1d';

/** Signs in through the real session protocol, then mounts the real player with real hls.js. */
export function PlayerHarness() {
  const auth = useAuth();
  const client = useApiClient();
  const [playbackInfo, setPlaybackInfo] = useState<PlaybackInfo | null>(null);
  const [loadError, setLoadError] = useState('none');

  const signIn = (email: string) => {
    void auth.login({ email, password: 'fixture-password' }).catch(() => undefined);
  };
  const openPlayer = () => {
    fetchPlaybackInfo(client, EPISODE_ID)
      .then(setPlaybackInfo)
      .catch(() => setLoadError('failed'));
  };
  const reloadPlaybackInfo = useCallback(() => fetchPlaybackInfo(client, EPISODE_ID), [client]);

  return (
    <main>
      <p data-testid="identity">{auth.user?.id ?? 'signed-out'}</p>
      <p data-testid="initializing">{String(auth.isInitializing)}</p>
      <p data-testid="load-error">{loadError}</p>
      <button onClick={() => signIn('alice@example.com')}>Sign in Alice</button>
      <button onClick={() => signIn('bob@example.com')}>Sign in Bob</button>
      <button onClick={openPlayer}>Open player</button>
      {playbackInfo && (
        <HlsPlayer
          episodeId={EPISODE_ID}
          title="Fixture movie"
          playbackInfo={playbackInfo}
          reloadPlaybackInfo={reloadPlaybackInfo}
        />
      )}
    </main>
  );
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={new QueryClient()}>
      <AuthProvider redirectToLogin={() => undefined}>
        <PlayerHarness />
      </AuthProvider>
    </QueryClientProvider>
  </StrictMode>
);
