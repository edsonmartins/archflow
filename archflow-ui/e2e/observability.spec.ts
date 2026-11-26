import { expect, test, type Page } from '@playwright/test';
import { authHandlers, fulfillJson, installApiRouter, installSession, superadminUser } from './support/api';

const overview = {
    totalExecutionsToday: 42,
    successRate: 0.88,
    avgLatencyMs: 612,
    p95LatencyMs: 1280,
    errorRate: 0.12,
    activeStreams: 3,
    totalAuditEventsToday: 87,
    topPersonas: [
        { personaId: 'order_tracking', executionCount: 25, successRate: 0.92 },
        { personaId: 'complaint', executionCount: 10, successRate: 0.80 },
    ],
    latencySparkline: [420, 500, 600, 700, 820, 650, 540, 480, 600, 700, 680, 612],
};

const traces = {
    items: [
        {
            traceId: 'trace-aaaa-1111',
            tenantId: 'acme',
            personaId: 'order_tracking',
            flowId: 'flow-sac',
            executionId: 'exec-1',
            startedAt: '2026-04-11T10:00:00.000Z',
            durationMs: 450,
            status: 'OK',
            spanCount: 3,
            error: null,
        },
        {
            traceId: 'trace-bbbb-2222',
            tenantId: 'acme',
            personaId: 'complaint',
            flowId: 'flow-sac',
            executionId: 'exec-2',
            startedAt: '2026-04-11T09:58:00.000Z',
            durationMs: 1200,
            status: 'ERROR',
            spanCount: 4,
            error: 'timeout',
        },
    ],
    page: 0,
    pageSize: 50,
    total: 2,
};

const traceDetail = {
    traceId: 'trace-aaaa-1111',
    tenantId: 'acme',
    personaId: 'order_tracking',
    flowId: 'flow-sac',
    executionId: 'exec-1',
    startedAt: '2026-04-11T10:00:00.000Z',
    durationMs: 450,
    status: 'OK',
    error: null,
    attributes: { 'flow.name': 'sac-basic', tenant: 'acme' },
    spans: [
        {
            spanId: 'span-1',
            parentSpanId: null,
            name: 'flow.execute',
            kind: 'INTERNAL',
            startedAt: '2026-04-11T10:00:00.000Z',
            durationMs: 200,
            status: 'OK',
            attributes: { step: 'router' },
            events: [],
        },
        {
            spanId: 'span-2',
            parentSpanId: 'span-1',
            name: 'agent.run',
            kind: 'CLIENT',
            startedAt: '2026-04-11T10:00:00.050Z',
            durationMs: 180,
            status: 'OK',
            attributes: { agent: 'order_tracking' },
            events: [],
        },
    ],
};

const audit = {
    items: [
        {
            id: 'audit-1',
            timestamp: '2026-04-11T10:00:30.000Z',
            action: 'WORKFLOW_EXECUTE',
            userId: 'user-1',
            username: 'alice',
            resourceType: 'workflow',
            resourceId: 'wf-sac',
            success: true,
            errorMessage: null,
            context: {},
            ipAddress: '10.0.0.1',
            sessionId: null,
            traceId: 'trace-aaaa-1111',
        },
        {
            id: 'audit-2',
            timestamp: '2026-04-11T09:55:00.000Z',
            action: 'LOGIN_SUCCESS',
            userId: 'user-2',
            username: 'bob',
            resourceType: null,
            resourceId: null,
            success: true,
            errorMessage: null,
            context: {},
            ipAddress: '10.0.0.2',
            sessionId: null,
            traceId: null,
        },
    ],
    page: 0,
    pageSize: 50,
    total: 2,
};

const metricsSnapshot = {
    timestamp: '2026-04-11T10:00:00.000Z',
    counters: { 'flows.started': 42, 'flows.completed': 37 },
    values: { 'cpu.usage': 0.45 },
    stats: {
        'flow.duration': { count: 42, min: 120, max: 2100, mean: 612, median: 540 },
    },
};

const metricSeries = [
    {
        metric: 'latency_ms',
        buckets: Array.from({ length: 12 }, (_, i) =>
            new Date(Date.now() - (11 - i) * 60_000).toISOString(),
        ),
        values: [400, 450, 500, 520, 600, 650, 700, 680, 620, 580, 550, 540],
    },
];

const executions = [
    {
        id: 'exec-live-1',
        workflowId: 'wf-sac',
        workflowName: 'SAC',
        status: 'RUNNING',
        startedAt: '2026-04-11T10:00:00.000Z',
        completedAt: null,
        duration: null,
        error: null,
    },
];

async function mockApi(page: Page) {
    await installApiRouter(page, [
        ...authHandlers(superadminUser, { loginShape: 'token' }),
        async ({ path, route }) => {
            if (path !== '/admin/observability/overview') return false;
            await fulfillJson(route, overview);
            return true;
        },
        async ({ path, method, route }) => {
            if (path !== '/admin/observability/traces' || method !== 'GET') return false;
            await fulfillJson(route, traces);
            return true;
        },
        async ({ path, route }) => {
            if (path !== '/admin/observability/traces/trace-aaaa-1111') return false;
            await fulfillJson(route, traceDetail);
            return true;
        },
        async ({ path, method, route }) => {
            if (path !== '/admin/observability/metrics' || method !== 'GET') return false;
            await fulfillJson(route, metricsSnapshot);
            return true;
        },
        async ({ path, route }) => {
            if (path !== '/admin/observability/metrics/series') return false;
            await fulfillJson(route, metricSeries);
            return true;
        },
        async ({ path, method, route }) => {
            if (path !== '/admin/observability/audit' || method !== 'GET') return false;
            await fulfillJson(route, audit);
            return true;
        },
        async ({ path, method, route }) => {
            if (path !== '/admin/observability/audit/export' || method !== 'GET') return false;
            await route.fulfill({
                status: 200,
                contentType: 'text/csv',
                body: 'id,timestamp,action\naudit-2,2026-04-11T09:00:00Z,LOGIN_SUCCESS\n',
            });
            return true;
        },
        // Live events escolhe a execução desta lista (RCHFLW-50).
        async ({ path, method, route }) => {
            if (path !== '/executions' || method !== 'GET') return false;
            await fulfillJson(route, executions);
            return true;
        },
    ]);
}

async function authenticateAsSuperadmin(page: Page) {
    await installSession(page, { role: 'superadmin' });
}

test.describe('Admin observability surface', () => {
    test('overview renders aggregated cards and top personas', async ({ page }) => {
        await mockApi(page);
        await authenticateAsSuperadmin(page);

        await page.goto('/admin/observability');

        await expect(page.getByRole('tab', { name: 'Overview' })).toBeVisible();
        await expect(page.getByText('Executions today')).toBeVisible();
        await expect(page.getByText('42')).toBeVisible();
        await expect(page.getByText('88.0%')).toBeVisible();
        await expect(page.getByText('612 ms')).toBeVisible();
        await expect(page.getByText('order_tracking').first()).toBeVisible();
    });

    test('traces list supports drill-down to detail', async ({ page }) => {
        await mockApi(page);
        await authenticateAsSuperadmin(page);

        await page.goto('/admin/observability/traces');

        // The UI truncates the trace id to its first 12 chars.
        // The list is a VTable: its rows are read and clicked through the accessible mirror.
        await expect(page.getByText('trace-aaaa-1', { exact: true }).first()).toBeAttached();
        await expect(page.getByText('trace-bbbb-2', { exact: true }).first()).toBeAttached();

        await page.getByText('trace-aaaa-1', { exact: true }).first().dispatchEvent('click');
        await expect(page).toHaveURL(/\/traces\/trace-aaaa-1111$/);
        await expect(page.getByRole('heading', { name: /Trace trace-aaaa-1/ })).toBeVisible();
        await expect(page.getByText('flow.execute').first()).toBeVisible();
        await expect(page.getByText('agent.run').first()).toBeVisible();
    });

    test('metrics page loads snapshot + series', async ({ page }) => {
        await mockApi(page);
        await authenticateAsSuperadmin(page);

        await page.goto('/admin/observability/metrics');

        await expect(page.getByText('Latency (ms)').first()).toBeVisible();
        await expect(page.getByText('flows.started')).toBeVisible();
        await expect(page.getByText('flow.duration')).toBeAttached();
    });

    test('audit log lists entries and exports the CSV through the session, with the screen filters', async ({ page }) => {
        await mockApi(page);
        await authenticateAsSuperadmin(page);

        await page.goto('/admin/observability/audit');

        await expect(page.getByText('WORKFLOW_EXECUTE')).toBeAttached();
        await expect(page.getByText('LOGIN_SUCCESS')).toBeAttached();
        await expect(page.getByText('alice')).toBeAttached();

        // O export era um `<a href>`: a navegação não leva o Authorization, e o arquivo salvo seria o 401
        // (RCHFLW-195 item 9). Agora é um botão que baixa pela sessão, com os filtros da tela (RCHFLW-180).
        const filtrada = page.waitForRequest((r) => r.url().includes('/admin/observability/audit?')
            && new URL(r.url()).searchParams.get('action') === 'LOGIN_SUCCESS');
        await page.getByTestId('audit-action').fill('LOGIN_SUCCESS');
        await filtrada;

        const exportButton = page.getByTestId('audit-export');
        await expect(exportButton).not.toHaveAttribute('href', /.*/);
        const exportRequest = page.waitForRequest((r) => r.url().includes('/admin/observability/audit/export'));
        const download = page.waitForEvent('download');
        await exportButton.click();

        const request = await exportRequest;
        expect(new URL(request.url()).searchParams.get('action')).toBe('LOGIN_SUCCESS');
        expect(request.headers()['authorization']).toMatch(/^Bearer /);
        expect((await download).suggestedFilename()).toBe('audit-log.csv');
    });

    test('live events page requires attach before streaming', async ({ page }) => {
        await mockApi(page);
        await authenticateAsSuperadmin(page);

        await page.goto('/admin/observability/live');

        await expect(page.getByTestId('live-status')).toHaveText('idle');
        await expect(page.getByTestId('live-attach')).toBeDisabled();

        // Sem tenant nem sessão livres por padrão: escolhe-se a execução (RCHFLW-50).
        await expect(page.getByTestId('live-tenant')).toHaveCount(0);
        await page.getByTestId('live-execution').click();
        await page.getByRole('option', { name: /exec-live-1/ }).click();
        await expect(page.getByTestId('live-attach')).toBeEnabled();
    });

    test('live events free channel still accepts a typed tenant and session', async ({ page }) => {
        await mockApi(page);
        await authenticateAsSuperadmin(page);

        await page.goto('/admin/observability/live');

        await page.getByTestId('live-free-channel').check();
        await expect(page.getByTestId('live-attach')).toBeDisabled();
        await page.getByTestId('live-tenant').fill('acme');
        await page.getByTestId('live-session').fill('conv-1');
        await expect(page.getByTestId('live-attach')).toBeEnabled();
    });
});
