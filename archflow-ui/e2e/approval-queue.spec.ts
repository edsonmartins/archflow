import { expect, test, type Page, type Route } from '@playwright/test';

const user = {
    id: 'user-e2e',
    username: 'admin',
    name: 'Admin User',
    roles: ['admin'],
};

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
        if (path === '/approvals/pending' && method === 'GET') {
            await json(route, pending);
            return;
        }
        if (path === '/approvals/pending/count' && method === 'GET') {
            await json(route, { count: pending.length });
            return;
        }
        if (path.startsWith('/approvals/') && method === 'GET') {
            const id = path.substring('/approvals/'.length);
            const row = pending.find((p) => p.requestId === id);
            if (!row) {
                await route.fulfill({ status: 404, body: 'not found' });
                return;
            }
            await json(route, row);
            return;
        }
        if (path.startsWith('/approvals/') && method === 'POST') {
            const id = path.substring('/approvals/'.length);
            const body = route.request().postDataJSON() as { decision: string };
            submitted.push({ id, decision: body.decision });
            const row = pending.find((p) => p.requestId === id);
            if (row) {
                pending = pending.filter((p) => p.requestId !== id);
                await json(route, { ...row, status: body.decision });
                return;
            }
            await route.fulfill({ status: 404, body: 'not found' });
            return;
        }
        if (path === '/workflows' && method === 'GET') {
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
