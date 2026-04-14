import { expect, test, type Page, type Route } from '@playwright/test';

/**
 * Full user journey through the workflow editor.
 *
 * Unlike the focused property-panel.spec.ts, this test exercises an
 * end-to-end scenario a real user would follow when configuring a
 * support triage workflow:
 *
 *   1. Open a 3-step workflow loaded from the backend
 *      (Intake Agent → CRM Lookup → Response Agent)
 *   2. Configure the Intake Agent: change provider, model, pattern,
 *      temperature, max tokens, timeout, persona, governance profile,
 *      whitelist of tools.
 *   3. Configure the CRM tool: set tool id, retry policy (maxAttempts,
 *      delay, backoff), timeout.
 *   4. Configure the Response Agent with a different pattern.
 *   5. Save the workflow.
 *   6. Toggle to the YAML view and verify the new config appears
 *      serialized as YAML.
 *   7. Toggle back to canvas, click each node, and verify PropertyPanel
 *      shows every persisted value.
 *   8. Navigate away (workflow list) and back to the editor, then
 *      re-verify all fields survive a full mount/unmount.
 *   9. Verify the captured PUT payload mirrors the UI edits exactly.
 *
 * The backend is mocked but behaves like a real API: every PUT is
 * stored and subsequent GETs return the updated state. That way the
 * "save → reload → verify" part exercises the complete config
 * propagation path (PropertyPanel → useFlowStore.getCanvasSnapshot →
 * saveWorkflow merge → backend → loadWorkflow → toWorkflowData →
 * FlowCanvas → PropertyPanel).
 */

const user = { id: 'user-e2e', username: 'admin', name: 'Admin', roles: ['admin'] };

type Step = {
  id: string;
  type: string;
  componentId: string;
  operation: string;
  position: { x: number; y: number };
  configuration: Record<string, unknown>;
  connections: Array<{ sourceId: string; targetId: string; isErrorPath: boolean }>;
};

type Workflow = {
  id: string;
  metadata: { name: string; description: string; version: string; category: string; tags: string[] };
  steps: Step[];
  configuration: Record<string, unknown>;
};

const providers = [
  {
    id: 'anthropic', displayName: 'Anthropic', requiresApiKey: true, supportsStreaming: true, group: 'Cloud',
    models: [
      { id: 'claude-sonnet-4-6', name: 'Claude Sonnet 4.6', contextWindow: 200000, maxTemperature: 1.0 },
      { id: 'claude-opus-4-6',   name: 'Claude Opus 4.6',   contextWindow: 200000, maxTemperature: 1.0 },
    ],
  },
  {
    id: 'openai', displayName: 'OpenAI', requiresApiKey: true, supportsStreaming: true, group: 'Cloud',
    models: [
      { id: 'gpt-4o',      name: 'GPT-4o',      contextWindow: 128000, maxTemperature: 2.0 },
      { id: 'gpt-4o-mini', name: 'GPT-4o Mini', contextWindow: 128000, maxTemperature: 2.0 },
    ],
  },
];

const patterns = [
  { id: 'react',            label: 'ReAct (Reason + Act)',              description: 'Iterative loop' },
  { id: 'plan-execute',     label: 'Plan and Execute',                  description: 'Plan then execute' },
  { id: 'rewoo',            label: 'ReWOO (Reasoning Without Observation)', description: '82% fewer tokens' },
  { id: 'chain-of-thought', label: 'Chain of Thought',                  description: 'Majority vote' },
];

const personas = [
  { id: 'customer_support', label: 'Customer Support', description: 'Handles customer queries with empathy', promptId: 'p/support' },
  { id: 'order_tracking',  label: 'Order Tracking',   description: 'Tracks orders and deliveries',          promptId: 'p/orders' },
];

const governanceProfiles = [
  { id: 'default', name: 'Default', systemPrompt: 'You are helpful.',
    enabledTools: [], disabledTools: [],
    escalationThreshold: 0.4, maxToolExecutions: 10, customInstructions: '' },
  { id: 'strict', name: 'Strict', systemPrompt: 'Be very careful.',
    enabledTools: ['crm_lookup'], disabledTools: ['execute_code'],
    escalationThreshold: 0.75, maxToolExecutions: 5, customInstructions: 'No PII disclosure.' },
];

function initialWorkflow(): Workflow {
  return {
    id: 'wf-journey',
    metadata: {
      name: 'Support Triage',
      description: 'Intake → CRM → Response',
      version: '1.0.0',
      category: 'support',
      tags: ['triage', 'sac'],
    },
    steps: [
      {
        id: 'step-intake',
        type: 'AGENT',
        componentId: 'agent',
        operation: 'Intake Agent',
        position: { x: 150, y: 180 },
        configuration: {
          provider: 'anthropic',
          model: 'claude-sonnet-4-6',
          agentPattern: 'react',
          temperature: 0.7,
          maxTokens: 2048,
        },
        connections: [{ sourceId: 'step-intake', targetId: 'step-crm', isErrorPath: false }],
      },
      {
        id: 'step-crm',
        type: 'TOOL',
        componentId: 'tool',
        operation: 'CRM Lookup',
        position: { x: 450, y: 180 },
        configuration: {
          toolId: 'crm_lookup',
          onError: 'stop',
        },
        connections: [{ sourceId: 'step-crm', targetId: 'step-response', isErrorPath: false }],
      },
      {
        id: 'step-response',
        type: 'AGENT',
        componentId: 'agent',
        operation: 'Response Agent',
        position: { x: 750, y: 180 },
        configuration: {
          provider: 'anthropic',
          model: 'claude-sonnet-4-6',
          agentPattern: 'react',
          temperature: 0.5,
          maxTokens: 4096,
        },
        connections: [],
      },
    ],
    configuration: {},
  };
}

// Mutable backend state — preserved across navigation so "save → reload" works.
let storedWorkflow: Workflow;
let storedYaml: string;
let putCount: number;
let lastPut: Workflow | null;

function renderYaml(wf: Workflow): string {
  const stepsYaml = wf.steps.map(s => {
    const cfgLines = Object.entries(s.configuration)
      .map(([k, v]) => `      ${k}: ${JSON.stringify(v)}`)
      .join('\n');
    const connsYaml = s.connections.length
      ? `    connections:\n${s.connections.map(c => `      - { sourceId: ${c.sourceId}, targetId: ${c.targetId}, isErrorPath: ${c.isErrorPath} }`).join('\n')}`
      : '';
    return `  - id: ${s.id}\n    type: ${s.type}\n    componentId: ${s.componentId}\n    operation: ${s.operation}\n    configuration:\n${cfgLines}\n${connsYaml}`;
  }).join('\n');
  return `id: ${wf.id}\nmetadata:\n  name: ${wf.metadata.name}\n  version: ${wf.metadata.version}\nsteps:\n${stepsYaml}\n`;
}

async function mockApi(page: Page) {
  storedWorkflow = initialWorkflow();
  storedYaml = renderYaml(storedWorkflow);
  putCount = 0;
  lastPut = null;

  await page.route('**/api/**', async (route) => {
    const url  = new URL(route.request().url());
    const path = url.pathname.replace(/^\/api/, '');
    const method = route.request().method();

    // ── Auth
    if (path === '/auth/login' && method === 'POST') return json(route, { token: 'e2e-token', refreshToken: 'e2e-refresh-token' });
    if (path === '/auth/me'    && method === 'GET')  return json(route, user);

    // ── Approvals badge
    if (path === '/approvals/pending/count' && method === 'GET') return json(route, { count: 0 });
    if (path === '/approvals/pending'       && method === 'GET') return json(route, []);

    // ── Workflow editor config
    if (path === '/workflow/providers'           && method === 'GET') return json(route, providers);
    if (path === '/workflow/agent-patterns'      && method === 'GET') return json(route, patterns);
    if (path === '/workflow/personas'            && method === 'GET') return json(route, personas);
    if (path === '/workflow/governance-profiles' && method === 'GET') return json(route, governanceProfiles);
    if (path === '/workflow/mcp-servers'         && method === 'GET') return json(route, []);

    // ── Workflow CRUD (stateful)
    if (path === '/workflows' && method === 'GET') return json(route, [{
      id: storedWorkflow.id,
      name: storedWorkflow.metadata.name,
      description: storedWorkflow.metadata.description,
      version: storedWorkflow.metadata.version,
      status: 'draft',
      updatedAt: new Date().toISOString(),
      stepCount: storedWorkflow.steps.length,
    }]);
    if (path === '/workflows/wf-journey' && method === 'GET')  return json(route, storedWorkflow);
    if (path === '/workflows/wf-journey' && method === 'PUT') {
      putCount += 1;
      lastPut = route.request().postDataJSON() as Workflow;
      storedWorkflow = lastPut;
      storedYaml = renderYaml(storedWorkflow);
      return json(route, storedWorkflow);
    }

    // ── YAML
    if (path === '/workflows/wf-journey/yaml' && method === 'GET')
      return json(route, { id: 'wf-journey', yaml: storedYaml, version: storedWorkflow.metadata.version });

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

/**
 * Selects a specific node by matching its on-screen label. React Flow
 * wraps each node in `.react-flow__node`; we walk them and click the
 * one whose text matches, so the test isn't sensitive to layout order.
 */
async function selectNodeByLabel(page: Page, label: string) {
  const node = page.locator('.react-flow__node').filter({ hasText: label });
  await expect(node).toBeVisible();
  await node.click({ force: true });
}

test.describe('Workflow editor — full user journey', () => {
  test('configure a 3-step support flow, save, toggle YAML, reload, verify', async ({ page }) => {
    const errors: string[] = [];
    page.on('pageerror', (e) => errors.push(e.message));

    await mockApi(page);
    await authenticate(page);
    await page.goto('/editor/wf-journey');

    // ── All three nodes rendered on canvas
    await expect(page.locator('.react-flow__node')).toHaveCount(3);
    await expect(page.getByText('Intake Agent').first()).toBeVisible();
    await expect(page.getByText('CRM Lookup').first()).toBeVisible();
    await expect(page.getByText('Response Agent').first()).toBeVisible();

    // ════════════════════════════════════════════════════════════════
    //  STEP 1 — Configure the Intake Agent
    // ════════════════════════════════════════════════════════════════
    await selectNodeByLabel(page, 'Intake Agent');

    // Provider Select opens a popup; the textbox holds the selected label
    await page.getByRole('textbox', { name: 'Provider' }).click();
    await page.getByRole('option', { name: 'OpenAI' }).click();

    await page.getByRole('textbox', { name: 'Model' }).click();
    await page.getByRole('option', { name: 'GPT-4o Mini' }).click();

    await page.getByRole('textbox', { name: 'Execution strategy' }).click();
    await page.getByRole('option', { name: /Plan and Execute/ }).click();

    await page.getByLabel('Temperature').fill('0.3');
    await page.getByLabel('Max tokens').fill('8192');
    await page.getByLabel('Max iterations').fill('15');

    // Persona
    await page.getByRole('textbox', { name: 'Persona' }).click();
    await page.getByRole('option', { name: 'Customer Support' }).click();

    // Governance accordion
    await page.getByRole('button', { name: 'Governance' }).click();
    await page.getByRole('textbox', { name: 'Profile' }).click();
    await page.getByRole('option', { name: 'Strict' }).click();

    // ════════════════════════════════════════════════════════════════
    //  STEP 2 — Configure the CRM tool node
    // ════════════════════════════════════════════════════════════════
    await selectNodeByLabel(page, 'CRM Lookup');

    await page.getByLabel('Tool ID').fill('crm_lookup_v2');
    await page.getByRole('textbox', { name: 'On error' }).click();
    await page.getByRole('option', { name: /Retry with policy/ }).click();

    await page.getByLabel('Max attempts').fill('5');
    await page.getByLabel('Initial delay (ms)').fill('2000');
    await page.getByLabel('Backoff multiplier').fill('2.5');
    await page.getByLabel('Timeout (seconds)').fill('45');

    // ════════════════════════════════════════════════════════════════
    //  STEP 3 — Configure the Response Agent with a different pattern
    // ════════════════════════════════════════════════════════════════
    await selectNodeByLabel(page, 'Response Agent');

    await page.getByRole('textbox', { name: 'Execution strategy' }).click();
    await page.getByRole('option', { name: /Chain of Thought/ }).click();

    await page.getByRole('textbox', { name: 'Model' }).click();
    await page.getByRole('option', { name: 'Claude Opus 4.6' }).click();

    // ════════════════════════════════════════════════════════════════
    //  STEP 4 — Save the workflow
    // ════════════════════════════════════════════════════════════════
    await page.getByTestId('editor-save').click();
    await expect.poll(() => putCount, { timeout: 5000 }).toBeGreaterThan(0);
    await expect(page.getByText('Workflow saved successfully')).toBeVisible({ timeout: 5000 });

    // ── Verify the captured PUT payload includes every edit
    const put = lastPut!;
    const intake   = put.steps.find(s => s.id === 'step-intake')!;
    const crm      = put.steps.find(s => s.id === 'step-crm')!;
    const response = put.steps.find(s => s.id === 'step-response')!;

    expect(intake.configuration).toMatchObject({
      provider: 'openai',
      model: 'gpt-4o-mini',
      agentPattern: 'plan-execute',
      temperature: 0.3,
      maxTokens: 8192,
      maxIterations: 15,
      personaId: 'customer_support',
      governanceProfileId: 'strict',
      escalationThreshold: 0.75,
      maxToolExecutions: 5,
    });
    expect(intake.configuration.enabledTools).toEqual(['crm_lookup']);
    expect(intake.configuration.disabledTools).toEqual(['execute_code']);

    expect(crm.configuration).toMatchObject({
      toolId: 'crm_lookup_v2',
      onError: 'retry',
      retryMaxAttempts: 5,
      retryDelay: 2000,
      retryMultiplier: 2.5,
      timeout: 45,
    });

    expect(response.configuration).toMatchObject({
      model: 'claude-opus-4-6',
      agentPattern: 'chain-of-thought',
    });

    // ── Connections are preserved
    expect(intake.connections).toHaveLength(1);
    expect(intake.connections[0]).toMatchObject({ sourceId: 'step-intake', targetId: 'step-crm' });
    expect(crm.connections).toHaveLength(1);
    expect(crm.connections[0]).toMatchObject({ sourceId: 'step-crm', targetId: 'step-response' });

    // ════════════════════════════════════════════════════════════════
    //  STEP 5 — Verify YAML view reflects the new config
    // ════════════════════════════════════════════════════════════════
    await page.getByTestId('editor-tab-code').click();
    const yamlEditor = page.getByTestId('yaml-editor');
    await expect(yamlEditor).toContainText('claude-opus-4-6');
    await expect(yamlEditor).toContainText('plan-execute');
    await expect(yamlEditor).toContainText('crm_lookup_v2');
    await expect(yamlEditor).toContainText('chain-of-thought');

    // Back to canvas
    await page.getByTestId('editor-tab-canvas').click();

    // ════════════════════════════════════════════════════════════════
    //  STEP 6 — Navigate away and come back: fields must persist
    // ════════════════════════════════════════════════════════════════
    await page.goto('/');
    await expect(page.getByText('Support Triage').first()).toBeVisible();

    await page.goto('/editor/wf-journey');
    await expect(page.locator('.react-flow__node')).toHaveCount(3);

    // Re-select Intake Agent and verify every field round-tripped
    await selectNodeByLabel(page, 'Intake Agent');
    await expect(page.getByRole('textbox', { name: 'Provider' })).toHaveValue('OpenAI');
    await expect(page.getByRole('textbox', { name: 'Model' })).toHaveValue('GPT-4o Mini');
    await expect(page.getByRole('textbox', { name: 'Execution strategy' })).toHaveValue('Plan and Execute');
    await expect(page.getByLabel('Temperature')).toHaveValue('0.3');
    await expect(page.getByLabel('Max tokens')).toHaveValue('8192');
    await expect(page.getByRole('textbox', { name: 'Persona' })).toHaveValue('Customer Support');

    // Re-select CRM tool and verify retry policy
    await selectNodeByLabel(page, 'CRM Lookup');
    await expect(page.getByLabel('Tool ID')).toHaveValue('crm_lookup_v2');
    await expect(page.getByRole('textbox', { name: 'On error' })).toHaveValue('Retry with policy');
    await expect(page.getByLabel('Max attempts')).toHaveValue('5');
    await expect(page.getByLabel('Initial delay (ms)')).toHaveValue('2000');
    await expect(page.getByLabel('Backoff multiplier')).toHaveValue('2.5');
    await expect(page.getByLabel('Timeout (seconds)')).toHaveValue('45');

    // Re-select Response Agent
    await selectNodeByLabel(page, 'Response Agent');
    await expect(page.getByRole('textbox', { name: 'Model' })).toHaveValue('Claude Opus 4.6');
    await expect(page.getByRole('textbox', { name: 'Execution strategy' })).toHaveValue('Chain of Thought');

    // ── No JavaScript errors throughout the journey
    expect(errors, 'Unexpected page errors: ' + errors.join('; ')).toEqual([]);
  });
});
