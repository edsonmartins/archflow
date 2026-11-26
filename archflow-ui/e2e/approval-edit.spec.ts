import { expect, test, type Page } from '@playwright/test';
import { adminUser, authHandlers, fulfillJson, installApiRouter, installSession } from './support/api';

const approval = {
    requestId: 'req-edit-1',
    tenantId: 'default',
    flowId: 'flow-sac',
    stepId: 'step-review',
    status: 'PENDING',
    description: 'Adjust payload before approving',
    proposal: { amount: 120, currency: 'BRL', approved: false },
    createdAt: new Date(Date.now() - 5 * 60_000).toISOString(),
    // Prazo no futuro: vencida, a tela esconde a edição e desabilita Aprovar/Rejeitar (RCHFLW-126).
    expiresAt: new Date(Date.now() + 60 * 60_000).toISOString(),
};

async function authenticate(page: Page) {
    await installSession(page);
}

test.describe('Approval edit mode', () => {
    test('rejects invalid edited JSON before submit', async ({ page }) => {
        await installApiRouter(page, [
            ...authHandlers(adminUser, { approvalsCount: 1, includeApprovals: false }),
            async ({ path, method, route }) => {
                if (path !== '/approvals/pending/count' || method !== 'GET') return false;
                await fulfillJson(route, { count: 1 });
                return true;
            },
            async ({ path, method, route }) => {
                if (path !== '/approvals/req-edit-1' || method !== 'GET') return false;
                await fulfillJson(route, approval);
                return true;
            },
        ]);

        await authenticate(page);
        await page.goto('/approvals/req-edit-1', { waitUntil: 'commit' });

        await page.getByTestId('approval-edit-toggle').click();
        await page.getByTestId('approval-edit-payload').fill('{ invalid json ');
        await page.getByTestId('approval-approve').click();

        // Validado antes da confirmação, com o erro junto do campo e o texto preservado (RCHFLW-128)
        await expect(page.getByText('Edited payload is not valid JSON')).toBeVisible();
        await expect(page.getByRole('dialog', { name: 'Approve request' })).toHaveCount(0);
        await expect(page.getByTestId('approval-edit-payload')).toHaveValue('{ invalid json ');
        await expect(page).toHaveURL(/\/approvals\/req-edit-1$/);
    });

    test('submits EDITED decision with modified payload', async ({ page }) => {
        let submitted: Record<string, unknown> | null = null;

        await installApiRouter(page, [
            ...authHandlers(adminUser, { includeApprovals: false }),
            async ({ path, method, route }) => {
                if (path === '/approvals/pending/count' && method === 'GET') {
                    await fulfillJson(route, { count: 1 });
                    return true;
                }
                if (path === '/approvals/pending' && method === 'GET') {
                    await fulfillJson(route, []);
                    return true;
                }
                return false;
            },
            async ({ path, method, route }) => {
                if (path !== '/approvals/req-edit-1' || method !== 'GET') return false;
                await fulfillJson(route, approval);
                return true;
            },
            async ({ path, method, request, route }) => {
                if (path !== '/approvals/req-edit-1' || method !== 'POST') return false;
                submitted = request.postDataJSON() as Record<string, unknown>;
                await fulfillJson(route, { ...approval, status: 'EDITED' });
                return true;
            },
        ]);

        await authenticate(page);
        await page.goto('/approvals/req-edit-1', { waitUntil: 'commit' });

        await page.getByTestId('approval-edit-toggle').click();
        await page.getByTestId('approval-edit-payload').fill(JSON.stringify({ amount: 130, currency: 'BRL', approved: true }, null, 2));
        await page.getByTestId('approval-approve').click();
        await page.getByRole('dialog', { name: 'Approve request' }).getByRole('button', { name: 'Approve' }).click();

        await expect.poll(() => submitted).toEqual({
            tenantId: 'default',
            decision: 'EDITED',
            editedPayload: { amount: 130, currency: 'BRL', approved: true },
            // Sem responderId: o decisor é o usuário autenticado, resolvido pelo servidor (RCHFLW-38).
        });
        await expect(page).toHaveURL(/\/approvals$/);
    });
});
