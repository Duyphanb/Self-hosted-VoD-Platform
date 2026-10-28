import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => ({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts'
  },
  server: {
    port: 5173,
    strictPort: true,
    // Tests read the frozen API contract (`?raw`); the dev server keeps Vite's default file access.
    ...(mode === 'test' ? { fs: { allow: ['.', '../docs/architecture'] } } : {})
  },
  preview: {
    port: 4173,
    strictPort: true
  }
}));
