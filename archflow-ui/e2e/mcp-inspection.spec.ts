import { expect, test, type Page } from '@playwright/test';
import { adminUser, authHandlers, fulfillJson, installApiRouter, installSession } from './support/api';

const SERVERS = ['memory-server', 'workflow-server'];

const INTROSPECTION = {
    serverName: 'memory-server',
    connected: true,
    tools: [
        {
            name: 'store-memory',
            description: 'Persist a memory entry',
            inputSchema: { type: 'object', properties: { content: { type: 'string' } } },
        },
    ],
    prompts: [
        { name: 'summarize', description: 'Summarise recent memories',
          arguments: [{ name: 'limit', description: 'Max entries', required: false }] },
    ],
    resources: [
        { uri: 'memory://recent', name: 'recent-memories',
          description: 'Latest 50 entries', mimeType: 'application/json' },
    ],
    error: null,
};

async function mockApi(page: Page) {
    await installApiRouter(page, [
        ...authHandlers(adminUser, { loginShape: 'token' }),
        async ({ path, method, route }) => {
            if (path !== '/admin/mcp/servers' || method !== 'GET') return false;
            await fulfillJson(route, SERVERS);
            return true;
        },
        async ({ path, method, route }) => {
            if (!path.startsWith('/admin/mcp/servers/') || method !== 'GET') return false;
            await fulfillJson(route, INTROSPECTION);
            return true;
        },
    ]);
}

test.describe('MCP inspection', () => {
    test('lists registered servers', async ({ page }) => {
        await installSession(page, { role: 'tenant_admin' });
        await mockApi(page);

        await page.goto('/admin/mcp');
        await expect(page.getByTestId('mcp-servers-page')).toBeVisible();
        await expect(page.getByTestId('mcp-memory-server')).toBeVisible();
        await expect(page.getByTestId('mcp-workflow-server')).toBeVisible();
    });

    test('opens server detail and shows tools/prompts/resources', async ({ page }) => {
        await installSession(page, { role: 'tenant_admin' });
        await mockApi(page);

        await page.goto('/admin/mcp/memory-server');
        await expect(page.getByTestId('mcp-detail')).toBeVisible();
        await expect(page.getByTestId('mcp-tool-store-memory')).toBeVisible();
        await expect(page.getByText('summarize')).toBeVisible();
        await expect(page.getByText('recent-memories')).toBeVisible();
    });
});
