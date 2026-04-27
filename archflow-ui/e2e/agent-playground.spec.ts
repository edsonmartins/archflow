import { expect, test, type Page } from '@playwright/test';
import { adminUser, authHandlers, fulfillJson, installApiRouter, installSession } from './support/api';

async function mockApi(page: Page) {
    // The agent invoke endpoint sits under `/archflow/...` (not `/api/...`),
    // so installApiRouter — which only intercepts `**/api/**` — would never
    // see it. Register a dedicated route for the archflow namespace before
    // delegating the rest to the standard mock surface.
    await page.route('**/archflow/agents/*/invoke', async (route) => {
        await fulfillJson(route, {
            requestId: 'req-e2e-1',
            tenantId: 'tenant_demo',
            agentId: 'conversational-agent',
            status: 'ACCEPTED',
            timestamp: new Date().toISOString(),
        });
    });
    await page.route('**/archflow/events/message', async (route) => {
        await fulfillJson(route, {
            requestId: 'req-e2e-1',
            status: 'ACCEPTED',
        });
    });
    await installApiRouter(page, [
        ...authHandlers(adminUser, { loginShape: 'token' }),
        async ({ path, method, route }) => {
            if (path !== '/catalog/agents' || method !== 'GET') return false;
            await fulfillJson(route, [
                { id: 'conversational-agent', displayName: 'Conversational', kind: 'agent' },
            ]);
            return true;
        },
        async ({ path, method, route }) => {
            if (path !== '/catalog/assistants' || method !== 'GET') return false;
            await fulfillJson(route, [
                { id: 'tech-support-assistant', displayName: 'Tech Support', kind: 'assistant' },
            ]);
            return true;
        },
        async ({ path, method, route }) => {
            if (!['/catalog/tools', '/catalog/embeddings', '/catalog/memories',
                  '/catalog/vectorstores', '/catalog/chains'].includes(path)) return false;
            if (method !== 'GET') return false;
            await fulfillJson(route, []);
            return true;
        },
    ]);
}

test.describe('Agent playground', () => {
    test('invokes an agent and shows the response', async ({ page }) => {
        await installSession(page);
        await mockApi(page);

        await page.goto('/playground/agent');
        await expect(page.getByTestId('agent-playground')).toBeVisible();

        // Select the agent.
        await page.getByTestId('pg-agent').click();
        await page.getByRole('option', { name: /Conversational/ }).click();

        // Fill payload and invoke.
        await page.getByTestId('pg-payload').fill('{"topic":"weather"}');
        await page.getByTestId('pg-invoke-btn').click();

        // The requestId appears in both the response Code block and the
        // success notification toast — `.first()` keeps the assertion
        // unambiguous regardless of toast lifecycle.
        await expect(page.getByText(/req-e2e-1/).first()).toBeVisible();
    });
});
