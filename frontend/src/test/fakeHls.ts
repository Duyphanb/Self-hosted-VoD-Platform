import type RealHls from 'hls.js';
import type { ErrorData, HlsConfig, LoaderResponse, RetryConfig } from 'hls.js';

type ErrorListener = (event: string, data: ErrorData) => void;
type PolicyName = 'manifestLoadPolicy' | 'playlistLoadPolicy' | 'fragLoadPolicy';

/**
 * A controllable stand-in for the hls.js default export. Tests drive the hooks the player installs
 * (`xhrSetup`, the load-policy `shouldRetry`) and emit errors; the real constants and default config are
 * copied from hls.js by `adopt`.
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

  /** Run the player's `xhrSetup` for `url`, as hls.js does before sending a request. */
  async setUpRequest(xhr: XMLHttpRequest, url: string): Promise<void> {
    const xhrSetup = this.config.xhrSetup;
    if (!xhrSetup) {
      throw new Error('The player installed no xhrSetup');
    }
    await xhrSetup(xhr, url, { url, responseType: 'text' } as never);
  }

  /** Ask the error-retry hook of `policy`, as hls.js does after a failed request. */
  shouldRetry(policy: PolicyName, response: LoaderResponse, defaultRetry = false): boolean {
    const errorRetry: RetryConfig | null | undefined = this.config[policy]?.default.errorRetry;
    if (!errorRetry?.shouldRetry) {
      throw new Error(`The player installed no shouldRetry hook on ${policy}`);
    }
    return errorRetry.shouldRetry(errorRetry, 0, false, response, defaultRetry);
  }

  emitError(data: Partial<ErrorData>): void {
    for (const listener of [...this.errorListeners]) {
      listener(FakeHls.Events.ERROR, data as ErrorData);
    }
  }
}
