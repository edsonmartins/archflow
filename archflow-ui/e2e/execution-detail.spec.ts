import { expect, test, type Page } from '@playwright/test';
import { adminUser, authHandlers, fulfillJson, installApiRouter, installSession } from './support/api';

async function mockApi(page: Page) {
    await installApiRouter(page, [
        ...authHandlers(adminUser, { loginShape: 'token' }),
        async ({ path, method, route }) => {
            if (path !== '/executions/exec-1' || method !== 'GET') return false;
            await fulfillJson(route, {
                id: 'exec-1',
                workflowId: 'wf-1',
                workflowName: 'Demo flow',
                status: 'COMPLETED',
                startedAt: '2026-04-23T10:00:00Z',
                completedAt: '2026-04-23T10:00:03Z',
                duration: 3000,
                traceId: 'trace-xyz',
            });
            return true;
        },
    ]);
}

test.describe('Execution detail page', () => {
    test('renders execution info and links to trace', async ({ page }) => {
        await installSession(page);
        await mockApi(page);

        await page.goto('/executions/exec-1');
        await expect(page.getByTestId('execution-detail')).toBeVisible();
        // Status badge now uses i18n: 'COMPLETED' → "Completed".
        // The raw 'COMPLETED' still appears in the JSON Code block on
        // the page, so we anchor on the human-readable badge label
        // explicitly (exact match, case-sensitive).
        await expect(page.getByText('Completed', { exact: true })).toBeVisible();
        await expect(page.getByRole('link', { name: /View trace/ })).toHaveAttribute(
            'href', /trace-xyz/);
    });
});
