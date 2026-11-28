import { defineConfig, configDefaults } from 'vitest/config';
import react from '@vitejs/plugin-react';
import type { IncomingMessage } from 'node:http';

// This runs in Node. The browser keeps using relative API and HLS URLs.
function edgeProxy(routePrefix: string) {
  return {
    target: process.env.VOD_DEV_PROXY_TARGET || 'http://localhost',
    changeOrigin: true,
    // Vite matches the raw request path, but Nginx resolves dot segments before it picks a location. A request
    // whose resolved path leaves the proxied route gets Vite's 404 instead of reaching another edge route.
    bypass(request: IncomingMessage) {
      const resolvedPath = new URL(request.url ?? '/', 'http://localhost').pathname;
      const staysInRoute = resolvedPath === routePrefix || resolvedPath.startsWith(`${routePrefix}/`);
      return staysInRoute ? undefined : false;
    }
  };
}

export default defineConfig(({ mode }) => ({
  plugins: [react()],
  test: {
    exclude: [...configDefaults.exclude, 'e2e/**'],
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts'
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    // changeOrigin only sets the upstream Host. ADR-012 checks the browser's original Origin.
    proxy: {
      '^/api/v1(?:/|\\?|$)': edgeProxy('/api/v1'),
      '^/hls(?:/|\\?|$)': edgeProxy('/hls')
    },
    // Tests read the frozen API contract (`?raw`); the dev server keeps Vite's default file access.
    ...(mode === 'test' ? { fs: { allow: ['.', '../docs/architecture'] } } : {})
  },
  preview: {
    host: '127.0.0.1',
    port: 4173,
    strictPort: true,
    proxy: {}
  }
}));
