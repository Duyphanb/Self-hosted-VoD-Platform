import { ApiRequestError, AuthenticationSupersededError } from '../../lib/api/client';
import type { ProgressSave } from './playbackApi';

export interface ProgressSaverOptions {
  /** A regular save through the shared ApiClient recovery. */
  save(snapshot: ProgressSave): Promise<void>;
  /** A best-effort `keepalive` save at page unload. */
  saveOnUnload(snapshot: ProgressSave): void;
  /** The server answered 409 VIDEO_ASSET_NOT_CURRENT: the page must fetch the playback info again. */
  onAssetNotCurrent(): void;
}

/**
 * Progress saves of one player for one video asset (ADR-011 D13).
 *
 * - A save is skipped only when its whole payload equals the last successful save.
 * - At most one save is in flight. A save requested meanwhile replaces one pending snapshot, which is
 *   sent when the running save completes, so a slow save never overwrites a newer position.
 * - A failed save is not retried by itself; the next trigger sends the newer position.
 * - 404, 409 and a changed identity stop all further saves; 409 VIDEO_ASSET_NOT_CURRENT also asks the
 *   page to fetch the playback info again.
 */
export class ProgressSaver {
  private lastSaved: ProgressSave | null = null;
  private inFlight = false;
  private pending: ProgressSave | null = null;
  private stopped = false;

  constructor(private readonly options: ProgressSaverOptions) {}

  /** A regular save trigger: the 10-second tick, pause, seek, `ended`, page hide or unmount. */
  request(snapshot: ProgressSave): void {
    if (this.stopped) {
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
    if (this.stopped) {
      return;
    }
    // A running save may still fail or never arrive, so only an idle saver can skip an equal payload.
    if (!this.inFlight && sameSave(snapshot, this.lastSaved)) {
      return;
    }
    this.options.saveOnUnload(snapshot);
  }

  /** Stop saving, for example when the player is replaced by one for another asset. */
  stop(): void {
    this.stopped = true;
    this.pending = null;
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
      this.handleFailure(failure);
    } finally {
      this.inFlight = false;
    }

    const next = this.pending;
    this.pending = null;
    if (next && !this.stopped) {
      void this.send(next);
    }
  }

  private handleFailure(failure: unknown): void {
    if (failure instanceof AuthenticationSupersededError) {
      this.stop();
      return;
    }
    if (!(failure instanceof ApiRequestError)) {
      return;
    }
    if (failure.status === 401 || failure.status === 404 || failure.status === 409) {
      this.stop();
    }
    if (failure.status === 409 && failure.apiError?.code === 'VIDEO_ASSET_NOT_CURRENT') {
      this.options.onAssetNotCurrent();
    }
  }
}

function sameSave(left: ProgressSave, right: ProgressSave | null): boolean {
  return right !== null
    && left.videoAssetId === right.videoAssetId
    && left.currentSeconds === right.currentSeconds
    && left.finished === right.finished;
}
