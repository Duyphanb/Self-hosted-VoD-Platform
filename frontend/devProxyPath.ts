// Decides which raw request paths the Vite dev proxy may forward to the Nginx edge (#121).
//
// Vite matches the raw request target, while the proxy collapses repeated slashes and Nginx decodes escaped
// characters and resolves dot segments before it picks a location. A path that any of those steps could rewrite
// is therefore refused instead of normalised here, and only an unambiguous path inside the route is forwarded.

// Encoded dot, slash or backslash; a literal backslash; an empty segment; or a "." or ".." segment.
const AMBIGUOUS_PATH = /%2e|%2f|%5c|\\|\/\/|(?:^|\/)\.{1,2}(?:\/|$)/i;

export function isForwardableProxyPath(requestUrl: string, routePrefix: string): boolean {
  const queryStart = requestUrl.search(/[?#]/);
  const rawPath = queryStart === -1 ? requestUrl : requestUrl.slice(0, queryStart);
  if (AMBIGUOUS_PATH.test(rawPath)) {
    return false;
  }
  return rawPath === routePrefix || rawPath.startsWith(`${routePrefix}/`);
}
