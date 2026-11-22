import Hls, { type ErrorData, type HlsConfig } from 'hls.js';
import {
  ApiRequestError,
  AuthenticationSupersededError,
  type ApiClient,
  type AuthenticationSnapshot
} from '../../lib/api/client';
import {
  failedRequest,
  HlsRequestAuthenticator,
  type FailedRequest,
  type HlsAuthenticationFailure
} from './hlsAuthentication';
import type { PlaybackInfo, ProgressSave } from './playbackApi';
import type { ProgressSaver } from './progressSaver';

/** ADR-011 D13: within the 5-10 second target of NFR progress durability. */
export const PROGRESS_SAVE_INTERVAL_MS = 10_000;

/** hls.js 1.7.4 `ErrorActionFlags.ResetMediaSource`: the library asked for `recoverMediaError()`. */
const RESET_MEDIA_SOURCE_FLAG = 16;

export type PlayerStatus =
  | 'loading'
  | 'ready'
  | 'unsupported'
  | 'networkError'
  | 'playbackError'
  | 'unavailable'
  | 'sessionEnded'
  | 'accountChanged';

/** What one hls.js instance plays. A new value starts a new run. */
export interface PlayerSource {
  videoAssetId: string;
  manifestUrl: string;
  /** Where loading starts, in seconds; 0 starts from the beginning. */
  startSeconds: number;
  /** Counts reloads, so the same asset can be loaded again. */
  attempt: number;
}

/** State that outlives one run: the same player keeps it across reloads and asset changes. */
export interface PlayerMemory {
  /** No save happens before playback first started. */
  hasPlayed: boolean;
  /** The last known position, kept when hls.js detaches the video. */
  lastPosition: number;
  /** The position where playback reached the end; saves report `finished` until the viewer leaves it. */
  endedAt: number | null;
}

export interface PlaybackRunOptions {
  video: HTMLVideoElement;
  client: ApiClient;
  /** The identity that was signed in when the player mounted. */
  identity: AuthenticationSnapshot;
  source: PlayerSource;
  memory: PlayerMemory;
  /** The player's one progress saver, shared by all runs so that saves stay in order. */
  saver: ProgressSaver;
  /** Set by the run: what to do when a save of `videoAssetId` answers 409 VIDEO_ASSET_NOT_CURRENT. */
  assetNotCurrentHandler: { current: (videoAssetId: string) => void };
  setStatus(status: PlayerStatus): void;
  setBuffering(buffering: boolean): void;
  /** Start a new run at `startSeconds`, with the asset of `playbackInfo` when given. */
  reload(startSeconds: number, playbackInfo?: PlaybackInfo): void;
  reloadPlaybackInfo(): Promise<PlaybackInfo>;
}

/**
 * One hls.js instance for one source: authentication (ADR-011 D2), error states (D7), start position
 * (D8) and progress save triggers (D13). Returns the cleanup, which saves progress and destroys hls.js.
 */
export function startPlaybackRun(options: PlaybackRunOptions): () => void {
  const { video, source, memory, saver } = options;
  // The previous run's cleanup may have recorded its own position; this run starts at its start position.
  memory.lastPosition = source.startSeconds;
  const authenticator = new HlsRequestAuthenticator(options.client, options.identity);
  const hls = new Hls(createHlsConfig(authenticator, source.startSeconds));
  let disposed = false;
  let hlsDestroyed = false;
  let mediaRecoveryUsed = false;
  let mediaRecoveryRefused = false;
  let positionBeforeRecovery: number | null = null;
  let assetCheck: Promise<AssetCheckResult> | null = null;
  let reloadRequested = false;
  let networkFailureActive = false;
  let saveTimer: ReturnType<typeof setInterval> | null = null;
  // Until hls.js has moved the video to its start position, `video.currentTime` is still 0, so the last
  // known position (the resume position at first) stays authoritative for a reload.
  let mediaPositionKnown = false;

  const currentPosition = (): number => {
    if (!hlsDestroyed && mediaPositionKnown) {
      memory.lastPosition = video.currentTime;
    }
    return memory.lastPosition;
  };

  const snapshot = (): ProgressSave => ({
    videoAssetId: source.videoAssetId,
    currentSeconds: Math.floor(currentPosition()),
    finished: memory.endedAt !== null
  });

  const save = () => {
    if (memory.hasPlayed) {
      saver.request(snapshot());
    }
  };

  const stopSaveTimer = () => {
    if (saveTimer !== null) {
      clearInterval(saveTimer);
      saveTimer = null;
    }
  };

  const startSaveTimer = () => {
    if (saveTimer !== null) {
      return;
    }
    saveTimer = setInterval(() => {
      if (!video.paused && !video.ended) {
        save();
      }
    }, PROGRESS_SAVE_INTERVAL_MS);
  };

  const videoListeners: Record<string, () => void> = {
    canplay: () => {
      options.setBuffering(false);
      options.setStatus('ready');
    },
    waiting: () => options.setBuffering(true),
    playing: () => {
      mediaPositionKnown = true;
      memory.hasPlayed = true;
      memory.endedAt = null;
      options.setBuffering(false);
      startSaveTimer();
    },
    pause: () => {
      stopSaveTimer();
      options.setBuffering(false);
      save();
    },
    seeked: () => {
      mediaPositionKnown = true;
      currentPosition();
      const leftTheEnd = memory.endedAt !== null && video.currentTime < memory.endedAt - 1;
      if (leftTheEnd) {
        memory.endedAt = null;
      }
      save();
    },
    ended: () => {
      stopSaveTimer();
      memory.endedAt = currentPosition();
      save();
    },
    timeupdate: () => {
      currentPosition();
    }
  };

  const onVisibilityChange = () => {
    if (document.visibilityState === 'hidden') {
      save();
    }
  };

  const onPageHide = () => {
    if (memory.hasPlayed) {
      saver.requestOnUnload(snapshot());
    }
  };

  /** A fatal state destroys the hls.js instance (ADR-011 D7); the last position survives the detach. */
  const destroyHls = () => {
    if (hlsDestroyed) {
      return;
    }
    currentPosition();
    hlsDestroyed = true;
    stopSaveTimer();
    for (const [type, listener] of Object.entries(videoListeners)) {
      video.removeEventListener(type, listener);
    }
    authenticator.dispose();
    hls.destroy();
  };

  const fail = (status: PlayerStatus) => {
    destroyHls();
    options.setBuffering(false);
    options.setStatus(status);
  };

  /**
   * ADR-011 D7 (HLS 404) and D13 (progress 409 VIDEO_ASSET_NOT_CURRENT) share a refetch while it runs; a
   * later trigger fetches again, so it never acts on an answer that the server has since changed.
   */
  const checkCurrentAsset = (): Promise<AssetCheckResult> => {
    if (!assetCheck) {
      const check = fetchCurrentAsset(options.reloadPlaybackInfo, source.videoAssetId);
      assetCheck = check;
      void check.then(() => {
        if (assetCheck === check) {
          assetCheck = null;
        }
      });
    }
    return assetCheck;
  };

  /**
   * After an HLS 404 the stream is gone, so a failed refetch shows Retry. After a progress 409 the stream
   * still plays from its buffer, so only a new asset or a final "no longer available" changes the player.
   */
  const applyAssetCheck = (result: AssetCheckResult, trigger: 'hls' | 'progress') => {
    if (disposed || reloadRequested || result.kind === 'superseded') {
      return;
    }
    if (result.kind === 'newAsset') {
      reloadRequested = true;
      const startSeconds = startOnReplacement(currentPosition(), result.playbackInfo);
      memory.lastPosition = startSeconds;
      // Reaching the end of the old asset says nothing about the replacement.
      memory.endedAt = null;
      options.reload(startSeconds, result.playbackInfo);
      return;
    }
    if (result.kind === 'unavailable') {
      fail('unavailable');
      return;
    }
    if (trigger === 'hls') {
      fail('networkError');
    }
  };

  /** ADR-011 D2 restart within an unused budget: the same instance keeps the request budgets. */
  const restartLoading = () => {
    if (hls.levels.length > 0) {
      hls.startLoad(currentPosition());
    } else {
      hls.loadSource(source.manifestUrl);
    }
  };

  /**
   * One path for a fatal network error and for a load error that must stop at once (ADR-011 D2, D7 and the
   * 2026-11-22 addendum). Loading is already stopped; the decision may wait for a pending recovery.
   */
  const handleNetworkFailure = async (request: FailedRequest, networkDetails: unknown) => {
    const decision = await authenticator.resolveLoadFailure(request.url, request.status, networkDetails);
    if (disposed || hlsDestroyed) {
      return;
    }
    if (decision.kind === 'restartLoading') {
      networkFailureActive = false;
      restartLoading();
      return;
    }
    if (decision.kind === 'failed') {
      fail(statusForAuthenticationFailure(decision.failure));
      return;
    }
    if (request.status === 404) {
      destroyHls();
      applyAssetCheck(await checkCurrentAsset(), 'hls');
      return;
    }
    fail('networkError');
  };

  /**
   * hls.js 1.7.4 retries a fragment of a single-level stream after a 404 or a 401 on the retry although the
   * `shouldRetry` hook refused, so such an error stops loading here, before hls.js sends it again.
   */
  const stopsLoadingNow = (data: ErrorData): boolean => {
    if (data.type !== Hls.ErrorTypes.NETWORK_ERROR) {
      return false;
    }
    if (data.fatal) {
      return true;
    }
    const request = failedRequest(data);
    return LOAD_ERRORS.has(data.details)
      && authenticator.isTerminalLoadError(request.url, request.status, data.networkDetails);
  };

  /**
   * ADR-011 D7: one `recoverMediaError()` per hls.js instance, whoever calls it. hls.js 1.7.4 calls it itself
   * for an error whose action resets the media source, before any listener sees the error, so the limit is
   * enforced on the instance's method: a second call is refused and the player shows the playback error.
   */
  const recoverMediaErrorOnce = hls.recoverMediaError.bind(hls);
  hls.recoverMediaError = () => {
    if (mediaRecoveryUsed) {
      mediaRecoveryRefused = true;
      return;
    }
    mediaRecoveryUsed = true;
    // The detach resets the video to 0, so keep the position from before it.
    positionBeforeRecovery = currentPosition();
    mediaPositionKnown = false;
    recoverMediaErrorOnce();
  };

  /** A fatal media error: recover once, then restart loading where playback was. */
  const recoverFromMediaError = (data: ErrorData) => {
    const recoveredByLibrary = ((data.errorAction?.flags ?? 0) & RESET_MEDIA_SOURCE_FLAG) !== 0;
    if (!recoveredByLibrary) {
      if (mediaRecoveryUsed) {
        fail('playbackError');
        return;
      }
      hls.recoverMediaError();
    }
    // A fatal error stopped loading, and hls.js restarts a stopped load only on request.
    hls.startLoad(positionBeforeRecovery ?? memory.lastPosition);
  };

  const onHlsError = (_event: string, data: ErrorData) => {
    if (disposed || hlsDestroyed || networkFailureActive) {
      return;
    }
    if (mediaRecoveryRefused) {
      fail('playbackError');
      return;
    }
    if (stopsLoadingNow(data)) {
      networkFailureActive = true;
      // Synchronously, so that the retry hls.js has just scheduled is cancelled before it is sent.
      hls.stopLoad();
      void handleNetworkFailure(failedRequest(data), data.networkDetails);
      return;
    }
    // Other non-fatal errors are routine; hls.js handles them and the state does not change.
    if (!data.fatal) {
      return;
    }
    if (isIncompatibleMedia(data)) {
      fail('unsupported');
      return;
    }
    if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
      recoverFromMediaError(data);
      return;
    }
    fail('playbackError');
  };

  for (const [type, listener] of Object.entries(videoListeners)) {
    video.addEventListener(type, listener);
  }
  document.addEventListener('visibilitychange', onVisibilityChange);
  window.addEventListener('pagehide', onPageHide);
  options.assetNotCurrentHandler.current = (videoAssetId) => {
    // A late answer to a save of an earlier asset says nothing about the asset this run plays.
    if (videoAssetId !== source.videoAssetId) {
      return;
    }
    void checkCurrentAsset().then((result) => applyAssetCheck(result, 'progress'));
  };
  hls.on(Hls.Events.ERROR, onHlsError);
  hls.attachMedia(video);
  hls.loadSource(source.manifestUrl);

  return () => {
    save();
    disposed = true;
    options.assetNotCurrentHandler.current = () => undefined;
    document.removeEventListener('visibilitychange', onVisibilityChange);
    window.removeEventListener('pagehide', onPageHide);
    destroyHls();
  };
}

type AssetCheckResult =
  | { kind: 'newAsset'; playbackInfo: PlaybackInfo }
  | { kind: 'sameAsset' }
  | { kind: 'unavailable' }
  | { kind: 'failed' }
  | { kind: 'superseded' };

async function fetchCurrentAsset(
  reloadPlaybackInfo: () => Promise<PlaybackInfo>,
  playingAssetId: string
): Promise<AssetCheckResult> {
  try {
    const playbackInfo = await reloadPlaybackInfo();
    if (playbackInfo.videoAssetId === playingAssetId) {
      return { kind: 'sameAsset' };
    }
    return { kind: 'newAsset', playbackInfo };
  } catch (failure) {
    if (failure instanceof AuthenticationSupersededError) {
      return { kind: 'superseded' };
    }
    if (failure instanceof ApiRequestError && (failure.status === 404 || failure.status === 409)) {
      return { kind: 'unavailable' };
    }
    return { kind: 'failed' };
  }
}

/**
 * Where a replacement asset starts: the current position while it lies inside the new duration, otherwise
 * the server's resume position, which ADR-011 D11 normalizes for a shorter replacement.
 */
function startOnReplacement(position: number, playbackInfo: PlaybackInfo): number {
  const duration = playbackInfo.durationSeconds;
  if (duration !== null && position >= duration) {
    return playbackInfo.resumeSeconds;
  }
  return position;
}

/** hls.js load errors that carry the HTTP status of a playlist or fragment request. */
const LOAD_ERRORS = new Set<string>([
  Hls.ErrorDetails.MANIFEST_LOAD_ERROR,
  Hls.ErrorDetails.LEVEL_LOAD_ERROR,
  Hls.ErrorDetails.AUDIO_TRACK_LOAD_ERROR,
  Hls.ErrorDetails.SUBTITLE_LOAD_ERROR,
  Hls.ErrorDetails.FRAG_LOAD_ERROR
]);

function createHlsConfig(authenticator: HlsRequestAuthenticator, startSeconds: number): Partial<HlsConfig> {
  return {
    // -1 is the hls.js default: a VOD playlist starts at its beginning.
    startPosition: startSeconds > 0 ? startSeconds : -1,
    // hls.js calls xhrSetup with its loader as `this`, which tells whether it gave the attempt up.
    xhrSetup(this: unknown, xhr: XMLHttpRequest, url: string) {
      return authenticator.setUpRequest(xhr, url, this);
    },
    manifestLoadPolicy: authenticator.withAuthenticationRetry(Hls.DefaultConfig.manifestLoadPolicy),
    playlistLoadPolicy: authenticator.withAuthenticationRetry(Hls.DefaultConfig.playlistLoadPolicy),
    fragLoadPolicy: authenticator.withAuthenticationRetry(Hls.DefaultConfig.fragLoadPolicy)
  };
}

function isIncompatibleMedia(data: ErrorData): boolean {
  return data.details === Hls.ErrorDetails.MANIFEST_INCOMPATIBLE_CODECS_ERROR
    || data.details === Hls.ErrorDetails.BUFFER_INCOMPATIBLE_CODECS_ERROR
    || data.details === Hls.ErrorDetails.BUFFER_ADD_CODEC_ERROR;
}

function statusForAuthenticationFailure(failure: HlsAuthenticationFailure): PlayerStatus {
  if (failure === 'sessionEnded') {
    return 'sessionEnded';
  }
  if (failure === 'superseded') {
    return 'accountChanged';
  }
  return 'networkError';
}
