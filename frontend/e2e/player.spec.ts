import { test, expect, type APIRequestContext, type Page } from '@playwright/test';

/**
 * The player's HLS protocol in real browsers with real hls.js and real XHRs (ADR-011 D1-D4, D7, D8).
 * Segments are held open by the test server, so these cases prove transport and authentication, not
 * decoding; the decoding smoke in desktop Chrome is #125.
 */

interface HlsRequest {
  path: string;
  label: string;
  hasCookie: boolean;
  hasQuery: boolean;
  status: number;
}

const MANIFEST_A = '/hls/video-assets/asset-a/master.m3u8';
const MANIFEST_B = '/hls/video-assets/asset-b/master.m3u8';
/** The resume position is 12 s and segments last 6 s, so loading starts with the third segment. */
const SEGMENT_A = '/hls/video-assets/asset-a/segments/seg_00002.ts';
const SEGMENT_B = '/hls/video-assets/asset-b/segments/seg_00002.ts';
const ASSET_B = '9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d';
/** hls.js 1.7.4 schedules its first fragment error retry after 1 s; waiting longer catches an extra send. */
const PAST_NEXT_RETRY_MS = 2500;

async function control(request: APIRequestContext, value: Record<string, unknown>) {
  await request.post('/__test/control', { data: value });
}

async function hlsRequests(request: APIRequestContext): Promise<HlsRequest[]> {
  const response = await request.get('/__test/hls');
  return (await response.json()).requests;
}

async function serverCount(request: APIRequestContext, operation: 'refresh' | 'playback'): Promise<number> {
  const response = await request.get('/__test/state');
  return (await response.json()).counts[operation] ?? 0;
}

async function refreshCount(request: APIRequestContext): Promise<number> {
  return serverCount(request, 'refresh');
}

async function signIn(page: Page, user = 'Alice') {
  await page.goto('/e2e/player.html');
  await expect(page.getByTestId('initializing')).toHaveText('false');
  await page.getByRole('button', { name: `Sign in ${user}`, exact: true }).click();
  await expect(page.getByTestId('identity')).toHaveText(user.toLowerCase());
}

async function openPlayer(page: Page) {
  await page.getByRole('button', { name: 'Open player', exact: true }).click();
  await expect(page.getByLabel('Fixture movie')).toBeAttached();
}

/** The answered requests for `path`, as "token label -> status" pairs. */
async function attempts(request: APIRequestContext, path: string): Promise<string[]> {
  const requests = await hlsRequests(request);
  return requests
    .filter(entry => entry.path === path && entry.status !== 0)
    .map(entry => `${entry.label} -> ${entry.status}`);
}

/** Every request for `path`, including one that the server still holds open ("held"). */
async function allAttempts(request: APIRequestContext, path: string): Promise<string[]> {
  const requests = await hlsRequests(request);
  return requests
    .filter(entry => entry.path === path)
    .map(entry => `${entry.label} -> ${entry.status === 0 ? 'held' : entry.status}`);
}

async function openPlayerAndWaitForSegment(page: Page, request: APIRequestContext) {
  await openPlayer(page);
  await expect.poll(() => allAttempts(request, SEGMENT_A)).toEqual(['alice-1 -> held']);
}

function playerAlert(page: Page) {
  return page.getByRole('alert').filter({ hasNotText: 'Sign-in could not be restored' });
}

test.beforeEach(async ({ request }) => {
  await request.post('/__test/reset');
});

test('every HLS request carries the bearer token, with no cookie and no token in the URL', async ({
  page,
  request
}) => {
  await signIn(page);
  await openPlayer(page);

  await expect.poll(async () => (await hlsRequests(request)).map(entry => entry.path)).toEqual([
    MANIFEST_A,
    SEGMENT_A
  ]);
  const requests = await hlsRequests(request);
  expect(requests.every(entry => entry.label === 'alice-1')).toBe(true);
  expect(requests.some(entry => entry.hasCookie || entry.hasQuery)).toBe(false);

  const video = page.getByLabel('Fixture movie');
  expect(await video.evaluate((element: HTMLVideoElement) => element.paused)).toBe(true);
  expect(await video.evaluate((element: HTMLVideoElement) => element.src.startsWith('blob:'))).toBe(true);
  await expect(page.getByRole('status')).toHaveText('Loading video…');
});

test('a manifest 401 refreshes once and retries with the new token', async ({ page, request }) => {
  await signIn(page);
  const refreshesBefore = await refreshCount(request);
  await control(request, { expireOnPlayback: true });

  await openPlayer(page);

  await expect.poll(() => attempts(request, MANIFEST_A)).toEqual(['alice-1 -> 401', 'alice-2 -> 200']);
  expect(await refreshCount(request)).toBe(refreshesBefore + 1);
  await expect(playerAlert(page)).toHaveCount(0);
});

test('a segment 401 refreshes once and retries the segment with the new token', async ({ page, request }) => {
  await signIn(page);
  const refreshesBefore = await refreshCount(request);
  await control(request, { expireAfterManifest: true });

  await openPlayer(page);

  await expect.poll(() => allAttempts(request, SEGMENT_A)).toEqual(['alice-1 -> 401', 'alice-2 -> held']);
  expect(await refreshCount(request)).toBe(refreshesBefore + 1);
  await expect(playerAlert(page)).toHaveCount(0);
});

test('a manifest 401 on the retry ends the session', async ({ page, request }) => {
  await signIn(page);
  await control(request, { hlsUnauthorized: true });

  await openPlayer(page);

  await expect(playerAlert(page)).toContainText('Your session has ended.');
  await expect(page.getByTestId('identity')).toHaveText('signed-out');
  expect(await attempts(request, MANIFEST_A)).toEqual(['alice-1 -> 401', 'alice-2 -> 401']);
});

test('a segment 401 on the retry ends the session after exactly two sends', async ({ page, request }) => {
  await signIn(page);
  await openPlayerAndWaitForSegment(page, request);

  await control(request, { segmentsUnauthorized: true, releaseSegments: true });

  await expect(playerAlert(page)).toContainText('Your session has ended.');
  await expect(page.getByTestId('identity')).toHaveText('signed-out');
  await page.waitForTimeout(PAST_NEXT_RETRY_MS);
  expect(await allAttempts(request, SEGMENT_A)).toEqual(['alice-1 -> 401', 'alice-2 -> 401']);
});

test('a refresh 503 after a segment 401 stops at once and keeps the session', async ({ page, request }) => {
  await signIn(page);
  await control(request, { expireAfterManifest: true, refreshStatus: 503 });

  await openPlayer(page);

  await expect(playerAlert(page)).toContainText('The video could not be loaded.');
  await expect(page.getByTestId('identity')).toHaveText('alice');
  await page.waitForTimeout(PAST_NEXT_RETRY_MS);
  expect(await allAttempts(request, SEGMENT_A)).toEqual(['alice-1 -> 401']);
});

test('a segment 503 is retried with the hls.js backoff and does not stop the player', async ({
  page,
  request
}) => {
  await signIn(page);
  await control(request, { segmentFailures: [503] });

  await openPlayer(page);

  await expect.poll(() => allAttempts(request, SEGMENT_A)).toEqual(['alice-1 -> 503', 'alice-1 -> held']);
  await expect(playerAlert(page)).toHaveCount(0);
  await expect(page.getByRole('status')).toHaveText('Loading video…');
});

test('a refresh 503 keeps the session and Retry recovers', async ({ page, request }) => {
  await signIn(page);
  await control(request, { expireOnPlayback: true, refreshStatus: 503 });

  await openPlayer(page);

  await expect(playerAlert(page)).toContainText('The video could not be loaded.');
  await expect(page.getByTestId('identity')).toHaveText('alice');
  await control(request, { refreshStatus: null });
  await playerAlert(page).getByRole('button', { name: 'Retry' }).click();

  await expect.poll(() => attempts(request, MANIFEST_A)).toEqual([
    'alice-1 -> 401',
    'alice-1 -> 401',
    'alice-2 -> 200'
  ]);
  await expect(playerAlert(page)).toHaveCount(0);
});

test('a 404 after the asset was replaced reloads the new asset at the current position', async ({
  page,
  request
}) => {
  await signIn(page);
  await openPlayerAndWaitForSegment(page, request);

  await control(request, { currentAsset: ASSET_B, releaseSegments: true });

  await expect.poll(() => attempts(request, MANIFEST_B)).toEqual(['alice-1 -> 200']);
  await expect.poll(() => allAttempts(request, SEGMENT_B)).toEqual(['alice-1 -> held']);
  await expect(playerAlert(page)).toHaveCount(0);
  await page.waitForTimeout(PAST_NEXT_RETRY_MS);
  expect(await allAttempts(request, SEGMENT_A)).toEqual(['alice-1 -> 404']);
  expect(await serverCount(request, 'playback')).toBe(2);
});

test('a 404 without a current asset shows that the video is no longer available', async ({
  page,
  request
}) => {
  await signIn(page);
  await openPlayerAndWaitForSegment(page, request);

  await control(request, { currentAsset: 'none', playbackStatus: 409, releaseSegments: true });

  await expect(playerAlert(page)).toContainText('This video is no longer available.');
  await page.waitForTimeout(PAST_NEXT_RETRY_MS);
  expect(await allAttempts(request, SEGMENT_A)).toEqual(['alice-1 -> 404']);
  expect(await serverCount(request, 'playback')).toBe(2);
});

test('another account signing in destroys the player and sends no request with its token', async ({
  page,
  request
}) => {
  await signIn(page);
  await openPlayerAndWaitForSegment(page, request);

  await page.getByRole('button', { name: 'Sign in Bob', exact: true }).click();

  await expect(page.getByTestId('identity')).toHaveText('bob');
  await expect(playerAlert(page)).toContainText('a different account signed in');
  await control(request, { releaseSegments: true });
  const requests = await hlsRequests(request);
  expect(requests.some(entry => entry.label.startsWith('bob'))).toBe(false);
});
