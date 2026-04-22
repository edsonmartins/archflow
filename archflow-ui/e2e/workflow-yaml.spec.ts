import { expect, test, type Page } from '@playwright/test';
import { adminUser, authHandlers, fulfillJson, installApiRouter, installSession } from './support/api';

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
    await installApiRouter(page, [
        ...authHandlers(adminUser, { loginShape: 'token', workflows: [] }),
        async ({ path, method, route }) => {
            if (path !== '/workflows/wf-yaml' || method !== 'GET') return false;
            await fulfillJson(route, workflowDetail);
            return true;
        },
        async ({ path, method, route }) => {
            if (path !== '/workflows/wf-yaml/yaml' || method !== 'GET') return false;
            await fulfillJson(route, { id: 'wf-yaml', yaml: storedYaml, version: '1.0.0' });
            return true;
        },
        async ({ path, method, request, route }) => {
            if (path !== '/workflows/wf-yaml/yaml' || method !== 'PUT') return false;
            const body = request.postDataJSON() as { yaml?: string };
            if (typeof body.yaml === 'string') storedYaml = body.yaml;
            await fulfillJson(route, { id: 'wf-yaml', yaml: storedYaml, version: '1.0.1' });
            return true;
        },
        async ({ path, method, route }) => {
            if (!path.startsWith('/workflow/') || method !== 'GET') return false;
            if (['/workflow/providers', '/workflow/agent-patterns', '/workflow/personas', '/workflow/governance-profiles', '/workflow/mcp-servers'].includes(path)) {
                await fulfillJson(route, []);
                return true;
            }
            return false;
        },
        async ({ path, method, route }) => {
            if (path !== '/executions' || method !== 'GET') return false;
            await fulfillJson(route, []);
            return true;
        },
    ]);
}

async function authenticate(page: Page) {
    await installSession(page);
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
