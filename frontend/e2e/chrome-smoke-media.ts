import type { Locator, Page } from '@playwright/test';

type Sample = { media: number; wall: number; frames: number };
export type PlaybackFault = number | 'media source detached';
type PlaybackMonitor = {
  seekAllowed: boolean;
  unexpectedSeek: boolean;
  unexpectedPlay: boolean;
  startKey: boolean;
  pausedAtKey: boolean;
  samples: Sample[];
};

declare global {
  interface Window {
    chromeSmokePlayback: PlaybackMonitor;
    captureChromeSmokePlaybackFault: (fault: PlaybackFault) => Promise<void>;
  }
}

export async function installPlaybackMonitor(page: Page, faults: PlaybackFault[]): Promise<void> {
  await page.exposeFunction('captureChromeSmokePlaybackFault', (fault: PlaybackFault) => faults.push(fault));
  await page.addInitScript(() => {
    const state: PlaybackMonitor = {
      seekAllowed: false, unexpectedSeek: false, unexpectedPlay: false, startKey: false, pausedAtKey: false, samples: []
    };
    window.chromeSmokePlayback = state;
    document.addEventListener('keydown', event => {
      if (event.isTrusted && event.code === 'Space' && event.target instanceof HTMLVideoElement && !state.startKey) {
        state.startKey = true;
        state.pausedAtKey = event.target.paused;
      }
    }, true);
    document.addEventListener('play', event => {
      if (event.target instanceof HTMLVideoElement && !state.startKey) {
        state.unexpectedPlay = true;
      }
    }, true);
    document.addEventListener('seeking', event => {
      if (event.target instanceof HTMLVideoElement && !state.seekAllowed) {
        state.unexpectedSeek = true;
      }
    }, true);
    document.addEventListener('error', event => {
      if (event.target instanceof HTMLVideoElement) {
        void window.captureChromeSmokePlaybackFault(event.target.error?.code ?? 0);
      }
    }, true);
    // hls.js can recover an internal error by resetting MSE before video.error is set.
    new MutationObserver(records => {
      const detached = records.some(record => record.target instanceof HTMLVideoElement
        && (record.oldValue?.startsWith('blob:') || Array.from(record.removedNodes).some(node =>
          node instanceof HTMLSourceElement && node.src.startsWith('blob:'))));
      if (detached) {
        void window.captureChromeSmokePlaybackFault('media source detached');
      }
    }).observe(document, {
      attributes: true, attributeFilter: ['src'], attributeOldValue: true, childList: true, subtree: true
    });
    const sample = () => {
      const video = document.querySelector('video');
      if (!video || !state.startKey || state.seekAllowed) return;
      const quality = video.getVideoPlaybackQuality();
      state.samples.push({ media: video.currentTime, wall: performance.now(),
        frames: quality.totalVideoFrames - quality.droppedVideoFrames });
    };
    document.addEventListener('playing', sample, true);
    setInterval(sample, 3_000);
  });
}

export function media(video: Locator) {
  return video.evaluate((element: HTMLVideoElement & { webkitAudioDecodedByteCount?: number }) => {
    const quality = element.getVideoPlaybackQuality();
    return {
      seconds: element.currentTime,
      frames: quality.totalVideoFrames - quality.droppedVideoFrames,
      audioBytes: element.webkitAudioDecodedByteCount ?? 0,
      audible: !element.muted && element.volume > 0,
      paused: element.paused,
      seeking: element.seeking,
      rate: element.playbackRate,
      error: element.error?.code ?? null,
      ready: element.readyState,
      duration: element.duration
    };
  });
}

export function sampleCadenceIsNormal(samples: Sample[], framesPerSecond: number): boolean {
  return samples.every((sample, index) => {
    if (index === 0) return true;
    const previous = samples[index - 1];
    const wall = (sample.wall - previous.wall) / 1000;
    if (wall < 1) return true;
    const mediaSeconds = sample.media - previous.media;
    const frames = sample.frames - previous.frames;
    return mediaSeconds / wall > 0.75 && mediaSeconds / wall < 1.15
      && frames >= mediaSeconds * framesPerSecond * 0.7;
  });
}
