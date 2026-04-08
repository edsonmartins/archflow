import { expect, test, type Page, type Route } from '@playwright/test';

const user = {
  id: 'user-e2e',
  username: 'admin',
  name: 'Admin User',
  roles: ['admin'],
};

const baseWorkflows = [
  {
    id: 'wf-customer',
    name: 'Customer Support Flow',
    description: 'Routes customer tickets to the right assistant',
    version: '1.2.0',
    status: 'active',
    updatedAt: '2026-04-08T12:00:00.000Z',
    stepCount: 3,
  },
  {
    id: 'wf-invoice',
    name: 'Invoice Approval',
    description: 'Checks invoices before approval',
    version: '0.9.0',
    status: 'draft',
    updatedAt: '2026-04-07T10:30:00.000Z',
    stepCount: 2,
  },
];

interface WorkflowDetail {
  id: string;
  metadata: {
    name: string;
    description: string;
    version: string;
    category: string;
    tags: string[];
  };
  steps: Array<Record<string, unknown>>;
  configuration: Record<string, unknown>;
}

const workflowDetails: Record<string, WorkflowDetail> = {
  'wf-customer': {
    id: 'wf-customer',
    metadata: {
      name: 'Customer Support Flow',
      description: 'Routes customer tickets to the right assistant',
      version: '1.2.0',
      category: 'support',
      tags: ['support', 'ai'],
    },
    steps: [
      { id: 'step-router', type: 'AGENT', name: 'Ticket Router' },
      { id: 'step-assistant', type: 'ASSISTANT', name: 'Support Assistant' },
      { id: 'step-tool', type: 'TOOL', name: 'CRM Lookup' },
    ],
    configuration: {},
  },
  'wf-invoice': {
    id: 'wf-invoice',
    metadata: {
      name: 'Invoice Approval',
      description: 'Checks invoices before approval',
      version: '0.9.0',
      category: 'finance',
      tags: ['finance'],
    },
    steps: [
      { id: 'step-reader', type: 'LLM_CHAT', name: 'Invoice Reader' },
      { id: 'step-condition', type: 'CONDITION', name: 'Approval Rule' },
    ],
    configuration: {},
  },
};

const executions = [
  {
    id: 'exec-customer-001',
    workflowId: 'wf-customer',
    workflowName: 'Customer Support Flow',
    status: 'COMPLETED',
    startedAt: '2026-04-08T12:01:00.000Z',
    completedAt: '2026-04-08T12:01:01.500Z',
    duration: 1500,
    error: null,
  },
  {
    id: 'exec-invoice-001',
    workflowId: 'wf-invoice',
    workflowName: 'Invoice Approval',
    status: 'FAILED',
    startedAt: '2026-04-08T11:00:00.000Z',
    completedAt: '2026-04-08T11:00:00.900Z',
    duration: 900,
    error: 'Missing invoice total',
  },
];

interface MockApiOptions {
  loginSucceeds?: boolean;
  workflows?: typeof baseWorkflows;
  workflowDetails?: Record<string, WorkflowDetail>;
  executions?: typeof executions;
  failWorkflowList?: { status?: number; message: string };
  failWorkflowGet?: Record<string, { status?: number; message: string }>;
  failWorkflowUpdate?: Record<string, { status?: number; message: string }>;
  failWorkflowExecute?: Record<string, { status?: number; message: string }>;
  failExecutionList?: { status?: number; message: string };
}

async function mockApi(page: Page, options: MockApiOptions = {}) {
  let workflows = options.workflows ? [...options.workflows] : [...baseWorkflows];
  const details = options.workflowDetails ? { ...options.workflowDetails } : { ...workflowDetails };
  const loginSucceeds = options.loginSucceeds ?? true;
  const mockedExecutions = options.executions ? [...options.executions] : [...executions];

  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^\/api/, '');
    const method = request.method();

    if (path === '/auth/login' && method === 'POST') {
      if (!loginSucceeds) {
        await json(route, 'Invalid credentials', 400);
        return;
      }

      await json(route, { token: 'e2e-token', refreshToken: 'e2e-refresh-token' });
      return;
    }

    if (path === '/auth/me' && method === 'GET') {
      await json(route, user);
      return;
    }

    if (path === '/auth/logout' && method === 'POST') {
      await json(route, {});
      return;
    }

    if (path === '/workflows' && method === 'GET') {
      if (options.failWorkflowList) {
        await json(route, options.failWorkflowList.message, options.failWorkflowList.status ?? 500);
        return;
      }
      await json(route, workflows);
      return;
    }

    if (path === '/workflows' && method === 'POST') {
      const body = request.postDataJSON() as Partial<WorkflowDetail>;
      const created: WorkflowDetail = {
        id: 'wf-created',
        metadata: {
          name: body.metadata?.name ?? 'Untitled Workflow',
          description: body.metadata?.description ?? '',
          version: body.metadata?.version ?? '1.0.0',
          category: body.metadata?.category ?? 'custom',
          tags: body.metadata?.tags ?? [],
        },
        steps: body.steps ?? [],
        configuration: body.configuration ?? {},
      };

      details[created.id] = created;
      workflows = [
        ...workflows,
        {
          id: created.id,
          name: created.metadata.name,
          description: created.metadata.description,
          version: created.metadata.version,
          status: 'draft',
          updatedAt: '2026-04-08T13:00:00.000Z',
          stepCount: created.steps.length,
        },
      ];
      await json(route, created, 201);
      return;
    }

    const executeMatch = path.match(/^\/workflows\/([^/]+)\/execute$/);
    if (executeMatch && method === 'POST') {
      const workflowId = executeMatch[1];
      const executeFailure = options.failWorkflowExecute?.[workflowId];
      if (executeFailure) {
        await json(route, executeFailure.message, executeFailure.status ?? 500);
        return;
      }
      await json(route, { executionId: 'exec-e2e', status: 'RUNNING' });
      return;
    }

    const workflowMatch = path.match(/^\/workflows\/([^/]+)$/);
    if (workflowMatch && method === 'GET') {
      const workflowFailure = options.failWorkflowGet?.[workflowMatch[1]];
      if (workflowFailure) {
        await json(route, workflowFailure.message, workflowFailure.status ?? 404);
        return;
      }
      const workflow = details[workflowMatch[1]];
      await json(route, workflow ?? { message: 'Not found' }, workflow ? 200 : 404);
      return;
    }

    if (workflowMatch && method === 'PUT') {
      const workflowFailure = options.failWorkflowUpdate?.[workflowMatch[1]];
      if (workflowFailure) {
        await json(route, workflowFailure.message, workflowFailure.status ?? 500);
        return;
      }
      const body = request.postDataJSON() as WorkflowDetail;
      details[workflowMatch[1]] = body;
      workflows = workflows.map((workflow) =>
        workflow.id === workflowMatch[1]
          ? {
              ...workflow,
              name: body.metadata.name,
              description: body.metadata.description,
              version: body.metadata.version,
              updatedAt: '2026-04-08T13:05:00.000Z',
              stepCount: body.steps.length,
            }
          : workflow
      );
      await json(route, body);
      return;
    }

    if (workflowMatch && method === 'DELETE') {
      workflows = workflows.filter((workflow) => workflow.id !== workflowMatch[1]);
      await route.fulfill({ status: 204 });
      return;
    }

    if (path === '/executions' && method === 'GET') {
      if (options.failExecutionList) {
        await json(route, options.failExecutionList.message, options.failExecutionList.status ?? 500);
        return;
      }
      const workflowId = url.searchParams.get('workflowId');
      await json(
        route,
        workflowId
          ? mockedExecutions.filter((execution) => execution.workflowId === workflowId)
          : mockedExecutions
      );
      return;
    }

    await json(route, { message: `Unhandled ${method} ${path}` }, 404);
  });
}

async function authenticate(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('archflow_token', 'e2e-token');
    localStorage.setItem('archflow_refresh_token', 'e2e-refresh-token');
  });
}

async function dragPaletteNodeToCanvas(
  page: Page,
  nodeTestId: string,
  position: { x: number; y: number }
) {
  const dataTransfer = await page.evaluateHandle(() => new DataTransfer());
  const dropzone = page.getByTestId('workflow-dropzone');

  await page.getByTestId(nodeTestId).dispatchEvent('dragstart', { dataTransfer });
  await dropzone.dispatchEvent('dragenter', {
    dataTransfer,
    clientX: position.x,
    clientY: position.y,
  });
  await dropzone.dispatchEvent('dragover', {
    dataTransfer,
    clientX: position.x,
    clientY: position.y,
  });
  await dropzone.dispatchEvent('drop', {
    dataTransfer,
    clientX: position.x,
    clientY: position.y,
  });
}

async function json(route: Route, body: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  });
}

test.describe('Archflow frontend E2E', () => {
  test('redirects protected routes to login when unauthenticated', async ({ page }) => {
    await mockApi(page);

    await page.goto('/');

    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByText('Sign in to your account')).toBeVisible();
  });

  test('shows login errors and signs in successfully', async ({ page }) => {
    await mockApi(page, { loginSucceeds: false });

    await page.goto('/login');
    await page.getByLabel('Username').fill('admin');
    await page.getByLabel('Password').fill('wrong-password');
    await page.getByRole('button', { name: /sign in/i }).click();

    await expect(page.getByText('Invalid credentials')).toBeVisible();

    await page.unroute('**/api/**');
    await mockApi(page, { loginSucceeds: true });
    await page.getByLabel('Password').fill('correct-password');
    await page.getByRole('button', { name: /sign in/i }).click();

    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole('heading', { name: 'Workflows' })).toBeVisible();
  });

  test('lists, searches, executes, edits and deletes workflows', async ({ page }) => {
    await mockApi(page);
    await authenticate(page);

    await page.goto('/');

    await expect(page.getByRole('heading', { name: 'Workflows' })).toBeVisible();
    await expect(page.getByText('Customer Support Flow')).toBeVisible();
    await expect(page.getByText('Invoice Approval')).toBeVisible();

    await page.getByPlaceholder('Search workflows...').fill('invoice');
    await expect(page.getByText('Invoice Approval')).toBeVisible();
    await expect(page.getByText('Customer Support Flow')).not.toBeVisible();

    await page.getByPlaceholder('Search workflows...').clear();
    const customerRow = page.getByRole('row', { name: /Customer Support Flow/ });
    await customerRow.locator('button').nth(0).click();
    await expect(page).toHaveURL(/\/executions\?id=exec-e2e$/);

    await page.goto('/');
    await page.getByRole('row', { name: /Customer Support Flow/ }).locator('button').nth(1).click();
    await expect(page).toHaveURL(/\/editor\/wf-customer$/);

    await page.goto('/');
    await page.getByRole('row', { name: /Invoice Approval/ }).locator('button').nth(2).click();
    await expect(page.getByRole('dialog', { name: 'Delete Workflow' })).toBeVisible();
    await page
      .getByRole('dialog', { name: 'Delete Workflow' })
      .getByRole('button', { name: 'Delete' })
      .dispatchEvent('click');
    await expect(page.getByText('Invoice Approval')).not.toBeVisible();
  });

  test('opens the new workflow editor screen', async ({ page }) => {
    await mockApi(page);
    await authenticate(page);

    await page.goto('/');
    await page.getByRole('button', { name: 'New Workflow' }).click();

    await expect(page).toHaveURL(/\/editor$/);
    await expect(page.getByText('Node Palette')).toBeVisible();
    await expect(page.getByText('Agent', { exact: true })).toBeVisible();
    await expect(page.getByText('Tool', { exact: true })).toBeVisible();
    await expect(page.locator('archflow-designer').getByText('No Workflow Loaded')).toBeVisible();
  });

  test('creates a workflow from the editor screen', async ({ page }) => {
    await mockApi(page);
    await authenticate(page);

    await page.goto('/');
    await page.getByRole('button', { name: 'New Workflow' }).click();

    await expect(page).toHaveURL(/\/editor$/);
    await page.getByRole('button', { name: 'Create Workflow' }).click();
    await expect(page.getByRole('dialog', { name: 'Create Workflow' })).toBeVisible();

    await page.getByLabel('Name').fill('Partner Onboarding');
    await page.getByLabel('Description').fill('Collect partner intake and route approvals');
    await page
      .getByRole('dialog', { name: 'Create Workflow' })
      .getByRole('button', { name: 'Create' })
      .dispatchEvent('click');

    await expect(page).toHaveURL(/\/editor\/wf-created$/);
    await expect(page.locator('archflow-designer').getByText('Partner Onboarding')).toBeVisible();
    await expect(page.locator('archflow-designer').getByText('Steps: 0')).toBeVisible();
    await expect(page.locator('archflow-designer').getByText('Ready')).toBeVisible();
  });

  test('adds a step to a created workflow and saves it', async ({ page }) => {
    await mockApi(page);
    await authenticate(page);

    await page.goto('/editor');
    await page.getByRole('button', { name: 'Create Workflow' }).click();
    await page.getByLabel('Name').fill('Order Triage');
    await page.getByLabel('Description').fill('Classifies inbound order issues');
    await page
      .getByRole('dialog', { name: 'Create Workflow' })
      .getByRole('button', { name: 'Create' })
      .dispatchEvent('click');

    await expect(page).toHaveURL(/\/editor\/wf-created$/);
    await expect(page.locator('archflow-designer').getByText('Order Triage')).toBeVisible();
    await expect(page.locator('archflow-designer').getByText('Steps: 0')).toBeVisible();

    await dragPaletteNodeToCanvas(page, 'palette-node-agent', { x: 520, y: 260 });

    const stepTitle = page.locator('archflow-designer').locator('.archflow-step__title', { hasText: /^Agent$/ });
    await expect(page.locator('archflow-designer').getByText('Steps: 1')).toBeVisible();
    await expect(stepTitle).toBeVisible();
    await stepTitle.click();
    await expect(page.getByText('Properties')).toBeVisible();
    await expect(page.getByLabel('Node Label')).toHaveValue('Agent');
    await expect(page.getByText('Properties').locator('..').getByText('AGENT', { exact: true })).toBeVisible();
    await page.getByLabel('Node Label').fill('Triage Agent');
    await page.getByLabel('Temperature').fill('1.1');
    await expect(page.locator('archflow-designer').getByText('Triage Agent')).toBeVisible();

    await page.locator('archflow-designer').getByRole('button', { name: 'Save' }).click();

    await page.goto('/editor/wf-created');
    await expect(page.locator('archflow-designer').getByText('Triage Agent')).toBeVisible();
    await expect(page.locator('archflow-designer').getByText('Steps: 1')).toBeVisible();
    await page.locator('archflow-designer').locator('.archflow-step__title', { hasText: /^Triage Agent$/ }).click();
    await expect(page.getByLabel('Temperature')).toHaveValue('1.1');

    await page.goto('/');
    await expect(page.getByText('Order Triage')).toBeVisible();
    await expect(page.getByRole('row', { name: /Order Triage/ })).toContainText('1');
  });

  test('executes a created workflow from the editor', async ({ page }) => {
    await mockApi(page);
    await authenticate(page);

    await page.goto('/editor');
    await page.getByRole('button', { name: 'Create Workflow' }).click();
    await page.getByLabel('Name').fill('Execution Flow');
    await page.getByLabel('Description').fill('Runs a workflow from the editor');
    await page
      .getByRole('dialog', { name: 'Create Workflow' })
      .getByRole('button', { name: 'Create' })
      .dispatchEvent('click');

    await expect(page).toHaveURL(/\/editor\/wf-created$/);
    await expect(page.locator('archflow-designer').getByText('Execution Flow')).toBeVisible();
    await expect(page.locator('archflow-designer').getByText('Steps: 0')).toBeVisible();

    await dragPaletteNodeToCanvas(page, 'palette-node-agent', { x: 520, y: 260 });

    await expect(page.locator('archflow-designer').getByText('Steps: 1')).toBeVisible();
    await page.locator('archflow-designer').getByRole('button', { name: 'Save' }).click();
    await page.locator('archflow-designer').getByRole('button', { name: 'Execute' }).click();

    await expect(page).toHaveURL(/\/executions\?id=exec-e2e$/);
    await expect(page.getByRole('heading', { name: 'Execution History' })).toBeVisible();
  });

  test('connects two steps and persists the connection after save', async ({ page }) => {
    await mockApi(page);
    await authenticate(page);

    await page.goto('/editor');
    await page.getByRole('button', { name: 'Create Workflow' }).click();
    await page.getByLabel('Name').fill('Connected Flow');
    await page.getByLabel('Description').fill('Persists a connection between two steps');
    await page
      .getByRole('dialog', { name: 'Create Workflow' })
      .getByRole('button', { name: 'Create' })
      .dispatchEvent('click');

    await expect(page).toHaveURL(/\/editor\/wf-created$/);
    await expect(page.locator('archflow-designer').getByText('Connected Flow')).toBeVisible();
    await expect(page.locator('archflow-designer').getByText('Steps: 0')).toBeVisible();

    await dragPaletteNodeToCanvas(page, 'palette-node-agent', { x: 520, y: 260 });
    await expect(page.locator('archflow-designer').getByText('Steps: 1')).toBeVisible();
    await expect(page.locator('archflow-designer').locator('.archflow-step__title', { hasText: /^Agent$/ })).toBeVisible();

    await dragPaletteNodeToCanvas(page, 'palette-node-tool', { x: 620, y: 320 });

    await expect(page.locator('archflow-designer').getByText('Steps: 2')).toBeVisible();
    await page.locator('archflow-designer').locator('[data-connect-source-id="step-1"]').click();
    await page.locator('archflow-designer').locator('.archflow-step__title', { hasText: /^Tool$/ }).click();
    await expect(page.locator('archflow-designer').getByText('step-1 -> step-2')).toBeVisible();

    await page.locator('archflow-designer').getByRole('button', { name: 'Save' }).click();

    await page.goto('/editor/wf-created');
    await expect(page.locator('archflow-designer').getByText('Steps: 2')).toBeVisible();
    await expect(page.locator('archflow-designer').getByText('step-1 -> step-2')).toBeVisible();
  });

  test('removes a step and clears persisted connections after save', async ({ page }) => {
    await mockApi(page);
    await authenticate(page);

    await page.goto('/editor');
    await page.getByRole('button', { name: 'Create Workflow' }).click();
    await page.getByLabel('Name').fill('Removable Flow');
    await page.getByLabel('Description').fill('Removes a step and persists the new structure');
    await page
      .getByRole('dialog', { name: 'Create Workflow' })
      .getByRole('button', { name: 'Create' })
      .dispatchEvent('click');

    await expect(page).toHaveURL(/\/editor\/wf-created$/);
    await expect(page.locator('archflow-designer').getByText('Removable Flow')).toBeVisible();
    await expect(page.locator('archflow-designer').getByText('Steps: 0')).toBeVisible();

    await dragPaletteNodeToCanvas(page, 'palette-node-agent', { x: 520, y: 260 });
    await expect(page.locator('archflow-designer').getByText('Steps: 1')).toBeVisible();

    await dragPaletteNodeToCanvas(page, 'palette-node-tool', { x: 620, y: 320 });
    await expect(page.locator('archflow-designer').getByText('Steps: 2')).toBeVisible();

    await page.locator('archflow-designer').locator('[data-connect-source-id="step-1"]').click();
    await page.locator('archflow-designer').locator('.archflow-step__title', { hasText: /^Tool$/ }).click();
    await expect(page.locator('archflow-designer').getByText('step-1 -> step-2')).toBeVisible();

    await page.locator('archflow-designer').locator('[data-remove-node-id="step-2"]').click();
    await expect(page.locator('archflow-designer').getByText('Steps: 1')).toBeVisible();
    await expect(page.locator('archflow-designer').getByText('step-1 -> step-2')).not.toBeVisible();
    await expect(page.locator('archflow-designer').locator('.archflow-step__title', { hasText: /^Tool$/ })).not.toBeVisible();

    await page.locator('archflow-designer').getByRole('button', { name: 'Save' }).click();

    await page.goto('/editor/wf-created');
    await expect(page.locator('archflow-designer').getByText('Steps: 1')).toBeVisible();
    await expect(page.locator('archflow-designer').locator('.archflow-step__title', { hasText: /^Agent$/ })).toBeVisible();
    await expect(page.locator('archflow-designer').getByText('step-1 -> step-2')).not.toBeVisible();
  });

  test('resets the editor and clears the selected node state', async ({ page }) => {
    await mockApi(page);
    await authenticate(page);

    await page.goto('/editor');
    await page.getByRole('button', { name: 'Create Workflow' }).click();
    await page.getByLabel('Name').fill('Resettable Flow');
    await page.getByLabel('Description').fill('Resets the current editor session');
    await page
      .getByRole('dialog', { name: 'Create Workflow' })
      .getByRole('button', { name: 'Create' })
      .dispatchEvent('click');

    await expect(page).toHaveURL(/\/editor\/wf-created$/);
    await expect(page.locator('archflow-designer').getByText('Resettable Flow')).toBeVisible();

    await dragPaletteNodeToCanvas(page, 'palette-node-agent', { x: 520, y: 260 });
    await expect(page.locator('archflow-designer').getByText('Steps: 1')).toBeVisible();

    await page.locator('archflow-designer').locator('.archflow-step__title', { hasText: /^Agent$/ }).click();
    await expect(page.getByText('Properties')).toBeVisible();
    await expect(page.getByLabel('Node Label')).toHaveValue('Agent');

    await page.locator('archflow-designer').getByRole('button', { name: 'Reset' }).click();

    await expect(page.locator('archflow-designer').getByText('No Workflow Loaded')).toBeVisible();
    await expect(page.locator('archflow-designer').getByText('Steps: 0')).toBeVisible();
    await expect(page.getByText('Properties')).not.toBeVisible();

    await page.getByRole('button', { name: 'Create Workflow' }).click();
    await expect(page.getByRole('dialog', { name: 'Create Workflow' })).toBeVisible();
  });

  test('persists multiple edited steps and multiple connections after save', async ({ page }) => {
    await mockApi(page);
    await authenticate(page);

    await page.goto('/editor');
    await page.getByRole('button', { name: 'Create Workflow' }).click();
    await page.getByLabel('Name').fill('Multi Step Flow');
    await page.getByLabel('Description').fill('Edits multiple nodes and persists branching connections');
    await page
      .getByRole('dialog', { name: 'Create Workflow' })
      .getByRole('button', { name: 'Create' })
      .dispatchEvent('click');

    await expect(page).toHaveURL(/\/editor\/wf-created$/);
    await expect(page.locator('archflow-designer').getByText('Multi Step Flow')).toBeVisible();

    await dragPaletteNodeToCanvas(page, 'palette-node-agent', { x: 500, y: 240 });
    await dragPaletteNodeToCanvas(page, 'palette-node-tool', { x: 620, y: 300 });
    await dragPaletteNodeToCanvas(page, 'palette-node-assistant', { x: 740, y: 360 });
    await expect(page.locator('archflow-designer').getByText('Steps: 3')).toBeVisible();

    const designer = page.locator('archflow-designer');

    await designer.locator('.archflow-step__title', { hasText: /^Agent$/ }).click();
    await expect(page.getByLabel('Node Label')).toHaveValue('Agent');
    await page.getByLabel('Node Label').fill('Router Agent');
    await page.getByLabel('Temperature').fill('1.3');
    await expect(designer.getByText('Router Agent')).toBeVisible();

    await designer.locator('.archflow-step__title', { hasText: /^Tool$/ }).click();
    await expect(page.getByLabel('Node Label')).toHaveValue('Tool');
    await page.getByLabel('Node Label').fill('CRM Tool');
    await page.getByLabel('Tool ID').fill('crm.lookup');
    await expect(designer.getByText('CRM Tool')).toBeVisible();

    await designer.locator('[data-connect-source-id="step-1"]').click();
    await designer.locator('.archflow-step__title', { hasText: /^CRM Tool$/ }).click();
    await expect(designer.getByText('step-1 -> step-2')).toBeVisible();

    await designer.locator('[data-connect-source-id="step-1"]').click();
    await designer.locator('.archflow-step__title', { hasText: /^Assistant$/ }).click();
    await expect(designer.getByText('step-1 -> step-3')).toBeVisible();

    await designer.getByRole('button', { name: 'Save' }).click();

    await page.goto('/editor/wf-created');
    await expect(designer.getByText('Steps: 3')).toBeVisible();
    await expect(designer.getByText('Router Agent')).toBeVisible();
    await expect(designer.getByText('CRM Tool')).toBeVisible();
    await expect(designer.getByText('step-1 -> step-2')).toBeVisible();
    await expect(designer.getByText('step-1 -> step-3')).toBeVisible();

    await designer.locator('.archflow-step__title', { hasText: /^Router Agent$/ }).click();
    await expect(page.getByLabel('Temperature')).toHaveValue('1.3');

    await designer.locator('.archflow-step__title', { hasText: /^CRM Tool$/ }).click();
    await expect(page.getByLabel('Tool ID')).toHaveValue('crm.lookup');
  });

  test('saves and executes a branched multi-step workflow with mixed node configs', async ({ page }) => {
    await mockApi(page);
    await authenticate(page);

    await page.goto('/editor');
    await page.getByRole('button', { name: 'Create Workflow' }).click();
    await page.getByLabel('Name').fill('Branch Execution Flow');
    await page.getByLabel('Description').fill('Persists a branched workflow and executes it');
    await page
      .getByRole('dialog', { name: 'Create Workflow' })
      .getByRole('button', { name: 'Create' })
      .dispatchEvent('click');

    await expect(page).toHaveURL(/\/editor\/wf-created$/);
    const designer = page.locator('archflow-designer');
    await expect(designer.getByText('Branch Execution Flow')).toBeVisible();

    await dragPaletteNodeToCanvas(page, 'palette-node-agent', { x: 500, y: 240 });
    await dragPaletteNodeToCanvas(page, 'palette-node-tool', { x: 620, y: 300 });
    await dragPaletteNodeToCanvas(page, 'palette-node-assistant', { x: 740, y: 360 });
    await expect(designer.getByText('Steps: 3')).toBeVisible();

    await designer.locator('.archflow-step__title', { hasText: /^Agent$/ }).click();
    await page.getByLabel('Node Label').fill('Decision Agent');
    await page.getByLabel('Temperature').fill('0.9');
    await expect(designer.getByText('Decision Agent')).toBeVisible();

    await designer.locator('.archflow-step__title', { hasText: /^Tool$/ }).click();
    await page.getByLabel('Node Label').fill('Enrichment Tool');
    await page.getByLabel('Tool ID').fill('enrichment.fetch');
    await expect(designer.getByText('Enrichment Tool')).toBeVisible();

    await designer.locator('.archflow-step__title', { hasText: /^Assistant$/ }).click();
    await page.getByLabel('Node Label').fill('Fallback Assistant');
    await page.getByLabel('Specialization').fill('customer-escalation');
    await expect(designer.getByText('Fallback Assistant')).toBeVisible();

    await designer.locator('[data-connect-source-id="step-1"]').click();
    await designer.locator('.archflow-step__title', { hasText: /^Enrichment Tool$/ }).click();
    await designer.locator('[data-connect-source-id="step-1"]').click();
    await designer.locator('.archflow-step__title', { hasText: /^Fallback Assistant$/ }).click();
    await expect(designer.getByText('step-1 -> step-2')).toBeVisible();
    await expect(designer.getByText('step-1 -> step-3')).toBeVisible();

    await designer.getByRole('button', { name: 'Save' }).click();

    await page.goto('/');
    await expect(page.getByRole('row', { name: /Branch Execution Flow/ })).toContainText('3');

    await page.goto('/editor/wf-created');
    await expect(designer.getByText('Decision Agent')).toBeVisible();
    await expect(designer.getByText('Enrichment Tool')).toBeVisible();
    await expect(designer.getByText('Fallback Assistant')).toBeVisible();

    await designer.locator('.archflow-step__title', { hasText: /^Fallback Assistant$/ }).click();
    await expect(page.getByLabel('Specialization')).toHaveValue('customer-escalation');

    await designer.getByRole('button', { name: 'Execute' }).click();
    await expect(page).toHaveURL(/\/executions\?id=exec-e2e$/);
    await expect(page.getByRole('heading', { name: 'Execution History' })).toBeVisible();
  });

  test('loads an existing workflow in the editor screen', async ({ page }) => {
    await mockApi(page);
    await authenticate(page);

    await page.goto('/editor/wf-customer');

    await expect(page.getByText('Node Palette')).toBeVisible();
    await expect(page.locator('archflow-designer').getByText('Customer Support Flow')).toBeVisible();
    await expect(page.locator('archflow-designer').getByText('Steps: 3')).toBeVisible();
    await expect(page.locator('archflow-designer').getByText('Ready')).toBeVisible();
  });

  test('shows empty states for workflows and executions', async ({ page }) => {
    await mockApi(page, { workflows: [], executions: [] });
    await authenticate(page);

    await page.goto('/');
    await expect(page.getByText('No workflows yet. Create your first one!')).toBeVisible();

    await page.goto('/executions');
    await expect(page.getByText('No executions yet.')).toBeVisible();
  });

  test('shows workflow load errors in the editor', async ({ page }) => {
    await mockApi(page, {
      failWorkflowGet: {
        'wf-missing': { status: 404, message: 'Workflow not found' },
      },
    });
    await authenticate(page);

    await page.goto('/editor/wf-missing');

    await expect(page.getByText('Workflow not found')).toBeVisible();
    await expect(page.locator('archflow-designer').getByText('No Workflow Loaded')).toBeVisible();
  });

  test('shows workflow execution errors on the list screen', async ({ page }) => {
    await mockApi(page, {
      failWorkflowExecute: {
        'wf-customer': { status: 500, message: 'Execution failed' },
      },
    });
    await authenticate(page);

    await page.goto('/');
    await page.getByRole('row', { name: /Customer Support Flow/ }).locator('button').nth(0).click();

    await expect(page.getByText('Execution failed')).toBeVisible();
    await expect(page).toHaveURL(/\/$/);
  });

  test('shows save errors in the editor', async ({ page }) => {
    await mockApi(page, {
      failWorkflowUpdate: {
        'wf-created': { status: 500, message: 'Save failed' },
      },
    });
    await authenticate(page);

    await page.goto('/editor');
    await page.getByRole('button', { name: 'Create Workflow' }).click();
    await page.getByLabel('Name').fill('Broken Save Flow');
    await page.getByRole('dialog', { name: 'Create Workflow' }).getByRole('button', { name: 'Create' }).dispatchEvent('click');

    await expect(page).toHaveURL(/\/editor\/wf-created$/);
    await expect(page.locator('archflow-designer').getByText('Broken Save Flow')).toBeVisible();
    await dragPaletteNodeToCanvas(page, 'palette-node-agent', { x: 520, y: 260 });
    await expect(page.locator('archflow-designer').getByText('Steps: 1')).toBeVisible();

    await page.locator('archflow-designer').getByRole('button', { name: 'Save' }).click();

    await expect(page.getByText('Failed to save workflow: Internal Server Error')).toBeVisible();
    await expect(page).toHaveURL(/\/editor\/wf-created$/);
  });

  test('shows execute errors in the editor', async ({ page }) => {
    await mockApi(page, {
      failWorkflowExecute: {
        'wf-created': { status: 500, message: 'Execute failed' },
      },
    });
    await authenticate(page);

    await page.goto('/editor');
    await page.getByRole('button', { name: 'Create Workflow' }).click();
    await page.getByLabel('Name').fill('Broken Execute Flow');
    await page.getByRole('dialog', { name: 'Create Workflow' }).getByRole('button', { name: 'Create' }).dispatchEvent('click');

    await expect(page).toHaveURL(/\/editor\/wf-created$/);
    await expect(page.locator('archflow-designer').getByText('Broken Execute Flow')).toBeVisible();
    await dragPaletteNodeToCanvas(page, 'palette-node-agent', { x: 520, y: 260 });
    await expect(page.locator('archflow-designer').getByText('Steps: 1')).toBeVisible();

    await page.locator('archflow-designer').getByRole('button', { name: 'Execute' }).click();

    await expect(page.getByText('Failed to execute workflow: Internal Server Error')).toBeVisible();
    await expect(page).toHaveURL(/\/editor\/wf-created$/);
  });

  test('redirects to login on editor execute 401', async ({ page }) => {
    await mockApi(page, {
      failWorkflowExecute: {
        'wf-created': { status: 401, message: 'Unauthorized' },
      },
    });
    await authenticate(page);

    await page.goto('/editor');
    await page.getByRole('button', { name: 'Create Workflow' }).click();
    await page.getByLabel('Name').fill('Unauthorized Flow');
    await page.getByRole('dialog', { name: 'Create Workflow' }).getByRole('button', { name: 'Create' }).dispatchEvent('click');

    await expect(page).toHaveURL(/\/editor\/wf-created$/);
    await expect(page.locator('archflow-designer').getByText('Unauthorized Flow')).toBeVisible();
    await dragPaletteNodeToCanvas(page, 'palette-node-agent', { x: 520, y: 260 });
    await expect(page.locator('archflow-designer').getByText('Steps: 1')).toBeVisible();

    await page.locator('archflow-designer').getByRole('button', { name: 'Execute' }).click();

    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByText('Sign in to your account')).toBeVisible();
  });

  test('shows execution history load errors', async ({ page }) => {
    await mockApi(page, {
      failExecutionList: { status: 500, message: 'Failed to load executions' },
    });
    await authenticate(page);

    await page.goto('/executions');

    await expect(page.getByText('Failed to load executions')).toBeVisible();
  });

  test('shows execution history and filters by workflow', async ({ page }) => {
    await mockApi(page);
    await authenticate(page);

    await page.goto('/executions');

    await expect(page.getByRole('heading', { name: 'Execution History' })).toBeVisible();
    await expect(page.getByRole('row', { name: /exec-custome.*Customer Support Flow/ })).toBeVisible();
    await expect(page.getByRole('row', { name: /exec-invoice.*Invoice Approval/ })).toBeVisible();
    await expect(page.getByText('COMPLETED')).toBeVisible();
    await expect(page.getByText('FAILED')).toBeVisible();
    await expect(page.getByText('Missing invoice total')).toBeVisible();

    await page.getByPlaceholder('All workflows').click();
    await page.getByRole('option', { name: 'Invoice Approval' }).click();

    await expect(page.getByRole('row', { name: /exec-invoice.*Invoice Approval/ })).toBeVisible();
    await expect(page.getByRole('row', { name: /exec-custome.*Customer Support Flow/ })).not.toBeVisible();
  });

  test('navigates through the app layout and logs out', async ({ page }) => {
    await mockApi(page);

    await page.goto('/login');
    await page.getByLabel('Username').fill('admin');
    await page.getByLabel('Password').fill('correct-password');
    await page.getByRole('button', { name: /sign in/i }).click();

    await expect(page).toHaveURL(/\/$/);
    await page.getByRole('button', { name: 'Toggle theme' }).click();
    await page.getByText('Editor').click();
    await expect(page).toHaveURL(/\/editor$/);

    await page.getByText('Executions').click();
    await expect(page).toHaveURL(/\/executions$/);

    await page.getByRole('button', { name: 'Logout' }).click();
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByText('Sign in to your account')).toBeVisible();
  });
});
