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
                id: 'step-agent',
                type: 'AGENT',
                componentId: 'agent',
                operation: 'Ticket Router',
                position: { x: 200, y: 150 },
                configuration: { provider: 'anthropic', model: 'claude-sonnet-4-6' },
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
        await page.getByLabel('Password').fill('wrong-password');
        await page.getByRole('button', { name: /sign in/i }).click();

        await expect(page.getByText('Invalid credentials')).toBeVisible();

        await page.unroute('**/api/**');
        await mockApi(page, { loginSucceeds: true });
        await page.getByLabel('Password').fill('admin123');
        await page.getByRole('button', { name: /sign in/i }).click();

        await expect(page).toHaveURL(/\/$/);
        await expect(page.getByRole('main').getByText('Workflows', { exact: true })).toBeVisible();
        await expect(page.getByText('Customer Support Flow')).toBeVisible();
    });

    test('lists, searches, creates, executes and deletes workflows', async ({ page }) => {
        await mockApi(page);
        await authenticate(page);

        await page.goto('/');

        await expect(page.getByText('Customer Support Flow')).toBeVisible();
        await expect(page.getByText('Invoice Approval')).toBeVisible();

        await page.getByPlaceholder(/Search workflows/i).fill('invoice');
        await expect(page.getByText('Invoice Approval')).toBeVisible();
        await expect(page.getByText('Customer Support Flow')).toBeHidden();

        await page.getByPlaceholder(/Search workflows/i).fill('');

        await page.locator('button[title="Execute"]').first().click();
        await expect(page).toHaveURL(/\/executions\?id=exec-wf-customer$/);

        await page.goto('/');
        await page.getByRole('button', { name: 'New Workflow' }).click();
        await expect(page).toHaveURL(/\/editor\/wf-created$/);
        await expect(page.getByText('Untitled Workflow')).toBeVisible();

        await page.goto('/');
        await page.locator('button[title="Delete"]').nth(1).click();
        await page.getByRole('dialog', { name: 'Delete Workflow' }).getByRole('button', { name: 'Delete' }).dispatchEvent('click');
        await expect(page.getByText('Invoice Approval')).toBeHidden();
        await expect(page.getByText('Customer Support Flow')).toBeVisible();
    });

    test('shows execution history and filters by workflow', async ({ page }) => {
        await mockApi(page);
        await authenticate(page);

        await page.goto('/executions');

        await expect(page.locator('span', { hasText: 'Customer Support Flow' }).first()).toBeVisible();
        await expect(page.locator('span', { hasText: 'Invoice Approval' }).first()).toBeVisible();
        await expect(page.getByText('Missing invoice total')).toBeVisible();

        await page.locator('select').selectOption('wf-invoice');

        await expect(page.locator('span', { hasText: 'Invoice Approval' }).first()).toBeVisible();
        await expect(page.getByText('Customer Support Flow')).toBeHidden();
        await expect(page.getByText('Missing invoice total')).toBeVisible();
    });
});
