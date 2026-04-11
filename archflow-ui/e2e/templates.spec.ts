import { expect, test, type Page, type Route } from '@playwright/test';

const user = {
    id: 'user-e2e',
    username: 'admin',
    name: 'Admin User',
    roles: ['admin'],
};

let createdWorkflowId = 'wf-from-template';

async function mockApi(page: Page) {
    await page.route('**/api/**', async (route) => {
        const url = new URL(route.request().url());
        const path = url.pathname.replace(/^\/api/, '');
        const method = route.request().method();

        if (path === '/auth/login' && method === 'POST') {
            await json(route, { token: 'e2e-token', refreshToken: 'e2e-refresh-token' });
            return;
        }
        if (path === '/auth/me' && method === 'GET') {
            await json(route, user);
            return;
        }
        if (path === '/workflows' && method === 'POST') {
            const body = route.request().postDataJSON() as Record<string, unknown>;
            const meta = (body?.metadata ?? {}) as Record<string, unknown>;
            const created = {
                id: createdWorkflowId,
                metadata: meta,
                steps: body?.steps ?? [],
                configuration: body?.configuration ?? {},
            };
            await json(route, created, 201);
            return;
        }
        if (path === `/workflows/${createdWorkflowId}` && method === 'GET') {
            await json(route, {
                id: createdWorkflowId,
                metadata: {
                    name: 'Customer support agent (copy)',
                    description: 'cloned from template',
                    version: '1.0.0',
                    category: 'customer_support',
                    tags: ['from-template'],
                },
                steps: [],
                configuration: {},
            });
            return;
        }
        await route.continue();
    });
}

async function authenticate(page: Page) {
    await page.addInitScript(() => {
        localStorage.setItem('archflow_token', 'e2e-token');
        localStorage.setItem('archflow_refresh_token', 'e2e-refresh-token');
    });
}

async function json(route: Route, body: unknown, status = 200) {
    await route.fulfill({
        status,
        contentType: 'application/json',
        body: JSON.stringify(body),
    });
}

test.describe('Templates gallery', () => {
    test('lists templates and filters by category', async ({ page }) => {
        await mockApi(page);
        await authenticate(page);

        await page.goto('/templates');

        await expect(page.getByRole('heading', { name: 'Templates' })).toBeVisible();
        await expect(page.getByText('Customer support agent')).toBeVisible();
        await expect(page.getByText('Document Q&A (RAG)')).toBeVisible();
        await expect(page.getByText('Code reviewer')).toBeVisible();

        // Filter by Code chip — Mantine Chip renders as a label, click directly
        await page.locator('label').filter({ hasText: /^Code$/ }).click();
        await expect(page.getByText('Code reviewer')).toBeVisible();
        await expect(page.getByText('Customer support agent')).toBeHidden();

        // Reset filter and search by tag
        await page.locator('label').filter({ hasText: /^All$/ }).click();
        await page.getByTestId('templates-search').fill('rag');
        await expect(page.getByText('Document Q&A (RAG)')).toBeVisible();
        await expect(page.getByText('Customer support agent')).toBeHidden();
    });

    test('opens detail page and clones into a workflow', async ({ page }) => {
        await mockApi(page);
        await authenticate(page);

        await page.goto('/templates');

        await page.getByTestId('template-card-sac-basic').click();
        await expect(page).toHaveURL(/\/templates\/sac-basic$/);
        await expect(page.getByRole('heading', { name: 'Customer support agent' })).toBeVisible();
        // The pipeline list shows step labels — match the first occurrence
        // (the same text also appears inside the JSON code block).
        await expect(page.getByText('Inbound message').first()).toBeVisible();

        await page.getByTestId('template-clone').click();
        await expect(page).toHaveURL(new RegExp(`/editor/${createdWorkflowId}$`));
    });

    test('filters by complexity', async ({ page }) => {
        await mockApi(page);
        await authenticate(page);

        await page.goto('/templates');

        await page.getByTestId('templates-complexity').click();
        await page.getByRole('option', { name: 'Starter' }).click();

        // Only the starter template should remain (Document Q&A is starter)
        await expect(page.getByText('Document Q&A (RAG)')).toBeVisible();
        await expect(page.getByText('Customer support agent')).toBeHidden();
        await expect(page.getByText('Web research → report')).toBeHidden();

        // Reset
        await page.getByTestId('templates-complexity').click();
        await page.getByRole('option', { name: 'Any complexity' }).click();
        await expect(page.getByText('Customer support agent')).toBeVisible();
    });

    test('shows not found for unknown template id', async ({ page }) => {
        await mockApi(page);
        await authenticate(page);

        await page.goto('/templates/does-not-exist');
        await expect(page.getByText('Template not found.')).toBeVisible();
    });
});
