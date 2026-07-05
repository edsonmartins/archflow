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
  // The heaviest journeys (editor-journey, auth-workflows) drive ~30 UI steps
  // over a React Flow + Mantine canvas; the default 30s test timeout is marginal
  // on a slow/contended CI runner (trace-on-retry adds more), so give headroom.
  timeout: process.env.CI ? 60_000 : 30_000,
  reporter: process.env.CI ? [['html'], ['github']] : [['list']],
  use: {
    baseURL: 'http://127.0.0.1:4173',
    viewport: { width: 1366, height: 900 },
    trace: process.env.PW_TRACE ? 'on' : 'on-first-retry',
    screenshot: 'only-on-failure',
    video: process.env.PW_VIDEO ? 'on' : 'off',
  },
  webServer: {
    // `npm run dev` (not pnpm): Playwright spawns this in a subshell where pnpm
    // isn't reliably on PATH in CI, which silently failed to start the server
    // and tripped the webServer startup timeout. npm is always available and
    // runs vite from the pnpm-installed node_modules/.bin all the same.
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
