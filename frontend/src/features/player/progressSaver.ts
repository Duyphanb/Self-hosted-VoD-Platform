import { ApiRequestError, AuthenticationSupersededError } from '../../lib/api/client';
import type { ProgressSave } from './playbackApi';

export interface ProgressSaverOptions {
  /** A regular save through the shared ApiClient recovery, bound to the player's identity. */
  save(snapshot: ProgressSave): Promise<void>;
  /** A best-effort `keepalive` save at page unload. */
  saveOnUnload(snapshot: ProgressSave): void;
  /** A save of `videoAssetId` answered 409 VIDEO_ASSET_NOT_CURRENT: fetch the playback info again. */
  onAssetNotCurrent(videoAssetId: string): void;
}

/**
 * Progress saves of one player (ADR-011 D13), across all its loads and assets.
 *
 * - A save is skipped only when its whole payload equals the last successful save.
 * - At most one save is in flight. A save requested meanwhile replaces one pending snapshot, which is
 *   sent when the running save completes, so a slow save never overwrites a newer position, also when
 *   the player reloads.
 * - A failed save is not retried by itself; the next trigger sends the newer position. A 401 is such a
 *   failure: it may answer an older token while the session is still valid.
 * - 404 and 409 stop the saves of that asset; 409 VIDEO_ASSET_NOT_CURRENT also asks the page to fetch the
 *   playback info again, and saves for the new asset are accepted.
 * - A changed identity stops every save for good.
 * - `whenIdle` tells when the running save and the pending snapshot are both done, for example so that
 *   reopening the movie reads the position of the last save.
 */
export class ProgressSaver {
  private lastSaved: ProgressSave | null = null;
  private inFlight = false;
  private pending: ProgressSave | null = null;
  private stopped = false;
  private readonly stoppedAssets = new Set<string>();
  private idleWaiters: Array<() => void> = [];

  constructor(private readonly options: ProgressSaverOptions) {}

  /** A regular save trigger: the 10-second tick, pause, seek, `ended`, page hide or unmount. */
  request(snapshot: ProgressSave): void {
    if (!this.accepts(snapshot)) {
      return;
    }
    if (this.inFlight) {
      this.pending = snapshot;
      return;
    }
    void this.send(snapshot);
  }

  /** `pagehide`: sent at once with `keepalive`, even while a regular save is in flight. */
  requestOnUnload(snapshot: ProgressSave): void {
    if (!this.accepts(snapshot)) {
      return;
    }
    // A running save may still fail or never arrive, so only an idle saver can skip an equal payload.
    if (!this.inFlight && sameSave(snapshot, this.lastSaved)) {
      return;
    }
    this.options.saveOnUnload(snapshot);
  }

  /** Stop every save for good, for example when the signed-in identity changed. */
  stop(): void {
    this.stopped = true;
    this.pending = null;
  }

  /** Resolves once no save is running or pending, whether the saves succeed or fail. */
  whenIdle(): Promise<void> {
    if (!this.inFlight) {
      return Promise.resolve();
    }
    return new Promise<void>((resolve) => {
      this.idleWaiters.push(resolve);
    });
  }

  private accepts(snapshot: ProgressSave): boolean {
    return !this.stopped && !this.stoppedAssets.has(snapshot.videoAssetId);
  }

  private async send(snapshot: ProgressSave): Promise<void> {
    if (sameSave(snapshot, this.lastSaved)) {
      return;
    }
    this.inFlight = true;
    try {
      await this.options.save(snapshot);
      this.lastSaved = snapshot;
    } catch (failure) {
      this.handleFailure(failure, snapshot);
    } finally {
      this.inFlight = false;
    }

    const next = this.pending;
    this.pending = null;
    if (next && this.accepts(next) && !sameSave(next, this.lastSaved)) {
      void this.send(next);
      return;
    }
    this.notifyIdle();
  }

  private notifyIdle(): void {
    const waiters = this.idleWaiters;
    this.idleWaiters = [];
    for (const resolve of waiters) {
      resolve();
    }
  }

  private handleFailure(failure: unknown, snapshot: ProgressSave): void {
    if (failure instanceof AuthenticationSupersededError) {
      this.stop();
      return;
    }
    if (!(failure instanceof ApiRequestError)) {
      return;
    }
    if (failure.status === 404 || failure.status === 409) {
      this.stoppedAssets.add(snapshot.videoAssetId);
    }
    if (failure.status === 409 && failure.apiError?.code === 'VIDEO_ASSET_NOT_CURRENT') {
      this.options.onAssetNotCurrent(snapshot.videoAssetId);
    }
  }
}

function sameSave(left: ProgressSave, right: ProgressSave | null): boolean {
  return right !== null
    && left.videoAssetId === right.videoAssetId
    && left.currentSeconds === right.currentSeconds
    && left.finished === right.finished;
}
