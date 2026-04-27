import { expect, test, type Page } from '@playwright/test';
import { adminUser, authHandlers, fulfillJson, installApiRouter, installSession } from './support/api';

const EXTENSIONS = [
    {
        id: 'ext-weather:1.0.0',
        name: 'weather',
        version: '1.0.0',
        displayName: 'Weather Tool',
        author: 'ArchFlow',
        description: 'Fetches the current weather for a city',
        type: 'tool',
        permissions: ['http'],
        installed: true,
    },
    {
        id: 'ext-slack:2.1.0',
        name: 'slack',
        version: '2.1.0',
        displayName: 'Slack Notifier',
        author: 'Community',
        description: 'Sends notifications to Slack channels',
        type: 'notifier',
        permissions: ['http', 'secrets'],
        installed: false,
    },
];

async function mockApi(page: Page) {
    await installApiRouter(page, [
        ...authHandlers(adminUser, { loginShape: 'token' }),
        async ({ path, method, route }) => {
            if (path !== '/extensions' || method !== 'GET') return false;
            await fulfillJson(route, EXTENSIONS);
            return true;
        },
        async ({ path, method, route }) => {
            if (!path.startsWith('/extensions/search') || method !== 'GET') return false;
            await fulfillJson(route, EXTENSIONS);
            return true;
        },
        async ({ path, method, route }) => {
            if (path !== '/extensions/install' || method !== 'POST') return false;
            await fulfillJson(route, { ...EXTENSIONS[1], installed: true });
            return true;
        },
        async ({ path, method, route }) => {
            if (!path.startsWith('/extensions/') || method !== 'DELETE') return false;
            await route.fulfill({ status: 204 });
            return true;
        },
    ]);
}

test.describe('Marketplace page', () => {
    test('lists extensions from the backend', async ({ page }) => {
        await installSession(page);
        await mockApi(page);

        await page.goto('/marketplace');
        await expect(page.getByTestId('marketplace-page')).toBeVisible();
        await expect(page.getByText('Weather Tool')).toBeVisible();
        await expect(page.getByText('Slack Notifier')).toBeVisible();
    });

    test('can install a new extension via the modal', async ({ page }) => {
        await installSession(page);
        await mockApi(page);

        await page.goto('/marketplace');
        await page.getByTestId('marketplace-install-btn').click();
        await page.getByTestId('manifest-path').fill('/opt/archflow/extensions/slack/manifest.json');
        // Mantine v7's Modal positions interior content with a CSS
        // transform that confuses Playwright's viewport calculation in
        // headless Chromium. dispatchEvent fires a synthetic click on
        // the element without going through real mouse coordinates.
        await page.getByTestId('install-submit').dispatchEvent('click');
        await expect(page.getByText('installed successfully')).toBeVisible();
    });

    test('can uninstall an installed extension', async ({ page }) => {
        await installSession(page);
        await mockApi(page);

        await page.goto('/marketplace');
        await page.getByTestId('uninstall-ext-weather:1.0.0').click();
        await expect(page.getByText(/removed/)).toBeVisible();
    });
});
