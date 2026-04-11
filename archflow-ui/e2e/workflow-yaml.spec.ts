import { expect, test, type Page, type Route } from '@playwright/test';

const user = {
    id: 'user-e2e',
    username: 'admin',
    name: 'Admin User',
    roles: ['admin'],
};

const workflowDetail = {
    id: 'wf-yaml',
    metadata: {
        name: 'SAC YAML Flow',
        description: 'YAML round-trip test',
        version: '1.0.0',
        category: 'support',
        tags: ['yaml', 'e2e'],
    },
    steps: [
        {
            id: 'step-1',
            type: 'AGENT',
            componentId: 'agent',
            operation: 'execute',
            position: { x: 200, y: 150 },
            configuration: { persona: 'order_tracking' },
            connections: [],
        },
    ],
    configuration: {},
};

const initialYaml = `id: wf-yaml
metadata:
  name: SAC YAML Flow
  description: YAML round-trip test
  version: 1.0.0
  category: support
  tags:
    - yaml
    - e2e
steps:
  - id: step-1
    type: AGENT
    componentId: agent
    operation: execute
    configuration:
      persona: order_tracking
`;

let storedYaml = initialYaml;

async function mockApi(page: Page) {
    storedYaml = initialYaml;
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
        if (path === '/workflows/wf-yaml' && method === 'GET') {
            await json(route, workflowDetail);
            return;
        }
        if (path === '/workflows/wf-yaml/yaml' && method === 'GET') {
            await json(route, { id: 'wf-yaml', yaml: storedYaml, version: '1.0.0' });
            return;
        }
        if (path === '/workflows/wf-yaml/yaml' && method === 'PUT') {
            const body = route.request().postDataJSON() as { yaml?: string };
            if (typeof body.yaml === 'string') storedYaml = body.yaml;
            await json(route, { id: 'wf-yaml', yaml: storedYaml, version: '1.0.1' });
            return;
        }
        if (path === '/approvals/pending/count' && method === 'GET') {
            await json(route, { count: 0 });
            return;
        }
        if (path === '/executions' && method === 'GET') {
            await json(route, []);
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

test.describe('Workflow editor YAML round-trip', () => {
    test('toggles between Canvas and Code tabs', async ({ page }) => {
        await mockApi(page);
        await authenticate(page);

        await page.goto('/editor/wf-yaml');

        // Canvas tab active by default — NodePalette is visible
        await expect(page.getByTestId('editor-tab-canvas')).toHaveAttribute('aria-selected', 'true');
        await expect(page.getByTestId('editor-tab-code')).toHaveAttribute('aria-selected', 'false');

        // Switch to Code
        await page.getByTestId('editor-tab-code').click();
        await expect(page.getByTestId('editor-tab-code')).toHaveAttribute('aria-selected', 'true');
        await expect(page.getByTestId('yaml-editor')).toBeVisible();
        await expect(page.getByTestId('yaml-editor')).toContainText('SAC YAML Flow');
        await expect(page.getByTestId('yaml-editor')).toContainText('order_tracking');
    });

    test('persists YAML edits via PUT /workflows/:id/yaml', async ({ page }) => {
        await mockApi(page);
        await authenticate(page);

        await page.goto('/editor/wf-yaml');
        await page.getByTestId('editor-tab-code').click();

        const textarea = page.locator('[data-testid="yaml-editor"] textarea');
        await expect(textarea).toBeVisible();

        const nextYaml = initialYaml.replace('SAC YAML Flow', 'SAC YAML Flow v2');
        await textarea.fill(nextYaml);

        // Save button reflects YAML mode
        const saveBtn = page.getByTestId('editor-save');
        await expect(saveBtn).toHaveText(/Save YAML/);
        await saveBtn.click();

        // After save the stored YAML should reflect the edit (mock stores it)
        await expect(page.getByText('YAML saved')).toBeVisible();

        // Switch back to canvas and return to code — should show updated YAML
        await page.getByTestId('editor-tab-canvas').click();
        await page.getByTestId('editor-tab-code').click();
        await expect(page.getByTestId('yaml-editor')).toContainText('SAC YAML Flow v2');
    });
});
