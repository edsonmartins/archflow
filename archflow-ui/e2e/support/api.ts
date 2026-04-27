import type { Page, Route } from '@playwright/test';

export type MockUser = {
    id: string;
    username: string;
    name: string;
    email?: string;
    roles: string[];
};

export type ApiContext = {
    route: Route;
    request: ReturnType<Route['request']>;
    url: URL;
    path: string;
    method: string;
};

export type ApiHandler = (context: ApiContext) => Promise<boolean | void> | boolean | void;

export const adminUser: MockUser = {
    id: 'user-e2e',
    username: 'admin',
    name: 'Admin User',
    email: 'admin@archflow.dev',
    roles: ['admin'],
};

export const superadminUser: MockUser = {
    id: 'user-admin',
    username: 'admin',
    name: 'Admin User',
    email: 'admin@archflow.dev',
    roles: ['superadmin'],
};

export async function fulfillJson(route: Route, body: unknown, status = 200) {
    await route.fulfill({
        status,
        contentType: 'application/json',
        body: JSON.stringify(body),
    });
}

export async function installSession(
    page: Page,
    options: { role?: string; token?: string; refreshToken?: string } = {},
) {
    const {
        role,
        token = 'e2e-token',
        refreshToken = 'e2e-refresh-token',
    } = options;

    await page.addInitScript(
        ({ currentRole, currentToken, currentRefreshToken }) => {
            localStorage.setItem('archflow_token', currentToken);
            localStorage.setItem('archflow_refresh_token', currentRefreshToken);
            // Pin the UI language to English so text-based test selectors
            // remain stable regardless of the runner's browser locale or
            // any `archflow-language` value left over from a prior run.
            localStorage.setItem('archflow-language', 'en');
            if (currentRole) {
                sessionStorage.setItem('archflow_role', currentRole);
            }
        },
        { currentRole: role, currentToken: token, currentRefreshToken: refreshToken },
    );
}

/**
 * Force the UI language to English for tests that don't go through
 * `installSession` (e.g. login flow tests where the user is not yet
 * authenticated). Call before `page.goto()`.
 */
export async function pinLanguageEn(page: Page) {
    await page.addInitScript(() => {
        localStorage.setItem('archflow-language', 'en');
    });
}

export async function installApiRouter(
    page: Page,
    handlers: ApiHandler[],
    options: { fallback?: 'continue' | '404' } = {},
) {
    const fallback = options.fallback ?? 'continue';

    await page.route('**/api/**', async (route) => {
        const request = route.request();
        const url = new URL(request.url());
        const context: ApiContext = {
            route,
            request,
            url,
            path: url.pathname.replace(/^\/api/, ''),
            method: request.method(),
        };

        for (const handler of handlers) {
            const handled = await handler(context);
            if (handled) {
                return;
            }
        }

        if (fallback === '404') {
            await fulfillJson(route, { message: `Unhandled ${context.method} ${context.path}` }, 404);
            return;
        }

        await route.continue();
    });
}

export function authHandlers(
    user: MockUser,
    options: {
        loginSucceeds?: boolean;
        loginShape?: 'accessToken' | 'token';
        approvalsCount?: number;
        pendingApprovals?: unknown[];
        workflows?: unknown[];
        includeLogout?: boolean;
        includeApprovals?: boolean;
        includeWorkflowList?: boolean;
    } = {},
): ApiHandler[] {
    const {
        loginSucceeds = true,
        loginShape = 'accessToken',
        approvalsCount = 0,
        pendingApprovals = [],
        workflows,
        includeLogout = true,
        includeApprovals = true,
        includeWorkflowList = true,
    } = options;

    return [
        async ({ path, method, route }) => {
            if (path !== '/auth/login' || method !== 'POST') return false;
            if (!loginSucceeds) {
                await fulfillJson(route, 'Invalid credentials', 400);
                return true;
            }

            const body =
                loginShape === 'token'
                    ? { token: 'e2e-token', refreshToken: 'e2e-refresh-token' }
                    : {
                        accessToken: 'e2e-token',
                        refreshToken: 'e2e-refresh-token',
                        userId: user.id,
                        username: user.username,
                        email: user.email ?? '',
                        roles: user.roles,
                    };
            await fulfillJson(route, body);
            return true;
        },
        async ({ path, method, route }) => {
            if (path !== '/auth/me' || method !== 'GET') return false;
            await fulfillJson(route, user);
            return true;
        },
        async ({ path, method, route }) => {
            if (!includeLogout || path !== '/auth/logout' || method !== 'POST') return false;
            await fulfillJson(route, {});
            return true;
        },
        async ({ path, method, route }) => {
            if (!includeApprovals || path !== '/approvals/pending/count' || method !== 'GET') return false;
            await fulfillJson(route, { count: approvalsCount });
            return true;
        },
        async ({ path, method, route }) => {
            if (!includeApprovals || path !== '/approvals/pending' || method !== 'GET') return false;
            await fulfillJson(route, pendingApprovals);
            return true;
        },
        async ({ path, method, route }) => {
            if (!includeWorkflowList || workflows === undefined || path !== '/workflows' || method !== 'GET') return false;
            await fulfillJson(route, workflows);
            return true;
        },
    ];
}
