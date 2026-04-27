import { expect, test, type Page } from '@playwright/test';
import { adminUser, authHandlers, fulfillJson, installApiRouter, installSession } from './support/api';

const INITIAL = {
    enabled: false,
    baseUrl: 'http://localhost:8081/api',
    apiKey: 'abcd…wxyz',
    tenantId: '',
    maxTokenBudget: 2000,
    deepAnalysisEnabled: false,
    timeoutSeconds: 10,
};

async function mockApi(page: Page) {
    let current = { ...INITIAL };
    await installApiRouter(page, [
        ...authHandlers(adminUser, { loginShape: 'token' }),
        async ({ path, method, route }) => {
            if (path !== '/admin/brainsentry' || method !== 'GET') return false;
            await fulfillJson(route, current);
            return true;
        },
        async ({ path, method, request, route }) => {
            if (path !== '/admin/brainsentry' || method !== 'PUT') return false;
            const body = request.postDataJSON() as Record<string, unknown>;
            current = { ...current, ...body, apiKey: 'abcd…wxyz' };
            await fulfillJson(route, current);
            return true;
        },
    ]);
}

test.describe('BrainSentry config admin', () => {
    test('loads current config and can toggle enable flag', async ({ page }) => {
        await installSession(page, { role: 'tenant_admin' });
        await mockApi(page);

        await page.goto('/admin/brainsentry');
        await expect(page.getByTestId('brainsentry-config')).toBeVisible();
        await expect(page.getByTestId('bs-base-url')).toHaveValue('http://localhost:8081/api');
        // Mantine v7 forwards data-testid to a hidden <input role="switch">.
        // Click the parent label (visible) to toggle the underlying input.
        await page.getByTestId('bs-enabled').locator('xpath=..').click();
        await page.getByTestId('bs-save').click();
        await expect(page.getByText('Configuration saved')).toBeVisible();
    });
});
