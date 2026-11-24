import { expect, test, type Locator, type Page } from '@playwright/test';

const codec = 'video/mp4; codecs="avc1.42E01E, mp4a.40.2"';
const refreshRoute = '/api/v1/auth/refresh';

type Observation = { route: string; status: number; at: number };

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

async function checkpoint(label: string, action: () => Promise<void>): Promise<void> {
  try {
    await test.step(label, action);
  } catch (error) {
    report({ failed: label });
    throw error;
  }
}

function watchResponses(page: Page, origin: string, hlsPrefix: string): Observation[] {
  const observations: Observation[] = [];
  page.on('response', response => {
    const url = new URL(response.url());
    if (url.origin !== origin) return;
    const route = url.pathname;
    const isHls = route === hlsPrefix + 'master.m3u8'
      || (route.startsWith(hlsPrefix) && /^segments\/seg_\d+\.ts$/.test(route.slice(hlsPrefix.length)));
    if (!isHls && (route !== refreshRoute || response.request().method() !== 'POST')) return;
    const observation = { route, status: response.status(), at: Date.now() };
    observations.push(observation);
    if (route === refreshRoute || observation.status === 401) report(observation);
  });
  return observations;
}

function loadedRecovery(observations: Observation[], hlsPrefix: string): Observation[] {
  const denied = observations.findIndex(result => result.route.startsWith(hlsPrefix) && result.status === 401);
  if (denied < 0) return [];
  const retry = observations.findIndex((result, index) => index > denied
    && result.route === observations[denied].route && result.status === 200);
  if (retry < 0) return [];
  const refreshes = observations.slice(denied + 1, retry).filter(result => result.route === refreshRoute);
  if (refreshes.length !== 1 || refreshes[0].status !== 200) return [];
  return [observations[denied], refreshes[0], observations[retry]];
}

function media(video: Locator) {
  return video.evaluate((element: HTMLVideoElement) => ({
    seconds: element.currentTime,
    frames: element.getVideoPlaybackQuality().totalVideoFrames - element.getVideoPlaybackQuality().droppedVideoFrames,
    paused: element.paused,
    seeking: element.seeking,
    rate: element.playbackRate,
    error: element.error?.code ?? null,
    ready: element.readyState,
    duration: element.duration
  }));
}

async function playing(video: Locator): Promise<void> {
  await video.press('Space');
  await expect.poll(async () => (await media(video)).paused).toBe(false);
}

test('Chrome uploads through the real stack and recovers expired HLS playback', async ({ page, browser, baseURL }) => {
  const movie = fixtureId('VOD_CHROME_MOVIE_ID');
  const asset = fixtureId('VOD_CHROME_ASSET_ID');
  const watch = `/movies/${movie}/watch`;
  const progressRoute = `/api/v1/movies/${movie}/progress`;
  const hlsPrefix = `/hls/video-assets/${asset}/`;
  const observations = watchResponses(page, baseURL!, hlsPrefix);
  report({ chrome: browser.version() });

  await checkpoint('Chrome supports H.264 and AAC', async () => {
    const supported = await page.evaluate(value => MediaSource.isTypeSupported(value)
      && document.createElement('video').canPlayType(value) !== '', codec);
    expect(supported).toBe(true);
  });

  const video = page.locator('video');
  const cdp = await page.context().newCDPSession(page);
  const throttle = async (latency: number, delayProgress = false) => {
    const rules = latency === 0 ? [] : [{
      urlPattern: baseURL + hlsPrefix + 'segments/*',
      latency,
      downloadThroughput: -1,
      uploadThroughput: -1
    }];
    if (delayProgress) {
      rules.push({
        urlPattern: baseURL + progressRoute,
        latency: 9_000,
        downloadThroughput: -1,
        uploadThroughput: -1
      });
    }
    await cdp.send('Network.emulateNetworkConditionsByRule', {
      offline: false,
      matchedNetworkConditions: rules
    });
  };
  await throttle(4500);
  let loginAt = 0;
  await checkpoint('Real UI login and uploaded asset start paused', async () => {
    await page.goto('/login');
    await page.getByLabel('Email', { exact: true }).fill(process.env.VOD_CHROME_EMAIL ?? '');
    await page.getByLabel('Password', { exact: true }).fill(process.env.VOD_CHROME_PASSWORD ?? '');
    const loginResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/auth/login'
      && response.status() === 200);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await loginResponse;
    loginAt = Date.now();
    await expect(page.getByRole('button', { name: 'Sign out', exact: true })).toBeVisible();
    await page.getByRole('link', { name: 'Chrome playback smoke' }).first().click();
    await page.getByRole('link', { name: 'Watch', exact: true }).click();
    await expect(page).toHaveURL(baseURL + watch);
    await expect.poll(async () => (await media(video)).ready, { timeout: 18_000 }).toBeGreaterThanOrEqual(2);
    expect(observations.some(result => result.route === hlsPrefix + 'master.m3u8' && result.status === 200)).toBe(true);
    const paused = await media(video);
    expect(paused.paused).toBe(true);
    expect(paused.error).toBeNull();
    expect(Math.abs(paused.duration - 120)).toBeLessThanOrEqual(2);
    await video.evaluate((element: HTMLVideoElement) => {
      element.addEventListener('error', () => {
        element.dataset.smokeMediaError = String(element.error?.code ?? 0);
      });
    });
  });

  // Align 10-second progress writes near seconds 60 and 70 of the JWT. The segment cadence is
  // shorter than that interval and keeps unrequested segments available past JWT expiry + 5 s skew.
  await new Promise(resolve => setTimeout(resolve, Math.max(0, loginAt + 20_000 - Date.now())));
  let start = await media(video);
  let started = performance.now();
  await checkpoint('User starts native playback', async () => {
    await playing(video);
    start = await media(video);
    started = performance.now();
  });

  await checkpoint('HLS 401 then one refresh then 200 on the same resource', async () => {
    // Transport latency on the progress route gives HLS the first expiry recovery. Both routes
    // still reach the real backend and the player keeps using its unmodified save timer.
    await new Promise(resolve => setTimeout(resolve, Math.max(0, loginAt + 63_000 - Date.now())));
    await throttle(4500, true);
    await expect.poll(() => loadedRecovery(observations, hlsPrefix).length, { timeout: 22_000 }).toBe(3);
    for (const event of loadedRecovery(observations, hlsPrefix)) report(event);
  });
  await throttle(0);

  await checkpoint('At least 60 seconds of media at normal rate before seeking', async () => {
    await expect.poll(async () => (await media(video)).seconds - start.seconds, { timeout: 40_000 })
      .toBeGreaterThanOrEqual(60);
    const end = await media(video);
    const seconds = end.seconds - start.seconds;
    const wall = (performance.now() - started) / 1000;
    report({ mediaSeconds: seconds, wallSeconds: wall, framesStart: start.frames, framesEnd: end.frames,
      mediaError: end.error });
    expect(end.error).toBeNull();
    await expect(video).not.toHaveAttribute('data-smoke-media-error', /\d+/);
    expect(end.rate).toBe(1);
    expect(end.frames).toBeGreaterThan(start.frames + 120);
    expect(seconds / wall).toBeGreaterThan(0.9);
    expect(seconds / wall).toBeLessThan(1.05);
  });

  await checkpoint('Seek to 84 seconds', async () => {
    await video.evaluate((element: HTMLVideoElement) => {
      element.currentTime = 84;
    });
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

  let saved = 0;
  await checkpoint('Persist the paused position below 90 percent', async () => {
    const minimum = Math.floor((await media(video)).seconds);
    const save = page.waitForResponse(async response => {
      const url = new URL(response.url());
      if (url.origin !== baseURL || url.pathname !== progressRoute) return false;
      if (response.request().method() !== 'PUT' || response.status() !== 200) return false;
      const progress = await response.json();
      return Number.isInteger(progress.currentSeconds) && progress.currentSeconds >= minimum;
    }, { timeout: 15_000 });
    await video.press('Space');
    const progress = await (await save).json();
    saved = progress.currentSeconds;
    const paused = await media(video);
    expect(paused.paused).toBe(true);
    expect(saved).toBeGreaterThan(84);
    expect(saved).toBeLessThan(paused.duration * 0.9);
    expect(Math.abs(saved - paused.seconds)).toBeLessThanOrEqual(2);
    report({ savedSeconds: saved });
  });

  await checkpoint('Cookie bootstrap resumes near the committed position', async () => {
    const bootstrap = page.waitForResponse(response => new URL(response.url()).pathname === refreshRoute
      && response.request().method() === 'POST' && response.status() === 200);
    await page.reload();
    await bootstrap;
    await expect.poll(async () => Math.abs((await media(video)).seconds - saved), { timeout: 15_000 })
      .toBeLessThanOrEqual(3);
    const resumed = await media(video);
    expect(resumed.paused).toBe(true);
    expect(resumed.error).toBeNull();
    report({ resumeSeconds: resumed.seconds });
  });
});
