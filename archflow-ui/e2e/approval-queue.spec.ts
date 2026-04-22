import { expect, test, type Page } from '@playwright/test';
import { adminUser, authHandlers, fulfillJson, installApiRouter, installSession } from './support/api';

interface ApprovalRow {
    requestId: string;
    tenantId: string;
    flowId: string;
    stepId: string | null;
    status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'EDITED' | 'EXPIRED';
    description: string | null;
    proposal: Record<string, unknown>;
    createdAt: string;
    expiresAt: string;
}

const pendingSeed: ApprovalRow[] = [
    {
        requestId: 'req-approval-1',
        tenantId: 'default',
        flowId: 'flow-sac',
        stepId: 'step-complaint',
        status: 'PENDING',
        description: 'Approve refund draft for order 12345',
        proposal: { order: '12345', amount: 240.5, reason: 'damaged' },
        createdAt: new Date(Date.now() - 5 * 60_000).toISOString(),
        expiresAt: new Date(Date.now() + 25 * 60_000).toISOString(),
    },
    {
        requestId: 'req-approval-2',
        tenantId: 'default',
        flowId: 'flow-email',
        stepId: 'step-send',
        status: 'PENDING',
        description: 'Outbound email to vip@acme.com',
        proposal: { to: 'vip@acme.com', subject: 'Contract' },
        createdAt: new Date(Date.now() - 20 * 60_000).toISOString(),
        expiresAt: new Date(Date.now() + 10 * 60_000).toISOString(),
    },
];

let pending: ApprovalRow[] = [];
let submitted: { id: string; decision: string }[] = [];

async function mockApi(page: Page) {
    pending = pendingSeed.map((p) => ({ ...p }));
    submitted = [];

    await installApiRouter(page, [
        ...authHandlers(adminUser, { loginShape: 'token', workflows: [], includeApprovals: false }),
        async ({ path, method, route }) => {
            if (path !== '/approvals/pending' || method !== 'GET') return false;
            await fulfillJson(route, pending);
            return true;
        },
        async ({ path, method, route }) => {
            if (path !== '/approvals/pending/count' || method !== 'GET') return false;
            await fulfillJson(route, { count: pending.length });
            return true;
        },
        async ({ path, method, route }) => {
            if (!path.startsWith('/approvals/') || method !== 'GET') return false;
            const id = path.substring('/approvals/'.length);
            const row = pending.find((p) => p.requestId === id);
            if (!row) {
                await route.fulfill({ status: 404, body: 'not found' });
                return true;
            }
            await fulfillJson(route, row);
            return true;
        },
        async ({ path, method, request, route }) => {
            if (!path.startsWith('/approvals/') || method !== 'POST') return false;
            const id = path.substring('/approvals/'.length);
            const body = request.postDataJSON() as { decision: string };
            submitted.push({ id, decision: body.decision });
            const row = pending.find((p) => p.requestId === id);
            if (row) {
                pending = pending.filter((p) => p.requestId !== id);
                await fulfillJson(route, { ...row, status: body.decision });
                return true;
            }
            await route.fulfill({ status: 404, body: 'not found' });
            return true;
        },
    ]);
}

async function authenticate(page: Page) {
    await installSession(page);
}

test.describe('Approval queue', () => {
    test('lists pending approvals with badge count', async ({ page }) => {
        await mockApi(page);
        await authenticate(page);

        await page.goto('/approvals');

        await expect(page.getByRole('heading', { name: 'Approval queue' })).toBeVisible();
        await expect(page.getByText('2 pending')).toBeVisible();
        await expect(page.getByText('Approve refund draft for order 12345')).toBeVisible();
        await expect(page.getByText('Outbound email to vip@acme.com')).toBeVisible();

        // Navbar badge reflects the count
        await expect(page.getByTestId('nav-badge-approvals')).toHaveText('2');
    });

    test('search filters the list', async ({ page }) => {
        await mockApi(page);
        await authenticate(page);

        await page.goto('/approvals');

        await page.getByTestId('approvals-search').fill('refund');
        await expect(page.getByText('Approve refund draft for order 12345')).toBeVisible();
        await expect(page.getByText('Outbound email to vip@acme.com')).toBeHidden();
    });

    test('approving a request submits APPROVED and redirects back', async ({ page }) => {
        await mockApi(page);
        await authenticate(page);

        await page.goto('/approvals');
        await page.getByText('Approve refund draft for order 12345').click();

        await expect(page).toHaveURL(/\/approvals\/req-approval-1$/);
        // UI truncates the request id to its first 12 chars.
        await expect(page.getByRole('heading', { name: /Approval req-approval/ })).toBeVisible();
        await expect(page.getByText(/"reason"\s*:\s*"damaged"/)).toBeVisible();

        await page.getByTestId('approval-approve').click();

        await expect(page).toHaveURL(/\/approvals$/);
        await expect(page.getByText('Outbound email to vip@acme.com')).toBeVisible();
        await expect(page.getByText('Approve refund draft for order 12345')).toBeHidden();
    });

    test('rejecting a request submits REJECTED', async ({ page }) => {
        await mockApi(page);
        await authenticate(page);

        await page.goto('/approvals/req-approval-2');
        await page.getByTestId('approval-reject').click();

        await expect(page).toHaveURL(/\/approvals$/);
        await expect(page.getByText('1 pending')).toBeVisible();
    });
});
