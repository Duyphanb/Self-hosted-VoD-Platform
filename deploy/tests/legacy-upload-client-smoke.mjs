import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { extname, resolve, sep } from 'node:path';

const require = createRequire(new URL('../../frontend/package.json', import.meta.url));
const { chromium, expect } = require('@playwright/test');
const bundleDirectory = resolve(process.argv[2] ?? '');
const indexHtml = await readFile(resolve(bundleDirectory, 'index.html'));
const titleId = '78b00000-0000-4000-8000-000000000001';
const previousTitleRoute = `/api/v1/movies/${titleId}`;
const previousUploadRoute = `/api/v1/admin/movies/${titleId}/video`;
const user = { id: '78b00000-0000-4000-8000-000000000002', email: 'legacy@example.test',
  displayName: 'Legacy Admin', roles: ['ROLE_USER', 'ROLE_ADMIN'] };
const title = { id: titleId, title: 'Legacy fixture', slug: 'legacy-fixture', description: 'Synthetic cutover fixture',
  genres: [], people: [], releaseYear: null, posterUrl: null, averageRating: null, maturityRating: null,
  status: 'DRAFT', videoStatus: null, userRating: null, inWatchlist: false };

let cutover = false;
let titleChecksAfterCutover = 0;
let oldUploadRequests = 0;
let oldUploadBytes = 0;

function json(response, code, value) {
  response.writeHead(code, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify(value));
}

const server = createServer(async (request, response) => {
  const path = new URL(request.url, 'http://localhost').pathname;
  if (request.method === 'POST' && ['/api/v1/auth/login', '/api/v1/auth/refresh'].includes(path)) {
    request.resume();
    json(response, 200, { user, accessToken: 'synthetic-test-token', expiresInSeconds: 600 });
    return;
  }
  if (request.method === 'GET' && path === previousTitleRoute) {
    if (cutover) {
      titleChecksAfterCutover += 1;
      json(response, 404, { timestamp: new Date().toISOString(), status: 404, code: 'NOT_FOUND',
        message: 'Route no longer exists' });
    } else {
      json(response, 200, title);
    }
    return;
  }
  if (request.method === 'POST' && path === previousUploadRoute) {
    oldUploadRequests += 1;
    for await (const chunk of request) {
      oldUploadBytes += chunk.length;
    }
    json(response, 404, {});
    return;
  }
  if (path.startsWith('/api/')) {
    json(response, 200, { items: [], page: { page: 0, size: 20, totalPages: 0, totalElements: 0 } });
    return;
  }
  if (path.startsWith('/assets/')) {
    const file = resolve(bundleDirectory, `.${path}`);
    if (!file.startsWith(resolve(bundleDirectory, 'assets') + sep)) {
      response.writeHead(404).end();
      return;
    }
    try {
      const asset = await readFile(file);
      const type = extname(file) === '.css' ? 'text/css' : 'text/javascript';
      response.writeHead(200, { 'Content-Type': type }).end(asset);
    } catch {
      response.writeHead(404).end();
    }
    return;
  }
  response.writeHead(200, { 'Content-Type': 'text/html' }).end(indexHtml);
});

let browser;
try {
  await new Promise((ready, reject) => server.once('error', reject).listen(0, '127.0.0.1', ready));
  const origin = `http://127.0.0.1:${server.address().port}`;
  browser = await chromium.launch({ channel: 'chrome' });
  const page = await browser.newPage();
  await page.goto(`${origin}/login`);
  await page.getByLabel('Email', { exact: true }).fill(user.email);
  await page.getByLabel('Password', { exact: true }).fill('Synthetic-upload-smoke!');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Sign out', exact: true })).toBeVisible();
  await page.goto(`${origin}/admin/movies/${titleId}/video`);
  // The old page is already open and has successfully resolved the title before the fence closes.
  const file = page.getByLabel('Video file');
  await expect(file).toBeVisible();
  await file.setInputFiles({ name: 'legacy.mp4', mimeType: 'video/mp4', buffer: Buffer.from('0000ftypisom') });
  cutover = true;
  const rejection = page.waitForResponse(result => new URL(result.url()).pathname === previousTitleRoute
    && result.status() === 404);
  await page.getByRole('button', { name: 'Upload', exact: true }).click();
  await rejection;
  await expect(page.getByRole('heading', { name: 'Movie not found', exact: true })).toBeVisible();
  assert.equal(titleChecksAfterCutover, 1);
  assert.equal(oldUploadRequests, 0);
  assert.equal(oldUploadBytes, 0);
  console.log(`Chrome ${browser.version()}: open V7 tab checked the removed route once, upload requests=0, bytes=0`);
} finally {
  await browser?.close();
  server.close();
}
