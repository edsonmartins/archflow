import { expect, test, type Page } from '@playwright/test';
import {
    adminUser,
    authHandlers,
    fulfillJson,
    installApiRouter,
    installSession,
} from './support/api';

type WorkflowSummary = {
    id: string;
    name: string;
    description: string;
    version: string;
    status: string;
    updatedAt: string;
    stepCount: number;
};

type WorkflowDetail = {
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
};

const baseWorkflows: WorkflowSummary[] = [
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

const baseDetails: Record<string, WorkflowDetail> = {
    'wf-customer': {
        id: 'wf-customer',
        metadata: {
            name: 'Customer Support Flow',
            description: 'Routes customer tickets to the right assistant',
            version: '1.2.0',
            category: 'support',
            tags: ['support'],
        },
        steps: [
            {
                // Um nó que o motor executa: o Rodar da lista bloqueia, como o Executar do editor, um fluxo com
                // nó sem execução (RCHFLW-133) — com 'agent' aqui o modal de entrada nunca abria.
                id: 'step-input',
                type: 'input',
                componentId: 'input',
                label: 'Ticket Intake',
                position: { x: 200, y: 150 },
                config: {},
                connections: [],
            },
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
        steps: [],
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

async function authenticate(page: Page) {
    await installSession(page);
}

async function mockApi(page: Page, options?: { loginSucceeds?: boolean }) {
    const loginSucceeds = options?.loginSucceeds ?? true;
    let workflows = [...baseWorkflows];
    const details = { ...baseDetails };

    await installApiRouter(page, [
        ...authHandlers(adminUser, { loginSucceeds, workflows, pendingApprovals: [] }),
        async ({ path, method, route }) => {
            if (path !== '/workflow/providers' || method !== 'GET') return false;
            await fulfillJson(route, [
                {
                    id: 'anthropic',
                    displayName: 'Anthropic',
                    requiresApiKey: true,
                    supportsStreaming: true,
                    group: 'Cloud',
                    models: [
                        {
                            id: 'claude-sonnet-4-6',
                            name: 'Claude Sonnet 4.6',
                            contextWindow: 200000,
                            maxTemperature: 1.0,
                        },
                    ],
                },
            ]);
            return true;
        },
        async ({ path, method, route }) => {
            if (!['/workflow/agent-patterns', '/workflow/personas', '/workflow/governance-profiles', '/workflow/mcp-servers'].includes(path) || method !== 'GET') {
                return false;
            }
            await fulfillJson(route, []);
            return true;
        },
        async ({ path, method, request, route }) => {
            if (path !== '/workflows' || method !== 'POST') return false;
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
            await fulfillJson(route, created, 201);
            return true;
        },
        async ({ path, method, route }) => {
            const workflowMatch = path.match(/^\/workflows\/([^/]+)$/);
            if (!workflowMatch || method !== 'GET') return false;
            const workflow = details[workflowMatch[1]];
            await fulfillJson(route, workflow ?? { message: 'Not found' }, workflow ? 200 : 404);
            return true;
        },
        async ({ path, method, route }) => {
            const workflowMatch = path.match(/^\/workflows\/([^/]+)$/);
            if (!workflowMatch || method !== 'DELETE') return false;
            workflows = workflows.filter((w) => w.id !== workflowMatch[1]);
            await route.fulfill({ status: 204 });
            return true;
        },
        async ({ path, method, route }) => {
            const executeMatch = path.match(/^\/workflows\/([^/]+)\/execute$/);
            if (!executeMatch || method !== 'POST') return false;
            await fulfillJson(route, { executionId: `exec-${executeMatch[1]}`, status: 'RUNNING' });
            return true;
        },
        async ({ path, method, url, route }) => {
            if (path !== '/executions' || method !== 'GET') return false;
            const workflowId = url.searchParams.get('workflowId');
            await fulfillJson(
                route,
                workflowId ? executions.filter((execution) => execution.workflowId === workflowId) : executions,
            );
            return true;
        },
    ], { fallback: '404' });
}

test.describe('Auth and workflows', () => {
    test('shows login error then signs in successfully', async ({ page }) => {
        await mockApi(page, { loginSucceeds: false });

        await page.goto('/login');
        await page.getByLabel('Username').fill('admin');
        await page.getByRole('textbox', { name: 'Password' }).fill('wrong-password');
        await page.getByRole('button', { name: /sign in/i }).click();

        await expect(page.getByText('Invalid credentials')).toBeVisible();

        await page.unroute('**/api/**');
        await mockApi(page, { loginSucceeds: true });
        await page.getByRole('textbox', { name: 'Password' }).fill('admin123');
        await page.getByRole('button', { name: /sign in/i }).click();

        await expect(page).toHaveURL(/\/$/);
        await expect(page.getByRole('main').getByText('Workflows', { exact: true })).toBeVisible();
        await expect(page.getByText('Customer Support Flow')).toBeVisible();
    });

    test('lists, searches, creates, executes and deletes workflows', async ({ page }) => {
        await mockApi(page);
        await authenticate(page);

        await page.goto('/workflows');

        await expect(page.getByText('Customer Support Flow')).toBeVisible();
        await expect(page.getByText('Invoice Approval')).toBeVisible();

        await page.getByPlaceholder(/Search workflows/i).fill('invoice');
        await expect(page.getByText('Invoice Approval')).toBeVisible();
        await expect(page.getByText('Customer Support Flow')).toBeHidden();

        await page.getByPlaceholder(/Search workflows/i).fill('');

        // Execute asks for the run input first (RunInputModal), sends a JSON object as the variables,
        // and opens the execution DETAIL — /executions?id=… used to land on the whole history.
        await page.getByRole('button', { name: 'Execute' }).first().click();
        await page.getByTestId('run-input-text').fill('{"message": "my invoice is overdue"}');
        const executeRequest = page.waitForRequest(r => /\/workflows\/wf-customer\/execute$/.test(r.url()) && r.method() === 'POST');
        await page.getByTestId('run-input-confirm').click();
        expect((await executeRequest).postDataJSON()).toEqual({ message: 'my invoice is overdue' });
        await expect(page).toHaveURL(/\/executions\/exec-wf-customer$/);

        await page.goto('/workflows');
        await page.getByRole('button', { name: 'New Workflow' }).click();
        // The create modal offers scratch / AI / template — take scratch.
        await page.getByTestId('create-blank').click();
        // RCHFLW-131: o modal abre um rascunho, sem gravar; o fluxo nasce no primeiro Salvar.
        await expect(page).toHaveURL(/\/editor\?new=1$/);
        await expect(page.getByText('Untitled Workflow')).toBeVisible();
        await page.getByTestId('editor-save').click();
        await expect(page).toHaveURL(/\/editor\/wf-created$/);

        await page.goto('/workflows');
        await page.getByRole('button', { name: 'Delete' }).nth(1).click();
        await page.getByRole('dialog', { name: 'Delete Workflow' }).getByRole('button', { name: 'Delete' }).dispatchEvent('click');
        // Pelo card, não pelo texto: o diálogo de exclusão cita o nome do fluxo (RCHFLW-122) enquanto fecha.
        await expect(page.getByTestId('workflow-card-wf-invoice')).toBeHidden();
        await expect(page.getByText('Customer Support Flow')).toBeVisible();
    });

    test('shows execution history and filters by workflow', async ({ page }) => {
        await mockApi(page);
        await authenticate(page);

        await page.goto('/executions');

        // VTable: the canvas is not readable; the accessible mirror table carries the text.
        await expect(page.getByRole('cell', { name: 'Customer Support Flow' })).toBeAttached();
        await expect(page.getByRole('cell', { name: 'Invoice Approval' })).toBeAttached();
        await expect(page.getByText('Missing invoice total')).toBeAttached();

        await page.getByRole('combobox', { name: 'All workflows' }).click();
        await page.getByRole('option', { name: 'Invoice Approval' }).click();

        await expect(page.getByRole('cell', { name: 'Invoice Approval' })).toBeAttached();
        await expect(page.getByText('Customer Support Flow')).toBeHidden();
        await expect(page.getByText('Missing invoice total')).toBeAttached();
    });

    // RCHFLW-117: um id que o backend não reconhece (excluído, de outro tenant) respondia 404 e o editor
    // abria um canvas em branco, "Untitled workflow" — parecia um fluxo novo e o Salvar estava ali.
    test('editor shows "Workflow not found" for an unknown id and goes back to the list', async ({ page }) => {
        await mockApi(page);
        await authenticate(page);

        await page.goto('/editor/wf-missing');

        await expect(page.getByTestId('editor-not-found')).toBeVisible();
        await expect(page.getByText('Workflow not found')).toBeVisible();
        await expect(page.getByTestId('editor-save')).toHaveCount(0);
        await expect(page.getByTestId('editor-canvas-pane')).toHaveCount(0);

        await page.getByRole('button', { name: 'Back to workflow list' }).click();
        await expect(page).toHaveURL(/\/workflows$/);
        await expect(page.getByText('Customer Support Flow')).toBeVisible();
    });
});
