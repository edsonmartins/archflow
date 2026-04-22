import { defineConfig, devices } from '@playwright/test';

const apiBase = process.env.E2E_API_BASE ?? 'http://127.0.0.1:8080/api';

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.real.spec.ts',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: 1,
  reporter: process.env.CI ? [['html'], ['github']] : [['list']],
  use: {
    baseURL: 'http://localhost:5173',
    viewport: { width: 1366, height: 900 },
    trace: process.env.PW_TRACE ? 'on' : 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: process.env.PW_VIDEO ? 'on' : 'off',
  },
  webServer: [
    {
      command: `/bin/zsh -lc 'cd .. && JAVA_HOME=/Users/edsonmartins/.sdkman/candidates/java/25.0.2-graalce PATH=/Users/edsonmartins/.sdkman/candidates/java/25.0.2-graalce/bin:$PATH mvn -pl archflow-conversation -am -DskipTests -Dmaven.javadoc.skip=true -Dmaven.source.skip=true install && JAVA_HOME=/Users/edsonmartins/.sdkman/candidates/java/25.0.2-graalce PATH=/Users/edsonmartins/.sdkman/candidates/java/25.0.2-graalce/bin:$PATH mvn -pl archflow-api spring-boot:run'`,
      url: `${apiBase}/workflows`,
      reuseExistingServer: !process.env.CI,
      timeout: 240_000,
    },
    {
      command: `/bin/zsh -lc 'VITE_API_BASE=${apiBase} npm run dev -- --host 127.0.0.1 --port 5173'`,
      url: 'http://localhost:5173',
      reuseExistingServer: !process.env.CI,
      timeout: 60_000,
    },
  ],
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
