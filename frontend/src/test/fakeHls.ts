import type RealHls from 'hls.js';
import type { ErrorData, HlsConfig } from 'hls.js';

type ErrorListener = (event: string, data: ErrorData) => void;

/**
 * A controllable stand-in for the hls.js default export. Tests read the config the player installs, emit
 * errors and inspect the calls the player makes; the real constants and default config are copied from
 * hls.js by `adopt`.
 */
export class FakeHls {
  static instances: FakeHls[] = [];
  static supported = true;
  static Events: typeof RealHls.Events;
  static ErrorTypes: typeof RealHls.ErrorTypes;
  static ErrorDetails: typeof RealHls.ErrorDetails;
  static DefaultConfig: typeof RealHls.DefaultConfig;

  readonly config: Partial<HlsConfig>;
  media: HTMLVideoElement | null = null;
  readonly loadedSources: string[] = [];
  readonly startLoadPositions: number[] = [];
  levels: unknown[] = [];
  stopLoadCount = 0;
  recoverMediaErrorCount = 0;
  destroyed = false;
  private readonly errorListeners: ErrorListener[] = [];

  constructor(config: Partial<HlsConfig> = {}) {
    this.config = config;
    FakeHls.instances.push(this);
  }

  static adopt(real: typeof RealHls): void {
    FakeHls.Events = real.Events;
    FakeHls.ErrorTypes = real.ErrorTypes;
    FakeHls.ErrorDetails = real.ErrorDetails;
    FakeHls.DefaultConfig = real.DefaultConfig;
  }

  static reset(): void {
    FakeHls.instances = [];
    FakeHls.supported = true;
  }

  static isSupported(): boolean {
    return FakeHls.supported;
  }

  static latest(): FakeHls {
    const instance = FakeHls.instances.at(-1);
    if (!instance) {
      throw new Error('No hls.js instance was created');
    }
    return instance;
  }

  on(event: string, listener: ErrorListener): void {
    if (event === FakeHls.Events.ERROR) {
      this.errorListeners.push(listener);
    }
  }

  attachMedia(media: HTMLVideoElement): void {
    this.media = media;
  }

  loadSource(url: string): void {
    this.loadedSources.push(url);
  }

  startLoad(position: number): void {
    this.startLoadPositions.push(position);
  }

  stopLoad(): void {
    this.stopLoadCount += 1;
  }

  recoverMediaError(): void {
    this.recoverMediaErrorCount += 1;
  }

  destroy(): void {
    this.destroyed = true;
    this.media = null;
  }

  emitError(data: Partial<ErrorData>): void {
    for (const listener of [...this.errorListeners]) {
      listener(FakeHls.Events.ERROR, data as ErrorData);
    }
  }
}
