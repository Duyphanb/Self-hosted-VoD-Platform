import { describe, expect, it, vi } from 'vitest';
import { ApiRequestError, AuthenticationSupersededError } from '../../lib/api/client';
import type { ProgressSave } from './playbackApi';
import { ProgressSaver } from './progressSaver';

const ASSET_ID = 'b1f4c6a8-2d3e-4f50-8a9b-0c1d2e3f4a5b';
const OTHER_ASSET_ID = 'c2a5d7b9-3e4f-4a61-9bac-1d2e3f4a5b6c';

describe('ProgressSaver', () => {
  describe('deduplication', () => {
    it('skips a save whose whole payload equals the last successful save', async () => {
      const harness = createHarness();

      harness.saver.request(position(12));
      await harness.completeSave();
      harness.saver.request(position(12));

      expect(harness.save).toHaveBeenCalledTimes(1);
    });

    it('sends the finished save even when the position equals the last successful save', async () => {
      const harness = createHarness();

      harness.saver.request(position(95));
      await harness.completeSave();
      harness.saver.request(position(95, true));

      expect(harness.save).toHaveBeenCalledTimes(2);
      expect(harness.save).toHaveBeenLastCalledWith(position(95, true));
    });

    it('sends the same position again after a failed save', async () => {
      const harness = createHarness();

      harness.saver.request(position(30));
      await harness.failSave(new TypeError('Failed to fetch'));
      harness.saver.request(position(30));

      expect(harness.save).toHaveBeenCalledTimes(2);
    });
  });

  describe('one save in flight', () => {
    it('keeps only the newest pending snapshot and sends it after the running save', async () => {
      const harness = createHarness();

      harness.saver.request(position(10));
      harness.saver.request(position(20));
      harness.saver.request(position(30));
      expect(harness.save).toHaveBeenCalledTimes(1);

      await harness.completeSave();

      expect(harness.save).toHaveBeenCalledTimes(2);
      expect(harness.save).toHaveBeenLastCalledWith(position(30));
    });

    it('sends the pending snapshot after the running save failed', async () => {
      const harness = createHarness();

      harness.saver.request(position(10));
      harness.saver.request(position(20));
      await harness.failSave(new ApiRequestError(503, null));

      expect(harness.save).toHaveBeenLastCalledWith(position(20));
    });
  });

  describe('stopping', () => {
    it.each([
      ['404 MOVIE_NOT_FOUND', 404, 'MOVIE_NOT_FOUND'],
      ['409 VIDEO_NOT_READY', 409, 'VIDEO_NOT_READY']
    ])('stops saving the asset after %s', async (_name, status, code) => {
      const harness = createHarness();

      harness.saver.request(position(10));
      harness.saver.request(position(20));
      await harness.failSave(new ApiRequestError(status, apiError(status, code)));
      harness.saver.request(position(30));

      expect(harness.save).toHaveBeenCalledTimes(1);
      expect(harness.onAssetNotCurrent).not.toHaveBeenCalled();
    });

    it('keeps saving after a 401, which may answer an older token of a valid session', async () => {
      const harness = createHarness();

      harness.saver.request(position(10));
      await harness.failSave(new ApiRequestError(401, apiError(401, 'UNAUTHORIZED')));
      harness.saver.request(position(20));

      expect(harness.save).toHaveBeenCalledTimes(2);
      expect(harness.save).toHaveBeenLastCalledWith(position(20));
    });

    it('accepts saves for a new asset after the old asset stopped', async () => {
      const harness = createHarness();
      const newAssetSave = { ...position(30), videoAssetId: OTHER_ASSET_ID };

      harness.saver.request(position(10));
      await harness.failSave(new ApiRequestError(409, apiError(409, 'VIDEO_ASSET_NOT_CURRENT')));
      harness.saver.request(position(20));
      harness.saver.request(newAssetSave);

      expect(harness.save).toHaveBeenCalledTimes(2);
      expect(harness.save).toHaveBeenLastCalledWith(newAssetSave);
    });

    it('stops saving and asks for the playback info after 409 VIDEO_ASSET_NOT_CURRENT', async () => {
      const harness = createHarness();

      harness.saver.request(position(10));
      await harness.failSave(new ApiRequestError(409, apiError(409, 'VIDEO_ASSET_NOT_CURRENT')));
      harness.saver.request(position(20));

      expect(harness.onAssetNotCurrent).toHaveBeenCalledTimes(1);
      expect(harness.save).toHaveBeenCalledTimes(1);
    });

    it('stops saving when the signed-in identity changed', async () => {
      const harness = createHarness();

      harness.saver.request(position(10));
      await harness.failSave(new AuthenticationSupersededError());
      harness.saver.request(position(20));
      harness.saver.requestOnUnload(position(25));

      expect(harness.save).toHaveBeenCalledTimes(1);
      expect(harness.saveOnUnload).not.toHaveBeenCalled();
    });
  });

  describe('unload saves', () => {
    it('sends the latest snapshot with keepalive even while a regular save is in flight', () => {
      const harness = createHarness();

      harness.saver.request(position(10));
      harness.saver.requestOnUnload(position(14));

      expect(harness.saveOnUnload).toHaveBeenCalledWith(position(14));
    });

    it('skips an unload save that equals the last successful save', async () => {
      const harness = createHarness();

      harness.saver.request(position(10));
      await harness.completeSave();
      harness.saver.requestOnUnload(position(10));

      expect(harness.saveOnUnload).not.toHaveBeenCalled();
    });
  });
});

interface Harness {
  saver: ProgressSaver;
  save: ReturnType<typeof vi.fn<(snapshot: ProgressSave) => Promise<void>>>;
  saveOnUnload: ReturnType<typeof vi.fn<(snapshot: ProgressSave) => void>>;
  onAssetNotCurrent: ReturnType<typeof vi.fn<() => void>>;
  /** Resolve the oldest running save and let the saver continue. */
  completeSave(): Promise<void>;
  /** Reject the oldest running save and let the saver continue. */
  failSave(failure: unknown): Promise<void>;
}

function createHarness(): Harness {
  const running: Array<{ resolve: () => void; reject: (failure: unknown) => void }> = [];
  const save = vi.fn<(snapshot: ProgressSave) => Promise<void>>(() => new Promise<void>((resolve, reject) => {
    running.push({ resolve, reject });
  }));
  const saveOnUnload = vi.fn<(snapshot: ProgressSave) => void>();
  const onAssetNotCurrent = vi.fn<() => void>();
  const saver = new ProgressSaver({ save, saveOnUnload, onAssetNotCurrent });

  const settleOldest = async (settle: (entry: (typeof running)[number]) => void) => {
    const entry = running.shift();
    if (!entry) {
      throw new Error('No save is running');
    }
    settle(entry);
    await flushPromises();
  };

  return {
    saver,
    save,
    saveOnUnload,
    onAssetNotCurrent,
    completeSave: () => settleOldest((entry) => entry.resolve()),
    failSave: (failure) => settleOldest((entry) => entry.reject(failure))
  };
}

function position(currentSeconds: number, finished = false): ProgressSave {
  return { videoAssetId: ASSET_ID, currentSeconds, finished };
}

function apiError(status: number, code: string): object {
  return { timestamp: '2026-11-22T00:00:00Z', status, code, message: 'Fixture' };
}

async function flushPromises(): Promise<void> {
  for (let step = 0; step < 5; step += 1) {
    await Promise.resolve();
  }
}
