import Hls from 'hls.js';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { AuthenticationSnapshot } from '../../lib/api/client';
import { useApiClient, useAuth } from '../auth/AuthContext';
import type { PlaybackInfo } from './playbackApi';
import { startPlaybackRun, type PlayerSource, type PlayerStatus } from './playbackRun';

export interface HlsPlayerProps {
  movieId: string;
  /** The movie title; it labels the video element for assistive technology. */
  title: string;
  /** Read once at mount; mount a new player (for example with `key`) for another movie. */
  playbackInfo: PlaybackInfo;
  /**
   * Fetches the playback info again after an HLS 404 or a progress 409 VIDEO_ASSET_NOT_CURRENT
   * (ADR-011 D7, D13). It rejects with the API error, such as 404 MOVIE_NOT_FOUND or 409 VIDEO_NOT_READY.
   */
  reloadPlaybackInfo(): Promise<PlaybackInfo>;
}

interface FatalMessage {
  text: string;
  canRetry: boolean;
}

const FATAL_MESSAGES: Partial<Record<PlayerStatus | 'signedOut', FatalMessage>> = {
  unsupported: {
    text: 'This browser cannot play this video. Use a current version of Chrome, Edge, Firefox or Safari.',
    canRetry: false
  },
  networkError: {
    text: 'The video could not be loaded. Check your connection and try again.',
    canRetry: true
  },
  playbackError: { text: 'This video cannot be played right now.', canRetry: false },
  unavailable: { text: 'This video is no longer available.', canRetry: false },
  sessionEnded: { text: 'Your session has ended. Sign in again to keep watching.', canRetry: false },
  signedOut: { text: 'Your session has ended. Sign in again to keep watching.', canRetry: false },
  accountChanged: { text: 'Playback stopped because a different account signed in.', canRetry: false }
};

/**
 * The authenticated hls.js player (ADR-011 D1-D8, D13): a native `<video controls playsinline>` that
 * loads paused at the resume position, never starts playback on its own, sends the bearer token on every
 * HLS request, and saves progress. It stops for good when the signed-in identity changes.
 */
export function HlsPlayer({ movieId, title, playbackInfo, reloadPlaybackInfo }: HlsPlayerProps) {
  // Subscribing to the session re-renders the player when the signed-in identity changes.
  useAuth();
  const client = useApiClient();
  const currentIdentity = client.getAuthenticationSnapshot();
  const [playerIdentity] = useState<AuthenticationSnapshot | null>(currentIdentity);
  const [supported] = useState(() => Hls.isSupported());
  const [status, setStatus] = useState<PlayerStatus>('loading');
  const [buffering, setBuffering] = useState(false);
  const [source, setSource] = useState<PlayerSource>(() => ({
    videoAssetId: playbackInfo.videoAssetId,
    manifestUrl: playbackInfo.hlsManifestUrl,
    startSeconds: playbackInfo.resumeSeconds,
    attempt: 0
  }));
  const videoRef = useRef<HTMLVideoElement>(null);
  const hasPlayed = useRef(false);
  const lastPosition = useRef(playbackInfo.resumeSeconds);
  const reloadPlaybackInfoRef = useRef(reloadPlaybackInfo);

  useEffect(() => {
    reloadPlaybackInfoRef.current = reloadPlaybackInfo;
  }, [reloadPlaybackInfo]);

  const identityLost = !playerIdentity || !currentIdentity || !sameIdentity(playerIdentity, currentIdentity);
  const signedOut = !currentIdentity;

  const reload = useCallback((startSeconds: number, nextPlaybackInfo?: PlaybackInfo) => {
    setStatus('loading');
    setBuffering(false);
    setSource((previous) => ({
      videoAssetId: nextPlaybackInfo?.videoAssetId ?? previous.videoAssetId,
      manifestUrl: nextPlaybackInfo?.hlsManifestUrl ?? previous.manifestUrl,
      startSeconds,
      attempt: previous.attempt + 1
    }));
  }, []);

  useEffect(() => {
    const video = videoRef.current;
    if (!video || !supported || !playerIdentity || identityLost) {
      return;
    }
    return startPlaybackRun({
      video,
      client,
      identity: playerIdentity,
      movieId,
      source,
      hasPlayed,
      lastPosition,
      setStatus,
      setBuffering,
      reload,
      reloadPlaybackInfo: () => reloadPlaybackInfoRef.current()
    });
  }, [client, identityLost, movieId, playerIdentity, reload, source, supported]);

  const shownStatus = displayedStatus(supported, identityLost, signedOut, status);
  const fatalMessage = FATAL_MESSAGES[shownStatus];
  const retry = () => reload(lastPosition.current);

  return (
    <div className="relative overflow-hidden rounded-lg bg-black">
      <video
        ref={videoRef}
        className="aspect-video w-full bg-black"
        controls
        playsInline
        preload="auto"
        aria-label={title}
        hidden={!supported}
      />
      {buffering && shownStatus === 'ready' && (
        <span
          aria-hidden="true"
          data-testid="player-buffering"
          className="pointer-events-none absolute left-1/2 top-1/2 h-10 w-10 -translate-x-1/2 -translate-y-1/2
            animate-spin rounded-full border-4 border-white/30 border-t-white"
        />
      )}
      <p role="status" className="sr-only">
        {shownStatus === 'loading' ? 'Loading video…' : ''}
      </p>
      {fatalMessage && (
        <div role="alert" className="space-y-3 bg-slate-900 p-4 text-sm text-white">
          <p>{fatalMessage.text}</p>
          {fatalMessage.canRetry && (
            <button
              type="button"
              className="rounded-md bg-white px-3 py-1.5 font-medium text-slate-900 hover:bg-slate-200
                focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2"
              onClick={retry}
            >
              Retry
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function displayedStatus(
  supported: boolean,
  identityLost: boolean,
  signedOut: boolean,
  status: PlayerStatus
): PlayerStatus | 'signedOut' {
  if (!supported) {
    return 'unsupported';
  }
  if (signedOut) {
    return 'signedOut';
  }
  if (identityLost) {
    return 'accountChanged';
  }
  return status;
}

function sameIdentity(left: AuthenticationSnapshot, right: AuthenticationSnapshot): boolean {
  return left.userId === right.userId && left.generation === right.generation;
}
