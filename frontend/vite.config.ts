import { defineConfig, configDefaults } from 'vitest/config';
import react from '@vitejs/plugin-react';

// This runs in Node. The browser keeps using relative API and HLS URLs.
const edgeProxy = { target: process.env.VOD_DEV_PROXY_TARGET || 'http://localhost', changeOrigin: true };

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
      '^/api/v1(?:/|\\?|$)': edgeProxy,
      '^/hls(?:/|\\?|$)': edgeProxy
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
