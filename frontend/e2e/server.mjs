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
      return json(response, 200, {});
    }
    if (path === '/__test/control') {
      Object.assign(control, await body(request));
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
