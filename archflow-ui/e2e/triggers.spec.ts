import { expect, test, type Page } from '@playwright/test';
import { adminUser, authHandlers, fulfillJson, installApiRouter, installSession } from './support/api';

const INITIAL = [
    {
        id: 'trg-abc12345',
        name: 'Nightly digest',
        cronExpression: '0 0 2 * * ?',
        tenantId: 'tenant_demo',
        agentId: 'data-analysis-agent',
        payload: {},
        enabled: true,
        createdAt: '2026-04-20T10:00:00Z',
        lastFiredAt: null,
        lastError: null,
    },
];

async function mockApi(page: Page) {
    let items = [...INITIAL];
    await installApiRouter(page, [
        ...authHandlers(adminUser, { loginShape: 'token' }),
        async ({ path, method, route }) => {
            if (path === '/admin/triggers' && method === 'GET') {
                await fulfillJson(route, items);
                return true;
            }
            return false;
        },
        async ({ path, method, route, request }) => {
            if (path === '/admin/triggers' && method === 'POST') {
                const body = request.postDataJSON() as Record<string, unknown>;
                const created = {
                    id: 'trg-xyz98765',
                    ...body,
                    createdAt: new Date().toISOString(),
                    lastFiredAt: null,
                    lastError: null,
                };
                items = [...items, created as typeof items[0]];
                await fulfillJson(route, created, 201);
                return true;
            }
            return false;
        },
        async ({ path, method, route }) => {
            if (/^\/admin\/triggers\/[^/]+\/fire$/.test(path) && method === 'POST') {
                const id = path.split('/')[3];
                const t = items.find(x => x.id === id);
                await fulfillJson(route, {
                    ...t, lastFiredAt: new Date().toISOString(),
                });
                return true;
            }
            return false;
        },
        async ({ path, method, route }) => {
            if (/^\/admin\/triggers\/[^/]+$/.test(path) && method === 'DELETE') {
                const id = path.split('/').pop()!;
                items = items.filter(x => x.id !== id);
                await route.fulfill({ status: 204 });
                return true;
            }
            return false;
        },
        // Provide a single agent in /catalog/agents so the modal's
        // Select has something to pick. Other catalog kinds return
        // empty (the dropdown is agent-only).
        async ({ path, method, route }) => {
            if (path === '/catalog/agents' && method === 'GET') {
                await fulfillJson(route, [
                    { id: 'conversational-agent', displayName: 'Conversational Agent', kind: 'agent' },
                ]);
                return true;
            }
            if (path.startsWith('/catalog/') && method === 'GET') {
                await fulfillJson(route, []);
                return true;
            }
            return false;
        },
    ]);
}

test.describe('Scheduled triggers', () => {
    test('lists existing triggers and fires one manually', async ({ page }) => {
        await installSession(page, { role: 'tenant_admin' });
        await mockApi(page);

        await page.goto('/admin/triggers');
        await expect(page.getByTestId('triggers-page')).toBeVisible();
        await expect(page.getByText('Nightly digest')).toBeVisible();

        await page.getByTestId('trigger-trg-abc12345')
            .getByRole('button', { name: 'Fire now' }).click();
        await expect(page.getByText(/Submitted invocation/)).toBeVisible();
    });

    test('creates a new trigger via the modal', async ({ page }) => {
        await installSession(page, { role: 'tenant_admin' });
        await mockApi(page);

        await page.goto('/admin/triggers');
        await page.getByTestId('trigger-new').click();
        // Mantine v7 keeps the modal-root in the DOM with visibility:hidden
        // during the open transition, so toBeVisible() against the root
        // can race. Wait on the inner inputs instead — they only render
        // when the modal body is fully attached and visible.
        await expect(page.getByTestId('trigger-name')).toBeVisible();
        await page.getByTestId('trigger-name').fill('Hourly health');
        await page.getByTestId('trigger-cron').fill('0 0 * * * ?');
        // Fill tenantId manually via the 3rd input (label "Tenant id")
        await page.getByLabel('Tenant id').fill('tenant_demo');
        // Fallback catalog puts "Conversational Agent" in the dropdown.
        // Mantine v7's Select input is inside a Modal that the headless
        // viewport guard rejects — dispatch the click directly.
        await page.getByTestId('trigger-agent').dispatchEvent('click');
        await page.getByRole('option', { name: /Conversational/ }).click();
        // Mantine v7's Modal can position the save button outside the
        // computed viewport in headless Chromium — dispatch a synthetic
        // click event so Playwright doesn't reject on coordinates.
        await page.getByTestId('trigger-save').dispatchEvent('click');

        // Modal closes and the list reloads; we assert no error toast.
        await expect(page.getByText(/failed/i)).toHaveCount(0);
    });
});
