/**
 * Shared smoke coverage that is still generic enough to keep in one file.
 *
 * Feature-specific journeys live in dedicated specs:
 *   - auth-workflows.spec.ts
 *   - property-panel.spec.ts
 *   - editor-journey.spec.ts
 *   - workflow-yaml.spec.ts
 *   - approval-queue.spec.ts
 *   - approval-edit.spec.ts
 *   - conversation-streaming.spec.ts
 *   - conversation-actions.spec.ts
 *   - observability.spec.ts
 *   - observability-interactions.spec.ts
 *   - admin-role-and-mock-pages.spec.ts
 */
import { expect, test, type Page, type Route } from '@playwright/test';
import {
  adminUser,
  authHandlers,
  fulfillJson,
  installApiRouter,
  installSession,
} from './support/api';

interface MockApiOptions {
  failExecutionList?: { status?: number; message: string };
}

async function mockApi(page: Page, options: MockApiOptions = {}) {
  await installApiRouter(
    page,
    [
      ...authHandlers(adminUser, { workflows: [] }),
      async ({ path, method, route }) => {
        if (path !== '/executions' || method !== 'GET') return false;
        if (options.failExecutionList) {
          await fulfillJson(route, options.failExecutionList.message, options.failExecutionList.status ?? 500);
          return true;
        }
        await fulfillJson(route, []);
        return true;
      },
    ],
    { fallback: '404' },
  );
}

async function authenticate(page: Page) {
  await installSession(page);
}

test.describe('Archflow frontend smoke', () => {
  test('redirects protected routes to login when unauthenticated', async ({ page }) => {
    await mockApi(page);

    await page.goto('/');

    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByText('Agent orchestration platform')).toBeVisible();
  });

  test('shows execution history load errors', async ({ page }) => {
    await mockApi(page, {
      failExecutionList: { status: 500, message: 'Failed to load executions' },
    });
    await authenticate(page);

    await page.goto('/executions');

    await expect(page.getByText('Failed to load executions')).toBeVisible();
  });

  test('navigates through the app layout and logs out', async ({ page }) => {
    await mockApi(page);

    await page.goto('/login');
    await page.getByLabel('Username').fill('admin');
    await page.getByLabel('Password').fill('correct-password');
    await page.getByRole('button', { name: /sign in/i }).click();

    await expect(page).toHaveURL(/\/$/);
    await page.getByRole('button', { name: 'Toggle theme' }).click();
    await page.getByText('Editor').click();
    await expect(page).toHaveURL(/\/editor$/);

    await page.getByText('Executions').click();
    await expect(page).toHaveURL(/\/executions$/);

    await page.getByRole('button', { name: 'Logout' }).click();
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByText('Agent orchestration platform')).toBeVisible();
  });
});
