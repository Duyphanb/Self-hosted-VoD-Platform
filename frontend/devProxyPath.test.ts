import { describe, expect, it } from 'vitest';
import { isForwardableProxyPath } from './devProxyPath';

describe('isForwardableProxyPath', () => {
  describe('forwards unambiguous paths inside the route', () => {
    it.each([
      ['/api/v1', '/api/v1'],
      ['/api/v1/health', '/api/v1'],
      ['/api/v1/movies?page=2&size=20', '/api/v1'],
      ['/api/v1/movies?q=../x', '/api/v1'],
      ['/hls/asset/master.m3u8', '/hls'],
      ['/hls/asset/segments/000.ts', '/hls']
    ])('%s', (requestUrl, routePrefix) => {
      expect(isForwardableProxyPath(requestUrl, routePrefix)).toBe(true);
    });
  });

  describe('refuses paths outside the route', () => {
    it.each([
      ['/api/v10/health', '/api/v1'],
      ['/actuator/health', '/api/v1'],
      ['/hlsx/master.m3u8', '/hls']
    ])('%s', (requestUrl, routePrefix) => {
      expect(isForwardableProxyPath(requestUrl, routePrefix)).toBe(false);
    });
  });

  describe('refuses paths that the proxy or Nginx could rewrite', () => {
    it.each([
      ['/api/v1/../actuator/health', '/api/v1'],
      ['/api/v1/./health', '/api/v1'],
      ['/api/v1/%2e%2e/actuator/health', '/api/v1'],
      ['/api/v1/%2E%2E/actuator/health', '/api/v1'],
      ['/api/v1/..%2factuator/health', '/api/v1'],
      ['/api/v1/..%2F..%2Factuator/health', '/api/v1'],
      ['/api/v1//../actuator/health', '/api/v1'],
      ['/api/v1/..%5cactuator/health', '/api/v1'],
      ['/api/v1/..\\actuator/health', '/api/v1'],
      ['/hls/x/../../actuator/health', '/hls'],
      ['/hls//master.m3u8', '/hls']
    ])('%s', (requestUrl, routePrefix) => {
      expect(isForwardableProxyPath(requestUrl, routePrefix)).toBe(false);
    });
  });
});
