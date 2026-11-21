import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
export default defineConfig({ plugins: [react()], build: { outDir: 'dist-e2e',
  emptyOutDir: true, rollupOptions: { input: 'e2e/harness.html' } } });
