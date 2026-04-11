import { expect, test, type Page, type Route } from '@playwright/test';

const user = {
    id: 'user-e2e',
    username: 'admin',
    name: 'Admin User',
    roles: ['admin'],
};

const conversation = {
    id: 'conv-streaming-1',
    workflowId: 'wf-customer',
    tenantId: 'acme',
    userId: 'user-42',
    channel: 'API',
    status: 'ACTIVE',
    persona: 'order_tracking',
    createdAt: '2026-04-10T09:00:00.000Z',
    updatedAt: '2026-04-10T09:05:00.000Z',
    messageCount: 1,
};

const initialMessages = [
    {
        id: 'm-1',
        role: 'user',
        content: 'quero rastrear pedido 12345',
        timestamp: '2026-04-10T09:01:00.000Z',
    },
];

/**
 * Mocks the REST + SSE endpoints used by the streaming conversation page.
 *
 * The SSE endpoint is implemented by intercepting the request and returning
 * a multipart-style text/event-stream body. We script a sequence of events
 * that mirrors a real SAC tracking turn:
 *
 *   1. chat.start
 *   2. tool_start (tracking_pedido)
 *   3. tool result
 *   4. chat.delta × 4 (tokens)
 *   5. chat.end
 */
async function mockApi(page: Page) {
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

        if (path === '/conversations' && method === 'GET') {
            await json(route, {
                items: [conversation],
                page: 1,
                pageSize: 100,
                total: 1,
            });
            return;
        }

        if (path === `/conversations/${conversation.id}` && method === 'GET') {
            await json(route, conversation);
            return;
        }

        if (path === `/conversations/${conversation.id}/messages` && method === 'GET') {
            await json(route, initialMessages);
            return;
        }

        if (path === `/conversations/${conversation.id}/messages` && method === 'POST') {
            await json(route, { messageId: 'm-2' });
            return;
        }

        if (
            path === `/stream/${conversation.tenantId}/${conversation.id}` &&
            method === 'GET'
        ) {
            const body = buildSseBody();
            await route.fulfill({
                status: 200,
                contentType: 'text/event-stream',
                headers: {
                    'cache-control': 'no-cache',
                    connection: 'keep-alive',
                },
                body,
            });
            return;
        }

        await route.continue();
    });
}

function buildSseBody(): string {
    // Stable ids so reconnects + StrictMode double-mount dedupe correctly.
    const events = [
        chatEvent('start', 'evt-start', { model: 'gpt-4o' }),
        toolEvent('tool_start', 'evt-tool-start', {
            toolName: 'tracking_pedido',
            toolCallId: 'tc-1',
            input: { numero_pedido: '12345' },
        }),
        toolEvent('result', 'evt-tool-result', {
            toolName: 'tracking_pedido',
            toolCallId: 'tc-1',
            result: {
                numero_pedido: '12345',
                status: 'EM_TRANSITO',
                previsao_entrega: '2026-04-15',
                transportadora: 'Express Cargo',
            },
            durationMs: 245,
        }),
        chatEvent('delta', 'evt-delta-1', { content: 'Seu pedido ' }),
        chatEvent('delta', 'evt-delta-2', { content: '12345 está ' }),
        chatEvent('delta', 'evt-delta-3', { content: 'em trânsito ' }),
        chatEvent('delta', 'evt-delta-4', { content: 'previsão 2026-04-15.' }),
        chatEvent('end', 'evt-end', { finishReason: 'stop' }),
    ];
    return events.map((envelope) => `data: ${JSON.stringify(envelope)}\n\n`).join('');
}

function chatEvent(type: string, id: string, data: Record<string, unknown>) {
    return {
        envelope: {
            domain: 'chat',
            type,
            id,
            timestamp: '2026-04-10T09:05:00.000Z',
            executionId: 'exec-1',
            tenantId: conversation.tenantId,
        },
        data,
        metadata: {},
    };
}

function toolEvent(type: string, id: string, data: Record<string, unknown>) {
    return {
        envelope: {
            domain: 'tool',
            type,
            id,
            timestamp: '2026-04-10T09:05:00.000Z',
            executionId: 'exec-1',
            tenantId: conversation.tenantId,
        },
        data,
        metadata: {},
    };
}

async function json(route: Route, body: unknown, status = 200) {
    await route.fulfill({
        status,
        contentType: 'application/json',
        body: JSON.stringify(body),
    });
}

async function authenticate(page: Page) {
    await page.addInitScript(() => {
        localStorage.setItem('archflow_token', 'e2e-token');
        localStorage.setItem('archflow_refresh_token', 'e2e-refresh-token');
    });
}

test.describe('Conversations streaming surface', () => {
    test('lists conversations and opens the detail page', async ({ page }) => {
        await mockApi(page);
        await authenticate(page);

        await page.goto('/conversations');

        await expect(page.getByRole('heading', { name: 'Conversations' })).toBeVisible();
        await expect(page.getByText(conversation.id.slice(0, 8))).toBeVisible();
        await expect(page.getByText('order_tracking', { exact: false })).toBeVisible();

        // Click the first row -> opens detail
        await page.getByText(conversation.id.slice(0, 8)).click();
        await expect(page).toHaveURL(/\/conversations\/conv-streaming-1$/);
    });

    test('renders streamed deltas + tool call block on the detail page', async ({ page }) => {
        await mockApi(page);
        await authenticate(page);

        await page.goto(`/conversations/${conversation.id}`);

        // Initial REST history is rendered
        await expect(page.getByText('quero rastrear pedido 12345')).toBeVisible();

        // After SSE completes the assistant message should be assembled
        // from 4 chunks into a single block
        await expect(
            page.getByText('Seu pedido 12345 está em trânsito previsão 2026-04-15.'),
        ).toBeVisible({ timeout: 5000 });

        // Tool call block is present with tool name visible
        await expect(page.getByText('tracking_pedido')).toBeVisible();
        // Status badge for completed tool
        await expect(page.getByText('success', { exact: false }).first()).toBeVisible();
    });
});
