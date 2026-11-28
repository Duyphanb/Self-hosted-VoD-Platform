/** Controlled backend for the real session protocol. It never logs token/cookie values. */
import http from 'node:http';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { resolve, extname } from 'node:path';
import { URL } from 'node:url';
import { clearTimeout, setTimeout } from 'node:timers';

const root = resolve('dist-e2e');
let sessions = new Map();
let access = new Map();
let active = 0;
let maxWriters = 0;
let counts = {};
let control = {};
let gates = [];
let segmentGates = [];
let tokenLabels = new Map();
let issuedPerUser = {};
let hlsLog = [];
let progressSaves = [];
const ASSET_A = '5f1e2d3c-4b5a-4968-8776-655443322110';
const ASSET_B = '9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d';
const TITLE_ID = '3c2b1a09-8f7e-4d6c-9b5a-493827160504';
const SEGMENT_COUNT = 5;
const users = Object.fromEntries(['alice', 'bob'].map(id => [id, {
  id,
  email: id + '@example.com',
  displayName: id,
  roles: ['ROLE_USER']
}]));

function release() {
  for (const complete of gates) {
    complete();
  }
  gates = [];
}

function json(response, status, payload) {
  response.writeHead(status, { 'Content-Type': 'application/json' });
  response.end(JSON.stringify(payload));
}

function error(response, status, code) {
  json(response, status, { timestamp: new Date().toISOString(), status, code, message: 'Fixture response' });
}

function cookie(request) {
  const matchingCookies = (request.headers.cookie ?? '').split(';')
    .map(value => value.trim())
    .filter(value => value.startsWith('vod_refresh='));
  return matchingCookies.length === 1 ? matchingCookies[0].slice('vod_refresh='.length) : null;
}

function setCookie(response, value, age = 604800) {
  const attributes = `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=${age}`;
  response.setHeader('Set-Cookie', `vod_refresh=${value}; ${attributes}`);
}

function issue(request, response, userId, bodyDelayMs = 0) {
  const previousCookie = cookie(request);
  if (previousCookie) {
    sessions.delete(previousCookie);
  }
  const refreshValue = 'refresh-cookie-test-' + randomUUID();
  const accessValue = 'access-cookie-test-' + randomUUID();
  sessions.set(refreshValue, userId);
  access.set(accessValue, userId);
  issuedPerUser[userId] = (issuedPerUser[userId] ?? 0) + 1;
  // Requests are logged with this label, never with the token value.
  tokenLabels.set(accessValue, `${userId}-${issuedPerUser[userId]}`);
  setCookie(response, refreshValue);
  const payload = { user: users[userId], accessToken: accessValue, expiresInSeconds: 900 };
  if (bodyDelayMs <= 0) {
    json(response, 200, payload);
    return;
  }
  response.writeHead(200, { 'Content-Type': 'application/json' });
  response.flushHeaders();
  // Start the response without delivering the JSON document before its delayed body.
  response.write(' ');
  return new Promise(resolve => {
    const timer = setTimeout(() => {
      if (!response.destroyed) {
        response.end(JSON.stringify(payload));
      }
      resolve();
    }, bodyDelayMs);
    response.once('close', () => {
      clearTimeout(timer);
      resolve();
    });
  });
}
async function body(request) {
  let raw = '';
  for await (const chunk of request) {
    raw += chunk;
  }
  return raw ? JSON.parse(raw) : {};
}

function releaseSegments() {
  for (const complete of segmentGates) {
    complete();
  }
  segmentGates = [];
}

function bearer(request) {
  return (request.headers.authorization ?? '').replace('Bearer ', '');
}

function currentAsset() {
  return control.currentAsset ?? ASSET_A;
}

function manifest() {
  const lines = ['#EXTM3U', '#EXT-X-VERSION:3', '#EXT-X-TARGETDURATION:6', '#EXT-X-MEDIA-SEQUENCE:0'];
  lines.push('#EXT-X-PLAYLIST-TYPE:VOD');
  for (let index = 0; index < SEGMENT_COUNT; index++) {
    lines.push('#EXTINF:6.000,', `segments/seg_${String(index).padStart(5, '0')}.ts`);
  }
  lines.push('#EXT-X-ENDLIST', '');
  return lines.join('\n');
}

/**
 * The authenticated HLS proxy (ADR-011 D4). Segments are held open until the test releases them, so no
 * fixture media is needed: the protocol, not decoding, is under test.
 */
async function hls(request, response, url) {
  const [, , , assetId, ...rest] = url.pathname.split('/');
  const token = bearer(request);
  const entry = {
    path: url.pathname.replace(assetId, assetId === ASSET_B ? 'asset-b' : 'asset-a'),
    label: tokenLabels.get(token) ?? (token ? 'unknown' : 'none'),
    hasCookie: Boolean(request.headers.cookie),
    hasQuery: url.search !== '',
    status: 0
  };
  hlsLog.push(entry);
  const finish = (status, contentType, body) => {
    entry.status = status;
    response.writeHead(status, { 'Content-Type': contentType, 'Cache-Control': 'no-store' });
    response.end(body);
  };
  const segmentRejected = control.segmentsUnauthorized && rest[0] === 'segments';
  if (control.hlsUnauthorized || segmentRejected || !access.has(token)) {
    return finish(401, 'application/json', JSON.stringify({ code: 'UNAUTHORIZED' }));
  }
  if (rest.join('/') === 'master.m3u8') {
    if (assetId !== currentAsset()) {
      return finish(404, 'application/json', JSON.stringify({ code: 'VIDEO_ASSET_NOT_FOUND' }));
    }
    if (control.expireAfterManifest) {
      control.expireAfterManifest = false;
      access.clear();
    }
    return finish(200, 'application/vnd.apple.mpegurl', manifest());
  }
  const scriptedStatus = control.segmentFailures?.shift();
  if (scriptedStatus) {
    return finish(scriptedStatus, 'application/json', JSON.stringify({ code: 'HLS_STORAGE_UNAVAILABLE' }));
  }
  while (assetId === currentAsset() && !control.segmentsUnauthorized && !response.destroyed) {
    await new Promise(complete => {
      segmentGates.push(complete);
      response.once('close', complete);
    });
  }
  if (response.destroyed) {
    return;
  }
  if (control.segmentsUnauthorized) {
    return finish(401, 'application/json', JSON.stringify({ code: 'UNAUTHORIZED' }));
  }
  finish(404, 'application/json', JSON.stringify({ code: 'VIDEO_ASSET_NOT_FOUND' }));
}

function playback(request, response, episodeId) {
  counts.playback = (counts.playback ?? 0) + 1;
  if (!access.has(bearer(request))) return error(response, 401, 'UNAUTHORIZED');
  if (control.playbackStatus === 409) return error(response, 409, 'VIDEO_NOT_READY');
  const videoAssetId = currentAsset();
  if (control.expireOnPlayback) {
    // The playback info used the token; the manifest request that follows meets an expired one.
    control.expireOnPlayback = false;
    access.clear();
  }
  return json(response, 200, {
    episodeId,
    titleId: TITLE_ID,
    videoAssetId,
    status: 'READY',
    hlsManifestUrl: `/hls/video-assets/${videoAssetId}/master.m3u8`,
    // The replacement asset has no saved position, so a reload that keeps 12 s took it from the player.
    resumeSeconds: videoAssetId === ASSET_B ? 0 : 12,
    durationSeconds: SEGMENT_COUNT * 6
  });
}

async function waitGate(response) {
  await new Promise(complete => {
    gates.push(complete);
    response.once('close', complete);
  });
}

const server = http.createServer(async (request, response) => {
  try {
    const path = new URL(request.url, 'http://localhost').pathname;
    if (path === '/__test/state') return json(response, 200, { active, maxWriters, counts });
    if (path === '/__test/reset') {
      release();
      sessions = new Map();
      access = new Map();
      active = 0;
      maxWriters = 0;
      counts = {};
      control = {};
      releaseSegments();
      tokenLabels = new Map();
      issuedPerUser = {};
      hlsLog = [];
      progressSaves = [];
      return json(response, 200, {});
    }
    if (path === '/__test/hls') return json(response, 200, { requests: hlsLog, progressSaves });
    if (path === '/__test/control') {
      Object.assign(control, await body(request));
      if (control.expireAccess) {
        control.expireAccess = false;
        access.clear();
      }
      if (control.releaseSegments) {
        control.releaseSegments = false;
        releaseSegments();
      }
      if (control.release) {
        release();
        control.hold = null;
        control.holdRead = false;
        control.release = false;
      }
      return json(response, 200, {});
    }
    if (path === '/api/v1/auth/probe') {
      return json(response, 200, { userId: sessions.get(cookie(request)) ?? null });
    }
    if (path === '/api/v1/users/me') {
      const userId = access.get((request.headers.authorization ?? '').replace('Bearer ', ''));
      if (control.holdRead) {
        await waitGate(response);
      }
      return userId ? json(response, 200, users[userId]) : error(response, 401, 'UNAUTHORIZED');
    }
    if (path.startsWith('/api/v1/auth/')) {
      const operation = path.split('/').at(-1);
      const payload = await body(request);
      if (operation === 'register') return json(response, 201, users.alice);
      active++;
      maxWriters = Math.max(maxWriters, active);
      counts[operation] = (counts[operation] ?? 0) + 1;
      try {
        if (control.hold === operation) {
          await waitGate(response);
        }
        if (response.destroyed) return;
        const failure = control[operation + 'Status'];
        if (failure === 'drop') {
          response.destroy();
          return;
        }
        if (typeof failure === 'number') {
          return error(response, failure, failure === 503 ? 'AUTH_STATE_UNAVAILABLE' : 'INTERNAL_ERROR');
        }
        if (operation === 'login') {
          const userId = payload.email?.startsWith('bob') ? 'bob' : 'alice';
          await issue(request, response, userId, control.loginBodyDelayMs ?? 0);
          return;
        }
        if (operation === 'refresh') {
          const userId = sessions.get(cookie(request));
          if (!userId) {
            setCookie(response, '', 0);
            return error(response, 401, 'UNAUTHORIZED');
          }
          return issue(request, response, userId);
        }
        if (operation === 'logout') {
          sessions.delete(cookie(request));
          setCookie(response, '', 0);
          response.writeHead(204);
          response.end();
          return;
        }
        return error(response, 404, 'NOT_FOUND');
      } finally {
        active--;
      }
    }
    if (path.startsWith('/hls/video-assets/')) {
      return await hls(request, response, new URL(request.url, 'http://localhost'));
    }
    const episodeRoute = path.match(/^\/api\/v1\/episodes\/([0-9a-f-]{36})\/(playback|progress)$/);
    if (episodeRoute?.[2] === 'playback') return playback(request, response, episodeRoute[1]);
    if (episodeRoute?.[2] === 'progress' && request.method === 'PUT') {
      if (!access.has(bearer(request))) return error(response, 401, 'UNAUTHORIZED');
      progressSaves.push(await body(request));
      return json(response, 200, { episodeId: episodeRoute[1], currentSeconds: 0, finished: false });
    }
    if (path.startsWith('/api/')) return error(response, 404, 'NOT_FOUND');
    let file = resolve(root, '.' + path);
    if (!file.startsWith(root)) return error(response, 404, 'NOT_FOUND');
    let data;
    try {
      data = await readFile(file);
    } catch {
      file = resolve(root, 'e2e/harness.html');
      data = await readFile(file);
    }
    const contentTypes = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' };
    response.writeHead(200, { 'Content-Type': contentTypes[extname(file)] ?? 'application/octet-stream' });
    response.end(data);
  } catch {
    if (!response.destroyed) {
      error(response, 500, 'INTERNAL_ERROR');
    }
  }
});
server.listen(18130, '127.0.0.1');
