import { expect, test, type CDPSession, type Locator } from '@playwright/test';
import {
  audioGrowsThroughout, installPlaybackMonitor, media, sampleCadenceIsNormal, type PlaybackFault
} from './chrome-smoke-media';
import {
  hasBearer, issuedToken, loginRoute, recovery, refreshRoute, segmentInterval, tokenTimes, watchTraffic
} from './chrome-smoke-traffic';

const codec = 'video/mp4; codecs="avc1.64001E, mp4a.40.2"';
const accessTtl = 60_000;
const clockSkew = 5_000;
const saveCadence = 10_000;
const expiryMargin = 1_000;
const segmentLatency = 4_500;
const refreshLatency = 6_000;
const lateProgressLatency = 18_000;
const fixtureFrameRate = 24;
// Seek back into the buffered range: playback alone never moves backwards, and the save stays far below 90
// percent whichever segment the expiry hit (the recovered segment and the seek position vary with the start phase).
const seekBackSeconds = 30;
const seekedTimeout = 10_000;
// Saves carry whole seconds; the first pause of the pause/resume step is more than two seconds earlier.
const pauseSaveTolerance = 1.5;
// The pause/resume step plays about three seconds after the seek; anything near seekBackSeconds is a stall.
const maxPlayAfterSeekSeconds = 10;

function fixtureId(name: string): string {
  const value = process.env[name] ?? '';
  if (!/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(value)) {
    throw new Error('Invalid disposable fixture ID');
  }
  return value;
}

function report(data: object): void {
  console.log('CHROME_SMOKE ' + JSON.stringify(data));
}

async function checkpoint<T>(label: string, action: () => Promise<T>): Promise<T> {
  try {
    return await test.step(label, action);
  } catch (error) {
    report({ failed: label });
    throw error;
  }
}

function waitUntil(at: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, Math.max(0, at - Date.now())));
}

function shapeTraffic(cdp: CDPSession, rules: [string, number][]) {
  return cdp.send('Network.emulateNetworkConditionsByRule', {
    offline: false,
    matchedNetworkConditions: rules.map(([urlPattern, latency]) => ({
      urlPattern, latency, downloadThroughput: -1, uploadThroughput: -1
    }))
  });
}

async function playing(video: Locator): Promise<void> {
  await video.press('Space');
  await expect.poll(async () => (await media(video)).paused).toBe(false);
}

test('Chrome plays the uploaded asset and recovers expired HLS playback', async ({ page, browser, baseURL }) => {
  const origin = baseURL!;
  const movie = fixtureId('VOD_CHROME_MOVIE_ID');
  const episode = fixtureId('VOD_CHROME_EPISODE_ID');
  const asset = fixtureId('VOD_CHROME_ASSET_ID');
  const watch = `/watch/${episode}`;
  const playbackRoute = `/api/v1/episodes/${episode}/playback`;
  const progressRoute = `/api/v1/episodes/${episode}/progress`;
  const hlsPrefix = `/hls/video-assets/${asset}/`;
  const traffic = watchTraffic(page, origin, hlsPrefix, [loginRoute, progressRoute, playbackRoute]);
  const video = page.locator('video');
  const mediaErrors: PlaybackFault[] = [];
  const segments: [string, number] = [origin + hlsPrefix + 'segments/*', segmentLatency];
  report({ chrome: browser.version() });

  await checkpoint('Chrome supports H.264 and AAC', async () => {
    const supported = await page.evaluate(value => MediaSource.isTypeSupported(value)
      && document.createElement('video').canPlayType(value) !== '', codec);
    expect(supported).toBe(true);
  });

  const cdp = await checkpoint('Install playback observers and shape segment traffic', async () => {
    await installPlaybackMonitor(page, mediaErrors);
    const session = await page.context().newCDPSession(page);
    const cpuRate = Number(process.env.VOD_CHROME_TEST_CPU_RATE ?? 1);
    if (!Number.isFinite(cpuRate) || cpuRate < 1 || cpuRate > 8) {
      throw new Error('Invalid Chrome CPU throttling rate');
    }
    if (cpuRate > 1) {
      await session.send('Emulation.setCPUThrottlingRate', { rate: cpuRate });
    }
    await shapeTraffic(session, [segments]);
    return session;
  });

  const originalToken = await checkpoint('Real UI login and uploaded asset start paused', async () => {
    await page.goto('/login');
    await page.getByLabel('Email', { exact: true }).fill(process.env.VOD_CHROME_EMAIL ?? '');
    await page.getByLabel('Password', { exact: true }).fill(process.env.VOD_CHROME_PASSWORD ?? '');
    const loginResponse = page.waitForResponse(response => new URL(response.url()).pathname === loginRoute
      && response.request().method() === 'POST');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    const login = await loginResponse;
    expect(new URL(login.url()).origin === origin).toBe(true);
    const token = await issuedToken(login);
    if (!token) {
      throw new Error('Missing fixture login credential');
    }
    await expect(page.getByRole('button', { name: 'Sign out', exact: true })).toBeVisible();
    await page.getByRole('link', { name: 'Chrome playback smoke' }).first().click();
    await expect(page.getByRole('link', { name: 'Watch', exact: true })).toHaveAttribute('href', watch);
    await page.goto(`/movies/${movie}/watch`);
    await expect(page).toHaveURL(origin + watch);
    await expect.poll(async () => (await media(video)).ready, { timeout: 22_000 }).toBeGreaterThanOrEqual(2);
    expect(traffic.current().some(exchange => exchange.route === hlsPrefix + 'master.m3u8'
      && exchange.response?.status() === 200)).toBe(true);
    const paused = await media(video);
    expect(paused.paused).toBe(true);
    expect(paused.error).toBeNull();
    expect(Math.abs(paused.duration - 120)).toBeLessThanOrEqual(2);
    return token;
  });

  const alignment = await checkpoint('User starts native playback', async () => {
    const { issued, expires } = tokenTimes(originalToken);
    const expectedExpiration = Math.min(expires, issued + accessTtl) + clockSkew;
    // Aim the native start near a useful phase; actual traffic shaping uses the observed playing event.
    await video.focus();
    await shapeTraffic(cdp, [segments, [origin + refreshRoute, refreshLatency]]);
    const phase = expectedExpiration + expiryMargin - Date.now();
    await waitUntil(Date.now() + Math.max(0, phase % saveCadence));
    const start = await media(video);
    const started = performance.now();
    expect(start.paused).toBe(true);
    await page.keyboard.press('Space');
    await expect.poll(async () => (await media(video)).paused).toBe(false);
    const monitor = await page.evaluate(() => window.chromeSmokePlayback);
    expect(monitor.startKey && monitor.pausedAtKey && !monitor.unexpectedPlay).toBe(true);
    return { start, started, issued, expires, expectedExpiration };
  });

  const { start, started, issued, expires, expectedExpiration } = alignment;
  const originalDocument = traffic.document();
  const loginCompleted = traffic.current().find(exchange => exchange.route === loginRoute)?.completed ?? 0;
  const originalExchanges = () => traffic.current(originalDocument)
    .filter(exchange => exchange.started > loginCompleted);
  const refreshCount = () => originalExchanges().filter(exchange => exchange.route === refreshRoute).length;
  await checkpoint('Phase the progress delay from the playing event', async () => {
    const playingAt = () => page.evaluate(() => window.chromeSmokePlayback.playingAt);
    await expect.poll(playingAt).not.toBeNull();
    const firstPlaying = (await playingAt())!;
    const ticksBeforeExpiry = Math.floor((expectedExpiration - firstPlaying) / saveCadence);
    const expectedOldBearerTick = firstPlaying + (ticksBeforeExpiry + 1) * saveCadence;
    // Shape between the last valid tick and the first expired tick.
    await waitUntil(expectedOldBearerTick - saveCadence / 2);
    expect(Date.now() < expectedOldBearerTick).toBe(true);
    await shapeTraffic(cdp, [segments, [origin + progressRoute, lateProgressLatency],
      [origin + refreshRoute, refreshLatency]]);
  });
  const recovered = await checkpoint('HLS 401 then one refresh then 200 on the same resource', async () => {
    await expect.poll(() => originalExchanges().some(exchange => exchange.route === refreshRoute
      && exchange.response?.status() === 200), { timeout: 28_000 }).toBe(true);
    const firstRefresh = originalExchanges().find(exchange => exchange.route === refreshRoute);
    const hlsDenialBeforeRefresh = () => originalExchanges().some(exchange => exchange.route.startsWith(hlsPrefix)
      && exchange.response?.status() === 401 && exchange.completed < (firstRefresh?.started ?? 0));
    expect(hlsDenialBeforeRefresh()).toBe(true);
    await shapeTraffic(cdp, []);
    const result = () => recovery(originalExchanges(), hlsPrefix, progressRoute);
    await expect.poll(() => result() !== null, { timeout: 32_000 }).toBe(true);
    const { hlsDenied, progressDenied, refresh, hlsRetry, progressRetry } = result()!;
    expect(refreshCount()).toBe(1);
    expect(expires - issued).toBe(accessTtl);
    await checkpoint('Rejected HLS request started after expiry and skew', async () => {
      expect(hlsDenied.sentAt >= expires + clockSkew).toBe(true);
    });
    expect(hlsDenied.completed < refresh.started).toBe(true);
    expect(progressDenied.started < refresh.completed && progressDenied.completed > refresh.completed).toBe(true);
    const fresh = await issuedToken(refresh.response);
    if (!fresh || fresh === originalToken) {
      throw new Error('Recovery did not issue a distinct credential');
    }
    expect(await hasBearer(hlsDenied, originalToken) && await hasBearer(progressDenied, originalToken)).toBe(true);
    await checkpoint('New bearer was sent before the late progress 401', async () => {
      expect(await hasBearer(hlsRetry, fresh)).toBe(true);
      expect(hlsRetry.started < progressDenied.completed && hlsRetry.sentAt < progressDenied.at).toBe(true);
    });
    expect(await hasBearer(progressRetry, fresh)).toBe(true);
    const sequence = [hlsDenied, refresh, hlsRetry, progressDenied, progressRetry]
      .sort((first, second) => first.completed - second.completed);
    for (const event of sequence) {
      report({ route: event.route, status: event.response?.status(), sentAt: event.sentAt, at: event.at });
    }
    return { refreshedBearer: fresh, hlsDenied, hlsRetry };
  });

  const { refreshedBearer, hlsDenied, hlsRetry } = recovered;
  const requestedAgain = () => originalExchanges().some(exchange =>
    exchange.route === hlsRetry.route && exchange.started > hlsRetry.started);
  const interval = await checkpoint('Find the recovered segment in the real media playlist', async () => {
    const playlist = originalExchanges().find(exchange => exchange.route === hlsPrefix + 'master.m3u8'
      && exchange.response?.status() === 200)?.response;
    if (!playlist) {
      throw new Error('Missing fixture media playlist response');
    }
    const range = await segmentInterval(playlist, hlsDenied);
    expect(start.seconds).toBeLessThan(range.start);
    expect(originalExchanges().some(exchange => exchange.route === hlsDenied.route
      && exchange.started < hlsDenied.started && exchange.response?.status() === 200)).toBe(false);
    return range;
  });

  await checkpoint('Recovered segment plays through without another request', async () => {
    const target = Math.max(start.seconds + 60, interval.end + 0.25);
    await expect.poll(async () => requestedAgain() || (await media(video)).seconds >= target, { timeout: 55_000 })
      .toBe(true);
    expect(requestedAgain()).toBe(false);
    const monitor = await page.evaluate(() => window.chromeSmokePlayback);
    expect(monitor.unexpectedSeek).toBe(false);
    const before = monitor.samples.filter(sample => sample.media <= interval.start).at(-1);
    const within = monitor.samples.find(sample => sample.media > interval.start && sample.media < interval.end);
    expect(Boolean(before && within && within.frames > before.frames)).toBe(true);
    expect((await media(video)).frames).toBeGreaterThan(within!.frames);
  });

  await checkpoint('At least 60 seconds of media at normal rate before seeking', async () => {
    const end = await media(video);
    const seconds = end.seconds - start.seconds;
    const wall = (performance.now() - started) / 1000;
    const monitor = await page.evaluate(() => window.chromeSmokePlayback);
    report({ mediaSeconds: seconds, wallSeconds: wall, framesStart: start.frames, framesEnd: end.frames,
      mediaError: end.error, audioDecoded: end.audioBytes > start.audioBytes,
      recoveredSegmentStart: interval.start, recoveredSegmentEnd: interval.end, mediaPosition: end.seconds });
    expect(seconds).toBeGreaterThanOrEqual(60);
    expect(end.rate).toBe(1);
    expect(seconds / wall).toBeGreaterThan(0.9);
    expect(seconds / wall).toBeLessThan(1.05);
    expect(end.frames - start.frames).toBeGreaterThan(seconds * fixtureFrameRate * 0.8);
    expect(monitor.samples.length > 10 && sampleCadenceIsNormal(monitor.samples, fixtureFrameRate)).toBe(true);
  });

  await checkpoint('Audio decoded during playback', async () => {
    const samples = await page.evaluate(() => window.chromeSmokePlayback.samples);
    const end = await media(video);
    expect(end.audible && end.audioBytes > start.audioBytes).toBe(true);
    expect(audioGrowsThroughout([
      { media: start.seconds, audioBytes: start.audioBytes },
      ...samples,
      { media: end.seconds, audioBytes: end.audioBytes }
    ])).toBe(true);
  });

  await checkpoint('HLS URLs have no query string', async () => {
    expect(traffic.safeHlsUrls()).toBe(true);
  });

  const seek = await checkpoint('Seek back with room to save below 90 percent', async () => {
    await checkpoint('No playback rate change before the explicit seek', async () => {
      expect(await page.evaluate(() => !window.chromeSmokePlayback.unexpectedRateChange)).toBe(true);
    });
    const { seconds, duration } = await media(video);
    const target = Math.floor(seconds) - seekBackSeconds;
    expect(target).toBeGreaterThan(0);
    // Leave room for the pause/resume step and the save, which must stay below 90 percent.
    expect(target + 6).toBeLessThan(duration * 0.9);
    const landedAt = await video.evaluate((element: HTMLVideoElement, { nextPosition, timeout }) => {
      if (window.chromeSmokePlayback.unexpectedSeek || window.chromeSmokePlayback.unexpectedRateChange) return null;
      // Arm the listener first, so only the seek requested here can resolve it.
      const seeked = new Promise<number>((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('No seeked event after the explicit seek')), timeout);
        element.addEventListener('seeked', () => {
          clearTimeout(timer);
          resolve(element.currentTime);
        }, { once: true });
      });
      window.chromeSmokePlayback.seekAllowed = true;
      element.currentTime = nextPosition;
      return seeked;
    }, { nextPosition: target, timeout: seekedTimeout });
    if (landedAt === null) {
      throw new Error('Unexpected seek or rate change before the explicit seek');
    }
    expect(Math.abs(landedAt - target)).toBeLessThan(1);
    return { target, completedAt: Date.now() };
  });

  await checkpoint('Pause and resume native playback', async () => {
    await video.press('Space');
    await expect.poll(async () => (await media(video)).paused).toBe(true);
    const pausedAt = (await media(video)).seconds;
    await playing(video);
    await expect.poll(async () => (await media(video)).seconds).toBeGreaterThan(pausedAt + 2);
  });

  const saved = await checkpoint('Persist the paused position below 90 percent', async () => {
    await video.press('Space');
    await expect.poll(async () => (await media(video)).paused).toBe(true);
    const paused = await media(video);
    // The final pause stays close to the rewound position, so a pre-seek save (at least seekBackSeconds later)
    // can never be mistaken for the save below.
    expect(paused.seconds - seek.target).toBeLessThan(maxPlayAfterSeekSeconds);
    // Any save committed after the seek back at the paused position counts: the pause save, or a timer tick in the
    // same second that made the pause save a duplicate. A pre-seek save still in flight started earlier and carries
    // a later position; the first pause's save is more than two seconds before the final pause.
    const committedNearPause = async (): Promise<number | null> => {
      for (const exchange of traffic.current()) {
        if (exchange.route !== progressRoute || exchange.request.method() !== 'PUT') continue;
        if (exchange.response?.status() !== 200 || exchange.sentAt < seek.completedAt) continue;
        const progress = await exchange.response.json();
        const position = Number(progress.currentSeconds);
        if (Number.isInteger(position) && Math.abs(position - paused.seconds) <= pauseSaveTolerance) {
          return position;
        }
      }
      return null;
    };
    await expect.poll(committedNearPause, { timeout: 15_000 }).not.toBeNull();
    const saved = (await committedNearPause())!;
    expect(saved).toBeGreaterThan(seek.target);
    expect(saved).toBeLessThan(paused.duration * 0.9);
    report({ savedSeconds: saved });
    return saved;
  });

  await checkpoint('Exactly one refresh through the end of the original document', async () => {
    await page.reload();
    expect(refreshCount()).toBe(1);
  });
  await checkpoint('No further request for the recovered segment before reload', async () => {
    expect(requestedAgain()).toBe(false);
  });

  await checkpoint('Cookie bootstrap supplies the new document playback request', async () => {
    const firstRequest = (route: string) => traffic.current().find(exchange => exchange.route === route);
    await expect.poll(() => Boolean(firstRequest(playbackRoute)?.completed), { timeout: 15_000 }).toBe(true);
    const bootstrap = firstRequest(refreshRoute);
    const playback = firstRequest(playbackRoute)!;
    expect(playback.document > originalDocument && bootstrap?.document === playback.document).toBe(true);
    expect(Boolean(bootstrap?.completed && bootstrap.completed < playback.started)).toBe(true);
    expect(playback.response?.status()).toBe(200);
    const token = await issuedToken(bootstrap?.response);
    await expect.poll(() => page.evaluate(
      routes => routes.every(route =>
        performance.getEntriesByName(new URL(route, location.origin).href, 'resource').length > 0),
      [refreshRoute, playbackRoute]
    )).toBe(true);
    expect(token !== null && token !== refreshedBearer && await hasBearer(playback, token)).toBe(true);
    report({ route: refreshRoute, status: bootstrap?.response?.status(), at: bootstrap?.at });
  });

  await checkpoint('Cookie bootstrap resumes near the committed position', async () => {
    await expect.poll(async () => Math.abs((await media(video)).seconds - saved), { timeout: 15_000 })
      .toBeLessThanOrEqual(3);
    const resumed = await media(video);
    expect(resumed.paused).toBe(true);
    report({ resumeSeconds: resumed.seconds });
  });

  await checkpoint('No media error in either document', async () => {
    expect(mediaErrors).toEqual([]);
    expect((await media(video)).error).toBeNull();
    expect(traffic.safeHlsUrls()).toBe(true);
  });
  await checkpoint('Exactly one cookie bootstrap in the new document', async () => {
    expect(traffic.current().filter(exchange => exchange.route === refreshRoute)).toHaveLength(1);
  });
});
