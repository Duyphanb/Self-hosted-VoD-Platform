import { defineConfig, devices } from '@playwright/test';

const baseURL = new URL(process.env.VOD_CHROME_BASE_URL ?? 'about:blank');
const outputDir = process.env.VOD_CHROME_OUTPUT;
if (baseURL.protocol !== 'http:' || baseURL.hostname !== '127.0.0.1' || !baseURL.port
  || baseURL.username || baseURL.password || baseURL.href !== baseURL.origin + '/') {
  throw new Error('Chrome smoke requires an HTTP loopback origin with an explicit port');
}
if (!outputDir) {
  throw new Error('Chrome smoke requires the output directory allocated by its driver');
}

export default defineConfig({
  testDir: './e2e',
  testMatch: 'chrome-playback.spec.ts',
  outputDir,
  workers: 1,
  retries: 0,
  timeout: 240_000,
  reporter: './e2e/chrome-smoke-reporter.ts',
  use: { baseURL: baseURL.origin, trace: 'off', screenshot: 'off', video: 'off' },
  projects: [{ name: 'chrome', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }]
});
