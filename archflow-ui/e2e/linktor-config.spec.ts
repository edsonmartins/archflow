import { expect, test, type Page } from '@playwright/test';
import { adminUser, authHandlers, fulfillJson, installApiRouter, installSession } from './support/api';

const INITIAL = {
    enabled: false,
    apiBaseUrl: 'http://localhost:8081/api/v1',
    apiKey: 'abcd…wxyz',
    accessToken: '',
    mcpCommand: 'linktor-mcp',
    timeoutSeconds: 30,
};

async function mockApi(page: Page) {
    let current = { ...INITIAL };
    await installApiRouter(page, [
        ...authHandlers(adminUser, { loginShape: 'token' }),
        async ({ path, method, route }) => {
            if (path !== '/admin/linktor' || method !== 'GET') return false;
            await fulfillJson(route, current);
            return true;
        },
        async ({ path, method, request, route }) => {
            if (path !== '/admin/linktor' || method !== 'PUT') return false;
            const body = request.postDataJSON() as Record<string, unknown>;
            current = { ...current, ...body, apiKey: 'abcd…wxyz' };
            await fulfillJson(route, current);
            return true;
        },
    ]);
}

test.describe('Linktor config admin', () => {
    test('loads current config, toggles enable and saves', async ({ page }) => {
        await installSession(page, { role: 'tenant_admin' });
        await mockApi(page);

        await page.goto('/admin/linktor');
        await expect(page.getByTestId('linktor-config')).toBeVisible();
        await expect(page.getByTestId('linktor-base-url'))
            .toHaveValue('http://localhost:8081/api/v1');
        await expect(page.getByTestId('linktor-mcp-command'))
            .toHaveValue('linktor-mcp');

        // Mantine v7 forwards data-testid to a hidden <input role="switch">.
        // Click the parent label (visible) to toggle the underlying input.
        await page.getByTestId('linktor-enabled').locator('xpath=..').click();
        await page.getByTestId('linktor-save').click();
        await expect(page.getByText(/Configuration saved/)).toBeVisible();
    });
});
