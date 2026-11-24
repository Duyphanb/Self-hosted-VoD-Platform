import { expect, test, type CDPSession, type Locator } from '@playwright/test';
import { installPlaybackMonitor, media, sampleCadenceIsNormal, type PlaybackFault } from './chrome-smoke-media';
import {
  hasBearer, issuedToken, loginRoute, recovery, refreshRoute, tokenTimes, watchTraffic
} from './chrome-smoke-traffic';

const codec = 'video/mp4; codecs="avc1.64001E, mp4a.40.2"';
const accessTtl = 60_000;
const clockSkew = 5_000;
const saveCadence = 10_000;
const expiryMargin = 1_000;
const segmentLatency = 4_500;
const refreshLatency = 3_500;
const lateProgressLatency = 15_000;
const fixtureFrameRate = 24;

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
  const asset = fixtureId('VOD_CHROME_ASSET_ID');
  const watch = `/movies/${movie}/watch`;
  const playbackRoute = `/api/v1/movies/${movie}/playback`;
  const progressRoute = `/api/v1/movies/${movie}/progress`;
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
    await page.getByRole('link', { name: 'Watch', exact: true }).click();
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
    // Phase the real progress timer so its next tick after JWT expiry sends the old token.
    await video.focus();
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
  await checkpoint('HLS 401 then one refresh then 200 on the same resource', async () => {
    // Enter the expiry window after the preceding save tick. Delay delivery of the real progress
    // response until after the real refresh without routing, fulfilling or changing either request.
    await waitUntil(expectedExpiration - clockSkew);
    await shapeTraffic(cdp, [segments, [origin + progressRoute, lateProgressLatency],
      [origin + refreshRoute, refreshLatency]]);
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
    expect(hlsDenied.at >= expires + clockSkew && hlsDenied.completed < refresh.started).toBe(true);
    expect(progressDenied.started < refresh.completed && progressDenied.completed > refresh.completed).toBe(true);
    const fresh = await issuedToken(refresh.response);
    expect(fresh !== null && fresh !== originalToken).toBe(true);
    expect(await hasBearer(hlsDenied, originalToken) && await hasBearer(progressDenied, originalToken)).toBe(true);
    expect(await hasBearer(hlsRetry, fresh) && await hasBearer(progressRetry, fresh)).toBe(true);
    const sequence = [hlsDenied, refresh, hlsRetry, progressDenied, progressRetry]
      .sort((first, second) => first.completed - second.completed);
    for (const event of sequence) {
      report({ route: event.route, status: event.response?.status(), at: event.at });
    }
  });

  await checkpoint('No seek before the explicit user seek', async () => {
    expect(await page.evaluate(() => !window.chromeSmokePlayback.unexpectedSeek)).toBe(true);
  });

  await checkpoint('At least 60 seconds of media at normal rate before seeking', async () => {
    await expect.poll(async () => (await media(video)).seconds - start.seconds, { timeout: 40_000 })
      .toBeGreaterThanOrEqual(60);
    const end = await media(video);
    const seconds = end.seconds - start.seconds;
    const wall = (performance.now() - started) / 1000;
    const monitor = await page.evaluate(() => window.chromeSmokePlayback);
    report({ mediaSeconds: seconds, wallSeconds: wall, framesStart: start.frames, framesEnd: end.frames,
      mediaError: end.error, audioDecoded: end.audioBytes > start.audioBytes });
    expect(end.rate).toBe(1);
    expect(seconds / wall).toBeGreaterThan(0.9);
    expect(seconds / wall).toBeLessThan(1.05);
    expect(end.frames - start.frames).toBeGreaterThan(seconds * fixtureFrameRate * 0.8);
    expect(monitor.samples.length > 10 && sampleCadenceIsNormal(monitor.samples, fixtureFrameRate)).toBe(true);
  });

  await checkpoint('Audio decoded during playback', async () => {
    const end = await media(video);
    expect(end.audible && end.audioBytes > start.audioBytes).toBe(true);
  });

  await checkpoint('HLS URLs have no query string', async () => {
    expect(traffic.safeHlsUrls()).toBe(true);
  });

  await checkpoint('Seek to 84 seconds', async () => {
    const allowed = await video.evaluate((element: HTMLVideoElement) => {
      if (window.chromeSmokePlayback.unexpectedSeek) return false;
      window.chromeSmokePlayback.seekAllowed = true;
      element.currentTime = 84;
      return true;
    });
    expect(allowed).toBe(true);
    await expect.poll(async () => Math.abs((await media(video)).seconds - 84)).toBeLessThan(2);
    await expect.poll(async () => (await media(video)).seeking).toBe(false);
  });

  await checkpoint('Pause and resume native playback', async () => {
    await video.press('Space');
    await expect.poll(async () => (await media(video)).paused).toBe(true);
    const pausedAt = (await media(video)).seconds;
    await playing(video);
    await expect.poll(async () => (await media(video)).seconds).toBeGreaterThan(pausedAt + 2);
  });

  const saved = await checkpoint('Persist the paused position below 90 percent', async () => {
    const minimum = Math.floor((await media(video)).seconds);
    const save = page.waitForResponse(async response => {
      const url = new URL(response.url());
      if (url.origin !== origin || url.pathname !== progressRoute) return false;
      if (response.request().method() !== 'PUT' || response.status() !== 200) return false;
      const progress = await response.json();
      return Number.isInteger(progress.currentSeconds) && progress.currentSeconds >= minimum;
    }, { timeout: 15_000 });
    await video.press('Space');
    const progress = await (await save).json();
    const saved = Number(progress.currentSeconds);
    const paused = await media(video);
    expect(paused.paused).toBe(true);
    expect(saved).toBeGreaterThan(84);
    expect(saved).toBeLessThan(paused.duration * 0.9);
    expect(Math.abs(saved - paused.seconds)).toBeLessThanOrEqual(2);
    report({ savedSeconds: saved });
    return saved;
  });

  await checkpoint('Exactly one refresh through the end of the original document', async () => {
    await page.reload();
    expect(refreshCount()).toBe(1);
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
    expect(await hasBearer(playback, token)).toBe(true);
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
});
