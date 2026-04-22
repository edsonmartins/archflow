import { expect, test, type Page } from '@playwright/test';
import { authHandlers, fulfillJson, installApiRouter, installSession, superadminUser } from './support/api';

async function authenticateAsSuperadmin(page: Page) {
    await installSession(page, { role: 'superadmin' });
}

test.describe('Observability interactions', () => {
    test('filters traces by search and status', async ({ page }) => {
        await installApiRouter(page, [
            ...authHandlers(superadminUser, { workflows: [] }),
            async ({ path, method, url, route }) => {
                if (path !== '/admin/observability/traces' || method !== 'GET') return false;
                const search = url.searchParams.get('search') ?? '';
                const status = url.searchParams.get('status') ?? '';
                const all = [
                    {
                        traceId: 'trace-ok-1234',
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
                        traceId: 'trace-error-5678',
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
                ];
                const filtered = all.filter((trace) => {
                    const haystack = `${trace.traceId} ${trace.flowId} ${trace.executionId} ${trace.personaId ?? ''} ${trace.tenantId ?? ''}`.toLowerCase();
                    const matchesSearch = search ? haystack.includes(search.toLowerCase()) : true;
                    const matchesStatus = status ? trace.status === status : true;
                    return matchesSearch && matchesStatus;
                });
                await fulfillJson(route, { items: filtered, page: 0, pageSize: 50, total: filtered.length });
                return true;
            },
        ]);

        await authenticateAsSuperadmin(page);
        await page.goto('/admin/observability/traces');

        await expect(page.getByText('trace-ok-1234'.slice(0, 12))).toBeVisible();
        await expect(page.getByText('trace-error-5678'.slice(0, 12))).toBeVisible();

        await page.getByTestId('traces-search').fill('error');
        await expect(page.getByText('trace-error-5678'.slice(0, 12))).toBeVisible();
        await expect(page.getByText('trace-ok-1234'.slice(0, 12))).toBeHidden();

        await page.getByTestId('traces-search').fill('');
        await page.getByTestId('traces-status').click();
        await page.getByRole('option', { name: 'OK' }).click();

        await expect(page.getByText('trace-ok-1234'.slice(0, 12))).toBeVisible();
        await expect(page.getByText('trace-error-5678'.slice(0, 12))).toBeHidden();
    });

    test('changes metrics selectors and reloads the chart title', async ({ page }) => {
        await installApiRouter(page, [
            ...authHandlers(superadminUser, { workflows: [] }),
            async ({ path, method, route }) => {
                if (path !== '/admin/observability/metrics' || method !== 'GET') return false;
                await fulfillJson(route, {
                    timestamp: '2026-04-11T10:00:00.000Z',
                    counters: { 'flows.started': 42 },
                    values: { 'cpu.usage': 0.45 },
                    stats: {},
                });
                return true;
            },
            async ({ path, method, url, route }) => {
                if (path !== '/admin/observability/metrics/series' || method !== 'GET') return false;
                const metric = url.searchParams.get('metric');
                await fulfillJson(route, [
                    {
                        metric,
                        buckets: ['a', 'b', 'c'],
                        values: metric === 'error_rate' ? [0.02, 0.03, 0.01] : [400, 420, 390],
                    },
                ]);
                return true;
            },
        ]);

        await authenticateAsSuperadmin(page);
        await page.goto('/admin/observability/metrics');

        await expect(page.getByRole('heading', { name: 'Latency (ms)' })).toBeVisible();

        await page.getByTestId('metric-select').click();
        await page.getByRole('option', { name: 'Error rate' }).click();
        await expect(page.getByRole('heading', { name: 'Error rate' })).toBeVisible();

        await page.getByTestId('bucket-select').click();
        await page.getByRole('option', { name: '1 min' }).click();
        await expect(page.getByText('3 buckets')).toBeVisible();
    });

    test('lists running flows and cancels one flow', async ({ page }) => {
        let flows = [
            {
                flowId: 'flow-running-1',
                tenantId: 'acme',
                startedAt: new Date(Date.now() - 30_000).toISOString(),
                currentStepId: 'step-router',
                stepIndex: 1,
                stepCount: 3,
                durationMs: 30000,
            },
        ];

        await installApiRouter(page, [
            ...authHandlers(superadminUser, { workflows: [] }),
            async ({ path, method, route }) => {
                if (path === '/admin/observability/running' && method === 'GET') {
                    await fulfillJson(route, flows);
                    return true;
                }
                if (path !== '/admin/observability/running/flow-running-1/cancel' || method !== 'POST') return false;
                flows = [];
                await fulfillJson(route, {});
                return true;
            },
        ]);

        await authenticateAsSuperadmin(page);
        await page.goto('/admin/observability/running');

        await expect(page.getByText('flow-running-1')).toBeVisible();
        await expect(page.getByText('step-router')).toBeVisible();

        await page.locator('tbody tr').first().getByRole('button').click();
        await page.getByRole('button', { name: 'Cancel flow' }).dispatchEvent('click');

        await expect(page.getByRole('main').getByText('flow-running-1', { exact: true })).toBeHidden();
        await expect(page.getByText('No flows running right now')).toBeVisible();
    });
});
