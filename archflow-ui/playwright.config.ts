import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  // *.real.spec.ts targets the live Spring backend (port 8080) — those
  // need an actual server, run via `npm run test:e2e:real`. Excluding
  // them here keeps the mock-based suite hermetic.
  testIgnore: '**/*.real.spec.ts',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI ? [['html'], ['github']] : [['list']],
  use: {
    baseURL: 'http://127.0.0.1:4173',
    viewport: { width: 1366, height: 900 },
    trace: process.env.PW_TRACE ? 'on' : 'on-first-retry',
    screenshot: 'only-on-failure',
    video: process.env.PW_VIDEO ? 'on' : 'off',
  },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 4173',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: !process.env.CI,
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
