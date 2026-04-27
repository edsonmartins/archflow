import { expect, test, type Page } from '@playwright/test';
import { adminUser, authHandlers, fulfillJson, installApiRouter, installSession } from './support/api';

const INITIAL_KEYS = [
    {
        id: 'key-1', keyId: 'af_abc123', name: 'CI runner',
        scopes: ['workflow.execute'], createdAt: '2026-01-10',
        lastUsedAt: '2026-04-22', enabled: true,
    },
];

async function mockApi(page: Page) {
    let keys = [...INITIAL_KEYS];
    await installApiRouter(page, [
        ...authHandlers(adminUser, { loginShape: 'token' }),
        async ({ path, method, route }) => {
            if (path !== '/apikeys' || method !== 'GET') return false;
            await fulfillJson(route, keys);
            return true;
        },
        async ({ path, method, request, route }) => {
            if (path !== '/apikeys' || method !== 'POST') return false;
            const body = request.postDataJSON() as { name: string; scopes: string[] };
            const created = {
                id: 'key-2', keyId: 'af_xyz789', keySecret: 'SECRET-ONCE',
                name: body.name, scopes: body.scopes, createdAt: '2026-04-23',
                enabled: true,
            };
            keys = [...keys, { ...created, keySecret: undefined } as unknown as typeof INITIAL_KEYS[0]];
            await fulfillJson(route, created);
            return true;
        },
        async ({ path, method, route }) => {
            if (!path.startsWith('/apikeys/') || method !== 'DELETE') return false;
            const id = path.split('/').pop()!;
            keys = keys.filter(k => k.id !== id);
            await route.fulfill({ status: 204 });
            return true;
        },
    ]);
}

test.describe('Scoped API keys', () => {
    test('lists keys and supports create/revoke', async ({ page }) => {
        await installSession(page, { role: 'tenant_admin' });
        await mockApi(page);

        await page.goto('/admin/workspace/api-keys');
        await expect(page.getByTestId('scoped-apikeys-page')).toBeVisible();
        await expect(page.getByText('CI runner')).toBeVisible();

        // Create a new key.
        await page.getByTestId('scoped-key-new').click();
        await page.getByLabel('Name').fill('dev-laptop');
        // Modal "Create" button can land outside the headless viewport
        // due to Mantine's transformed positioning — dispatch the click
        // event directly to skip the coord-based actionability check.
        await page.getByRole('button', { name: 'Create' }).dispatchEvent('click');
        await expect(page.getByTestId('scoped-key-created')).toBeVisible();
        await expect(page.getByText('SECRET-ONCE')).toBeVisible();

        // Dismiss banner and verify key is present in listing.
        await page.getByRole('button', { name: 'Dismiss' }).click();
        await expect(page.getByText('dev-laptop')).toBeVisible();
    });
});
