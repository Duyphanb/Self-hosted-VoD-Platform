import { defineConfig, devices } from '@playwright/test';
const external = process.env.VOD_E2E_BASE_URL;

export default defineConfig({
  testDir: './e2e',
  testMatch: external ? 'stack.spec.ts' : ['session.spec.ts', 'player.spec.ts'],
  outputDir: external ? 'test-results-stack' : 'test-results',
  fullyParallel: false,
  workers: 1,
  timeout: 45_000,
  reporter: 'list',
  use: { baseURL: external ?? 'http://127.0.0.1:18130', trace: 'off', screenshot: 'off', video: 'off' },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } }
  ],
  webServer: external ? undefined : {
    command: 'node e2e/server.mjs',
    url: 'http://127.0.0.1:18130',
    reuseExistingServer: false
  }
});
