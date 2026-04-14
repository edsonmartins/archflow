import { expect, test, type Page, type Route } from '@playwright/test';

/**
 * End-to-end coverage for the workflow editor PropertyPanel.
 *
 * Exercises the full round-trip that the unit tests cannot: click an
 * agent node on the canvas, edit its fields (model, pattern,
 * temperature, timeout, governance), save, and verify the backend
 * received the merged configuration. The mock handler captures the PUT
 * payload so assertions can inspect exactly which keys were persisted.
 */

const user = {
  id: 'user-e2e',
  username: 'admin',
  name: 'Admin User',
  roles: ['admin'],
};

type WorkflowDetail = {
  id: string;
  metadata: { name: string; description: string; version: string; category: string; tags: string[] };
  steps: Array<{
    id: string;
    type: string;
    componentId: string;
    operation: string;
    position: { x: number; y: number };
    configuration: Record<string, unknown>;
    connections: Array<{ sourceId: string; targetId: string; isErrorPath: boolean }>;
  }>;
  configuration: Record<string, unknown>;
};

const providers = [
  {
    id: 'anthropic', displayName: 'Anthropic', requiresApiKey: true,
    supportsStreaming: true, group: 'Cloud',
    models: [
      { id: 'claude-sonnet-4-6', name: 'Claude Sonnet 4.6', contextWindow: 200000, maxTemperature: 1.0 },
      { id: 'claude-opus-4-6',   name: 'Claude Opus 4.6',   contextWindow: 200000, maxTemperature: 1.0 },
    ],
  },
  {
    id: 'openai', displayName: 'OpenAI', requiresApiKey: true,
    supportsStreaming: true, group: 'Cloud',
    models: [
      { id: 'gpt-4o',      name: 'GPT-4o',      contextWindow: 128000, maxTemperature: 2.0 },
      { id: 'gpt-4o-mini', name: 'GPT-4o Mini', contextWindow: 128000, maxTemperature: 2.0 },
    ],
  },
];

const patterns = [
  { id: 'react',            label: 'ReAct (Reason + Act)',              description: 'Iterative loop' },
  { id: 'plan-execute',     label: 'Plan and Execute',                  description: 'Plan then execute' },
  { id: 'rewoo',            label: 'ReWOO',                             description: 'Plan upfront' },
  { id: 'chain-of-thought', label: 'Chain of Thought',                  description: 'Multi-path vote' },
];

const personas = [
  { id: 'order_tracking',  label: 'Order Tracking',  description: 'Tracks orders',  promptId: 'p/orders' },
  { id: 'customer_support', label: 'Customer Support', description: 'Support queries', promptId: 'p/support' },
];

const governanceProfiles = [
  {
    id: 'default', name: 'Default', systemPrompt: 'You are helpful.',
    enabledTools: [], disabledTools: [],
    escalationThreshold: 0.4, maxToolExecutions: 10, customInstructions: '',
  },
  {
    id: 'strict', name: 'Strict', systemPrompt: 'Be very careful.',
    enabledTools: ['search'], disabledTools: ['execute_code'],
    escalationThreshold: 0.8, maxToolExecutions: 5, customInstructions: 'No PII.',
  },
];

function initialWorkflow(): WorkflowDetail {
  return {
    id: 'wf-prop',
    metadata: {
      name: 'Property Panel Flow',
      description: 'Unit test target',
      version: '1.0.0',
      category: 'test',
      tags: ['e2e'],
    },
    steps: [
      {
        id: 'step-agent',
        type: 'AGENT',
        componentId: 'agent',
        operation: 'Support Agent',
        position: { x: 260, y: 200 },
        configuration: {
          provider: 'anthropic',
          model: 'claude-sonnet-4-6',
          temperature: 0.7,
          maxTokens: 4096,
          agentPattern: 'react',
        },
        connections: [],
      },
    ],
    configuration: {},
  };
}

let stored: WorkflowDetail;
let lastPut: WorkflowDetail | null;

async function mockApi(page: Page) {
  stored = initialWorkflow();
  lastPut = null;
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname.replace(/^\/api/, '');
    const method = route.request().method();

    if (path === '/auth/login' && method === 'POST') return json(route, { token: 'e2e-token', refreshToken: 'e2e-refresh-token' });
    if (path === '/auth/me'    && method === 'GET')  return json(route, user);

    if (path === '/approvals/pending/count' && method === 'GET') return json(route, { count: 0 });
    if (path === '/approvals/pending'       && method === 'GET') return json(route, []);

    if (path === '/workflow/providers'           && method === 'GET') return json(route, providers);
    if (path === '/workflow/agent-patterns'      && method === 'GET') return json(route, patterns);
    if (path === '/workflow/personas'            && method === 'GET') return json(route, personas);
    if (path === '/workflow/governance-profiles' && method === 'GET') return json(route, governanceProfiles);
    if (path === '/workflow/mcp-servers'         && method === 'GET') return json(route, []);

    if (path === '/workflows/wf-prop' && method === 'GET')  return json(route, stored);
    if (path === '/workflows/wf-prop' && method === 'PUT') {
      lastPut = route.request().postDataJSON() as WorkflowDetail;
      stored  = lastPut;
      return json(route, stored);
    }

    if (path === '/workflows/wf-prop/yaml' && method === 'GET') return json(route, { id: 'wf-prop', yaml: '', version: '1.0.0' });
    if (path === '/executions' && method === 'GET') return json(route, []);

    return route.continue();
  });
}

async function authenticate(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('archflow_token', 'e2e-token');
    localStorage.setItem('archflow_refresh_token', 'e2e-refresh-token');
  });
}

async function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  });
}

test.describe('Workflow editor — PropertyPanel', () => {
  test('loads the agent node and surfaces its config in PropertyPanel', async ({ page }) => {
    const pageErrors: Error[] = [];
    page.on('pageerror', (err) => pageErrors.push(err));
    await mockApi(page);
    await authenticate(page);
    await page.goto('/editor/wf-prop');

    // Wait for canvas to render the agent node label
    const node = page.locator('.react-flow__node').first();
    await expect(node).toBeVisible();
    await expect(page.getByText('Support Agent').first()).toBeVisible();

    // Empty panel is shown until a node is selected
    await expect(page.getByText('No node selected')).toBeVisible();

    // Click the node to select it
    await node.click({ force: true });
    await page.waitForTimeout(500);
    if (pageErrors.length > 0) {
      throw new Error('Uncaught page errors: ' + pageErrors.map(e => e.message).join('; '));
    }

    // PropertyPanel swaps to the node fields — the Model select label
    // is only rendered for agent nodes, so its presence proves the
    // panel picked up the selection.
    await expect(page.getByRole('textbox', { name: 'Model' })).toBeVisible({ timeout: 10000 });
    await expect(page.getByRole('textbox', { name: 'Execution strategy' })).toBeVisible();
  });

  test('changing fields in PropertyPanel updates the canvas snapshot and Save sends merged config', async ({ page }) => {
    await mockApi(page);
    await authenticate(page);
    await page.goto('/editor/wf-prop');

    // Select the agent node
    await expect(page.locator('.react-flow__node').first()).toBeVisible();
    await page.locator('.react-flow__node').first().click();

    // Change agent pattern to ReWOO
    await page.getByRole('textbox', { name: 'Execution strategy' }).click();
    await page.getByRole('option', { name: /ReWOO/i }).click();

    // Change model to Claude Opus
    await page.getByRole('textbox', { name: 'Model' }).click();
    await page.getByRole('option', { name: /Claude Opus/i }).click();

    // Adjust temperature
    const tempInput = page.getByLabel('Temperature');
    await tempInput.fill('0.3');

    // Save
    await page.getByTestId('editor-save').click();

    // Wait for PUT to land
    await page.waitForFunction(() => true, { timeout: 500 }).catch(() => {});
    await expect.poll(() => lastPut !== null, { timeout: 3000 }).toBeTruthy();

    const step = lastPut!.steps[0];
    expect(step.id).toBe('step-agent');
    expect(step.configuration.agentPattern).toBe('rewoo');
    expect(step.configuration.model).toBe('claude-opus-4-6');
    expect(step.configuration.temperature).toBe(0.3);
  });

  test('persona selector populates the system prompt', async ({ page }) => {
    await mockApi(page);
    await authenticate(page);
    await page.goto('/editor/wf-prop');

    await expect(page.locator('.react-flow__node').first()).toBeVisible();
    await page.locator('.react-flow__node').first().click();

    // Persona dropdown should be visible (the API returned 2 personas)
    await expect(page.getByRole('textbox', { name: 'Persona' })).toBeVisible();
    await page.getByRole('textbox', { name: 'Persona' }).click();
    await page.getByRole('option', { name: /Order Tracking/ }).click();

    // System prompt becomes populated and disabled
    const prompt = page.getByPlaceholder('You are a helpful assistant...');
    await expect(prompt).toHaveValue(/Tracks orders/);
    await expect(prompt).toBeDisabled();
  });

  test('governance accordion surfaces profile selector and seeds fields', async ({ page }) => {
    await mockApi(page);
    await authenticate(page);
    await page.goto('/editor/wf-prop');

    await expect(page.locator('.react-flow__node').first()).toBeVisible();
    await page.locator('.react-flow__node').first().click();

    // Open Governance accordion
    await page.getByRole('button', { name: 'Governance' }).click();

    // Profile selector is visible
    await expect(page.getByRole('textbox', { name: 'Profile' })).toBeVisible();
    await page.getByRole('textbox', { name: 'Profile' }).click();
    await page.getByRole('option', { name: 'Strict' }).click();

    // Escalation threshold is seeded from the profile (0.8)
    const threshold = page.getByLabel('Escalation threshold');
    await expect(threshold).toHaveValue('0.8');
  });
});
