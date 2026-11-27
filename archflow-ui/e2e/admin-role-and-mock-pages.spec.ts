import { expect, test, type Page } from '@playwright/test';
import {
    authHandlers,
    fulfillJson,
    installApiRouter,
    installSession,
    superadminUser,
} from './support/api';

async function setSession(page: Page, role: 'superadmin' | 'tenant_admin') {
    await installSession(page, { role });
}

async function installAdminApi(page: Page, { impersonationEnabled = true } = {}) {
    let tenants = [
        {
            id: 'tenant_rio_quality',
            name: 'Rio Quality',
            plan: 'enterprise',
            status: 'active',
            adminEmail: 'admin@rioquality.com.br',
            sector: 'Food Distribution',
            limits: {
                executionsPerDay: 500,
                tokensPerMonth: 5_000_000,
                maxWorkflows: 20,
                maxUsers: 10,
                allowedModels: ['gpt-4o'],
                featuresEnabled: ['hitl'],
            },
            usage: {
                executionsToday: 340,
                tokensThisMonth: 4_200_000,
                workflowCount: 12,
                userCount: 5,
            },
            allowedModels: ['gpt-4o'],
            createdAt: '2025-06',
        },
        {
            id: 'tenant_demo',
            name: 'Demo Trial',
            plan: 'trial',
            status: 'trial',
            adminEmail: 'trial@demo.com',
            sector: 'Tech',
            limits: {
                executionsPerDay: 50,
                tokensPerMonth: 100_000,
                maxWorkflows: 3,
                maxUsers: 2,
                allowedModels: ['gpt-4o-mini'],
                featuresEnabled: [],
            },
            usage: {
                executionsToday: 12,
                tokensThisMonth: 50_000,
                workflowCount: 2,
                userCount: 1,
            },
            allowedModels: ['gpt-4o-mini'],
            createdAt: '2026-04',
        },
    ];

    // A forma do backend real (RCHFLW-85): papel com o nome do backend e convite com validade.
    let users = [
        {
            id: 'user-1',
            name: 'Ana Admin',
            email: 'ana@rioquality.com.br',
            role: 'ADMIN',
            status: 'active',
            lastAccessAt: '2026-04-09T10:00:00Z' as string | null,
            invitationExpiresAt: null as string | null,
        },
        {
            id: 'user-2',
            name: 'Bruno Designer',
            email: 'bruno@rioquality.com.br',
            role: 'DESIGNER',
            status: 'active',
            lastAccessAt: '2026-04-08T10:00:00Z' as string | null,
            invitationExpiresAt: null as string | null,
        },
    ];

    const roles = [
        { name: 'ADMIN', description: 'Full system access', permissions: ['*:*'] },
        { name: 'DESIGNER', description: 'Can create and edit workflows',
            permissions: ['agent:read', 'workflow:create', 'workflow:delete', 'workflow:execute', 'workflow:read',
                'workflow:update'] },
        { name: 'EXECUTOR', description: 'Can execute workflows only', permissions: ['workflow:execute', 'workflow:read'] },
        { name: 'VIEWER', description: 'Read-only access', permissions: ['agent:read', 'execution:read', 'workflow:read'] },
    ];

    let toggles = {
        allowLocalModels: true,
        humanInTheLoop: true,
        brainSentry: true,
        debugMode: false,
        linktorNotifications: false,
        auditLog: true,
    };

    await installApiRouter(page, [
        ...authHandlers(superadminUser, { workflows: [], loginShape: 'token' }),
        async ({ path, method, route }) => {
            if (path !== '/admin/tenants' || method !== 'GET') return false;
            await fulfillJson(route, tenants);
            return true;
        },
        async ({ path, method, route }) => {
            if (path !== '/admin/tenants/stats' || method !== 'GET') return false;
            await fulfillJson(route, {
                totalActive: tenants.filter((tenant) => tenant.status === 'active').length,
                totalTrial: tenants.filter((tenant) => tenant.status === 'trial').length,
                executionsToday: tenants.reduce((sum, tenant) => sum + tenant.usage.executionsToday, 0),
                tokensThisMonth: tenants.reduce((sum, tenant) => sum + tenant.usage.tokensThisMonth, 0),
                impersonationEnabled,
            });
            return true;
        },
        async ({ path, method, request, route }) => {
            if (path !== '/admin/tenants' || method !== 'POST') return false;
            const body = request.postDataJSON() as Record<string, unknown>;
            const created = {
                id: body.tenantId,
                name: body.name,
                plan: body.plan,
                status: body.plan === 'trial' ? 'trial' : 'active',
                adminEmail: body.adminEmail,
                sector: body.sector,
                limits: {
                    executionsPerDay: (body.limits as Record<string, number>)?.executionsPerDay ?? 500,
                    tokensPerMonth: (body.limits as Record<string, number>)?.tokensPerMonth ?? 5_000_000,
                    maxWorkflows: (body.limits as Record<string, number>)?.maxWorkflows ?? 20,
                    maxUsers: (body.limits as Record<string, number>)?.maxUsers ?? 10,
                    allowedModels: ['gpt-4o'],
                    featuresEnabled: [],
                },
                usage: { executionsToday: 0, tokensThisMonth: 0, workflowCount: 0, userCount: 0 },
                allowedModels: ['gpt-4o'],
                createdAt: '2026-04',
            };
            tenants = [...tenants, created as typeof tenants[number]];
            await fulfillJson(route, created, 201);
            return true;
        },
        async ({ path, method, route }) => {
            const tenantMatch = path.match(/^\/admin\/tenants\/([^/]+)$/);
            if (!tenantMatch || method !== 'GET') return false;
            const tenant = tenants.find((entry) => entry.id === tenantMatch[1]);
            await fulfillJson(route, tenant ?? { message: 'Not found' }, tenant ? 200 : 404);
            return true;
        },
        async ({ path, method, route }) => {
            const tenantMatch = path.match(/^\/admin\/tenants\/([^/]+)$/);
            if (!tenantMatch || method !== 'DELETE') return false;
            tenants = tenants.filter((entry) => entry.id !== tenantMatch[1]);
            await route.fulfill({ status: 204 });
            return true;
        },
        async ({ path, method, route }) => {
            if (path !== '/admin/tenants/tenant_rio_quality/suspend' || method !== 'POST') return false;
            tenants = tenants.map((tenant) => tenant.id === 'tenant_rio_quality' ? { ...tenant, status: 'suspended' } : tenant);
            await fulfillJson(route, {});
            return true;
        },
        async ({ path, method, route }) => {
            if (path !== '/admin/tenants/tenant_rio_quality/activate' || method !== 'POST') return false;
            tenants = tenants.map((tenant) => tenant.id === 'tenant_rio_quality' ? { ...tenant, status: 'active' } : tenant);
            await fulfillJson(route, {});
            return true;
        },
        async ({ path, method, route }) => {
            if (path === '/admin/workspace/users' && method === 'GET') {
                await fulfillJson(route, users);
                return true;
            }
            if (path === '/admin/workspace/roles' && method === 'GET') {
                await fulfillJson(route, roles);
                return true;
            }
            if (path !== '/admin/workspace/summary' || method !== 'GET') return false;
            await fulfillJson(route, {
                tenantId: 'tenant_rio_quality',
                tenantName: 'Rio Quality',
                plan: 'enterprise',
                status: 'active',
                executionsToday: 340,
                tokensThisMonth: 4_200_000,
                workflowCount: 12,
                userCount: users.length,
                apiKeyCount: 1,
                usageThisMonth: {
                    executions: 2, tokensInput: 3_000, tokensOutput: 1_000, costCents: 1, costReais: 0.005,
                    declaredCostUsd: null, executionsWithoutPrice: 0,
                },
                limits: {
                    executionsPerDay: 500,
                    tokensPerMonth: 5_000_000,
                    maxWorkflows: 20,
                    maxUsers: 10,
                    allowedModels: ['gpt-4o'],
                },
            });
            return true;
        },
        async ({ path, method, request, route }) => {
            if (path !== '/admin/workspace/users/invite' || method !== 'POST') return false;
            const body = request.postDataJSON() as { email: string; role: string };
            const created = {
                id: `user-${users.length + 1}`,
                name: body.email,
                email: body.email,
                role: body.role,
                status: 'invited',
                lastAccessAt: null,
                invitationExpiresAt: '2026-04-24T10:00:00Z',
            };
            users = [...users, created];
            await fulfillJson(route, { user: created, invitationToken: `${created.id}.SEGREDO`,
                expiresAt: created.invitationExpiresAt }, 201);
            return true;
        },
        async ({ path, method, request, route }) => {
            const userMatch = path.match(/^\/admin\/workspace\/users\/([^/]+)$/);
            if (!userMatch || method !== 'PUT') return false;
            const body = request.postDataJSON() as { role: string };
            const updated = users.find((entry) => entry.id === userMatch[1]);
            if (!updated) {
                await fulfillJson(route, { message: 'Not found' }, 404);
                return true;
            }
            const next = { ...updated, role: body.role };
            users = users.map((entry) => entry.id === next.id ? next : entry);
            await fulfillJson(route, next);
            return true;
        },
        async ({ path, method, route }) => {
            const userMatch = path.match(/^\/admin\/workspace\/users\/([^/]+)$/);
            if (!userMatch || method !== 'DELETE') return false;
            users = users.filter((entry) => entry.id !== userMatch[1]);
            await route.fulfill({ status: 204 });
            return true;
        },
        async ({ path, method, route }) => {
            const revokeMatch = path.match(/^\/admin\/workspace\/users\/([^/]+)\/revoke$/);
            if (!revokeMatch || method !== 'POST') return false;
            users = users.filter((entry) => entry.id !== revokeMatch[1]);
            await route.fulfill({ status: 204 });
            return true;
        },
        async ({ path, method, route }) => {
            if (path === '/admin/global/models' && method === 'GET') {
                await fulfillJson(route, [
                { id: 'gpt-4o', name: 'GPT-4o', provider: 'OpenAI', status: 'active', costInputPer1M: 2.5, costOutputPer1M: 10 },
                { id: 'gemma-3-27b', name: 'Gemma 3 27B', provider: 'Local', status: 'beta', costInputPer1M: 0, costOutputPer1M: 0 },
            ]);
                return true;
            }
            if (path !== '/admin/global/plans' || method !== 'GET') return false;
            await fulfillJson(route, [
                { plan: 'enterprise', executionsPerDay: 1000, tokensPerMonth: 10000000, maxWorkflows: 50, maxUsers: 25 },
                { plan: 'professional', executionsPerDay: 500, tokensPerMonth: 5000000, maxWorkflows: 20, maxUsers: 10 },
            ]);
            return true;
        },
        async ({ path, method, request, route }) => {
            if (path === '/admin/global/toggles' && method === 'GET') {
                await fulfillJson(route, toggles);
                return true;
            }
            if (path !== '/admin/global/toggles' || method !== 'PUT') return false;
            toggles = request.postDataJSON() as typeof toggles;
            await fulfillJson(route, {});
            return true;
        },
        async ({ path, method, route }) => {
            const modelMatch = path.match(/^\/admin\/global\/models\/([^/]+)$/);
            if (modelMatch && method === 'PUT') {
                await fulfillJson(route, {});
                return true;
            }
            if (!path.startsWith('/admin/global/usage') || method !== 'GET') return false;
            await fulfillJson(route, [
                { tenantId: 'tenant_rio_quality', tenantName: 'Rio Quality', executions: 8400, tokensInput: 3200000, tokensOutput: 1000000, estimatedCost: 48.5, percentOfTotal: 62, planLimit: 10000000 },
                { tenantId: 'tenant_demo', tenantName: 'Demo Trial', executions: 350, tokensInput: 40000, tokensOutput: 10000, estimatedCost: 1.1, percentOfTotal: 14, planLimit: 100000 },
            ]);
            return true;
        },
    ]);
}

test.describe('Admin roles and integrated pages', () => {
    test('redirects tenant admin away from superadmin routes', async ({ page }) => {
        await installAdminApi(page);
        await setSession(page, 'tenant_admin');

        await page.goto('/admin/tenants');

        await expect(page).toHaveURL(/\/$/);
        await expect(page.getByRole('main').getByText('Workflows', { exact: true })).toBeVisible();
    });

    test('superadmin impersonates a tenant and sees live workspace data', async ({ page }) => {
        await installAdminApi(page);
        await setSession(page, 'superadmin');

        await page.goto('/admin/tenants');

        await page.getByRole('button', { name: 'Enter' }).first().dispatchEvent('click');
        await expect(page).toHaveURL(/\/admin\/workspace$/);
        await expect(page.getByTestId('impersonation-banner')).toBeVisible();
        await expect(page.getByText('Executions today')).toBeVisible();
        await expect(page.getByRole('main').getByText('API Keys', { exact: true })).toBeVisible();

        await page.getByRole('button', { name: /Back to superadmin/i }).click();
        await expect(page.getByTestId('impersonation-banner')).toBeHidden();
    });

    test('impersonation turned off on the server: no Enter action, and the hint says why', async ({ page }) => {
        // RCHFLW-198: com archflow.admin.impersonation.enabled=false, "Entrar" levava a 403 em toda tela.
        await installAdminApi(page, { impersonationEnabled: false });
        await setSession(page, 'superadmin');

        await page.goto('/admin/tenants');

        await expect(page.getByText('Entering as the tenant is turned off on this server', { exact: false }))
            .toBeVisible();
        await expect(page.getByRole('button', { name: 'Details' }).first()).toBeAttached();
        await expect(page.getByRole('button', { name: 'Enter' })).toHaveCount(0);
    });

    test('creates a tenant through the real create endpoint', async ({ page }) => {
        await installAdminApi(page);
        await setSession(page, 'superadmin');

        await page.goto('/admin/tenants/new');

        await page.getByLabel('Tenant name').fill('ACME Health');
        await page.getByLabel('Admin email').fill('admin@acme.health');
        await page.getByRole('combobox', { name: 'Sector' }).click();
        await page.getByRole('option', { name: 'Healthcare' }).click();
        await page.getByRole('combobox', { name: 'Plan' }).click();
        await page.getByRole('option', { name: 'Enterprise' }).click();

        await page.getByRole('button', { name: 'Create tenant' }).click();
        await expect(page).toHaveURL(/\/admin\/tenants$/);
        await expect(page.getByRole('main').getByText('ACME Health', { exact: true })).toBeAttached();
    });

    test('invites users through workspace endpoints', async ({ page }) => {
        await installAdminApi(page);
        await setSession(page, 'tenant_admin');

        await page.goto('/admin/workspace/users');

        await page.getByRole('button', { name: 'Invite User' }).click();
        const inviteDialog = page.getByRole('dialog', { name: 'Invite User' });
        await inviteDialog.getByLabel('Email').fill('new.user@acme.com');
        await inviteDialog.getByRole('combobox', { name: 'Role' }).click();
        await page.getByRole('option', { name: 'Viewer' }).click();
        await inviteDialog.getByRole('button', { name: 'Send Invite' }).dispatchEvent('click');
        // O produto não envia e-mail: o link de aceite aparece uma vez para quem convidou.
        await expect(page.getByTestId('invite-link-url')).toContainText('/accept-invite?token=');
        // Convidado ainda sem nome: a linha mostra o e-mail no lugar do nome e na coluna de e-mail.
        await expect(page.getByRole('main').getByText('new.user@acme.com', { exact: true }).first()).toBeAttached();
    });

    test('loads global config and persists toggle changes', async ({ page }) => {
        await installAdminApi(page);
        await setSession(page, 'superadmin');

        await page.goto('/admin/global');

        const debugToggle = page.getByLabel('Debug mode — full trace per node');
        await expect(debugToggle).not.toBeChecked();
        await debugToggle.click();
        await expect(debugToggle).toBeChecked();
        await expect(page.getByText('GPT-4o', { exact: true })).toBeVisible();
        await expect(page.getByText('enterprise')).toBeAttached();
    });
});
