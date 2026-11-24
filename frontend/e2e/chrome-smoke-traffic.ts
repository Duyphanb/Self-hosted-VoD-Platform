import type { Page, Request, Response } from '@playwright/test';

export const refreshRoute = '/api/v1/auth/refresh';
export const loginRoute = '/api/v1/auth/login';

export type Exchange = {
  request: Request;
  response?: Response;
  route: string;
  document: number;
  started: number;
  completed: number;
  at: number;
};

export function watchTraffic(page: Page, origin: string, hlsPrefix: string, protectedRoutes: string[]) {
  const exchanges = new Map<Request, Exchange>();
  let order = 0;
  let document = 0;
  let unsafeHlsUrl = false;
  page.on('request', request => {
    if (request.isNavigationRequest() && request.frame() === page.mainFrame()) {
      document++;
    }
    const url = new URL(request.url());
    if (url.origin !== origin) return;
    const route = url.pathname;
    const isHls = route.startsWith(hlsPrefix);
    if (isHls && request.url().includes('?')) {
      unsafeHlsUrl = true;
    }
    const isRefresh = route === refreshRoute && request.method() === 'POST';
    if (!isHls && !isRefresh && !protectedRoutes.includes(route)) return;
    exchanges.set(request, { request, route, document, started: ++order, completed: 0, at: 0 });
  });
  page.on('response', response => {
    const exchange = exchanges.get(response.request());
    if (!exchange) return;
    exchange.response = response;
    exchange.completed = ++order;
    exchange.at = Date.now();
  });
  return {
    document: () => document,
    current: (epoch = document) => Array.from(exchanges.values()).filter(exchange => exchange.document === epoch),
    safeHlsUrls: () => !unsafeHlsUrl
  };
}

export async function issuedToken(response: Response | undefined): Promise<string | null> {
  if (response?.status() !== 200) return null;
  const data = await response.json();
  return typeof data.accessToken === 'string' && data.accessToken ? data.accessToken : null;
}

export async function hasBearer(exchange: Exchange, token: string | null): Promise<boolean> {
  return token !== null && await exchange.request.headerValue('authorization') === `Bearer ${token}`;
}

export function tokenTimes(token: string) {
  const claims = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8'));
  if (!Number.isInteger(claims.iat) || !Number.isInteger(claims.exp)) {
    throw new Error('The fixture access token requires iat and exp claims');
  }
  return { issued: Number(claims.iat) * 1000, expires: Number(claims.exp) * 1000 };
}

export function recovery(exchanges: Exchange[], hlsPrefix: string, progressRoute: string) {
  const hlsDenied = exchanges.find(exchange => exchange.route.startsWith(hlsPrefix)
    && exchange.response?.status() === 401);
  const progressDenied = exchanges.find(exchange => exchange.route === progressRoute
    && exchange.request.method() === 'PUT' && exchange.response?.status() === 401);
  const refresh = exchanges.find(exchange => exchange.route === refreshRoute);
  if (!hlsDenied || !progressDenied || !refresh?.completed) return null;
  const hlsRetry = exchanges.find(exchange => exchange.route === hlsDenied.route
    && exchange.started > refresh.completed && exchange.response?.status() === 200);
  const progressRetry = exchanges.find(exchange => exchange.route === progressRoute
    && exchange.request.method() === 'PUT' && exchange.started > progressDenied.completed
    && exchange.request.postData() === progressDenied.request.postData() && exchange.response?.status() === 200);
  if (!hlsRetry || !progressRetry) return null;
  return { hlsDenied, progressDenied, refresh, hlsRetry, progressRetry };
}
