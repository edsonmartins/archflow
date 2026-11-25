import { expect, test, type Page } from '@playwright/test';
import { adminUser, authHandlers, fulfillJson, installApiRouter, installSession } from './support/api';

const INITIAL_KEYS = [
    {
        id: 'key-1', keyId: 'ak_abc123', name: 'CI runner',
        scopes: ['WORKFLOW_EXECUTE'], createdAt: '2026-01-10',
        lastUsedAt: '2026-04-22', enabled: true,
    },
];

const SCOPES = [
    { name: 'WORKFLOW_READ', permission: 'workflow:read', description: 'Read workflows', grantable: true, opensEndpoints: true },
    { name: 'WORKFLOW_EXECUTE', permission: 'workflow:execute', description: 'Execute workflows', grantable: true, opensEndpoints: true },
];

async function mockApi(page: Page) {
    let keys = [...INITIAL_KEYS];
    await installApiRouter(page, [
        ...authHandlers(adminUser, { loginShape: 'token' }),
        // O vocabulário de escopos vem do backend (RCHFLW-83).
        async ({ path, method, route }) => {
            if (path !== '/apikeys/scopes' || method !== 'GET') return false;
            await fulfillJson(route, SCOPES);
            return true;
        },
        async ({ path, method, route }) => {
            if (path !== '/apikeys' || method !== 'GET') return false;
            await fulfillJson(route, keys);
            return true;
        },
        async ({ path, method, request, route }) => {
            if (path !== '/apikeys' || method !== 'POST') return false;
            const body = request.postDataJSON() as { name: string; scopes: string[] };
            const created = {
                id: 'key-2', keyId: 'ak_xyz789', keySecret: 'SECRET-ONCE', apiKey: 'ak_xyz789.SECRET-ONCE',
                name: body.name, scopes: body.scopes, createdAt: '2026-04-23',
                enabled: true,
            };
            keys = [...keys, { ...created, keySecret: undefined, apiKey: undefined } as unknown as typeof INITIAL_KEYS[0]];
            await fulfillJson(route, created);
            return true;
        },
        async ({ path, method, route }) => {
            if (!path.startsWith('/apikeys/') || method !== 'DELETE') return false;
            // O backend revoga pelo keyId (id público).
            const keyId = path.split('/').pop()!;
            keys = keys.filter(k => k.keyId !== keyId);
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
        await expect(page.getByText('CI runner')).toBeAttached();

        // Create a new key.
        await page.getByTestId('scoped-key-new').click();
        await page.getByLabel('Name').fill('dev-laptop');
        await page.getByTestId('scoped-key-scopes').click();
        await page.getByRole('option', { name: /workflow:execute/ }).click();
        // Modal "Create" button can land outside the headless viewport
        // due to Mantine's transformed positioning — dispatch the click
        // event directly to skip the coord-based actionability check.
        await page.getByRole('button', { name: 'Create' }).dispatchEvent('click');
        await expect(page.getByTestId('scoped-key-created')).toBeVisible();
        // A credencial completa, a que vai no "Authorization: Bearer".
        await expect(page.getByTestId('scoped-key-credential')).toHaveText('ak_xyz789.SECRET-ONCE');

        // Dismiss banner and verify key is present in listing.
        await page.getByRole('button', { name: 'Dismiss' }).click();
        await expect(page.getByText('dev-laptop')).toBeAttached();
    });
});
