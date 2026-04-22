import { expect, test, type Page } from '@playwright/test';
import { adminUser, authHandlers, fulfillJson, installApiRouter, installSession } from './support/api';
import { installSilentEventSource } from './support/browser';

const conversation = {
    id: 'conv-actions-1',
    workflowId: 'wf-support',
    tenantId: 'acme',
    userId: 'user-42',
    channel: 'API',
    status: 'ACTIVE',
    persona: 'order_tracking',
    createdAt: '2026-04-10T09:00:00.000Z',
    updatedAt: '2026-04-10T09:05:00.000Z',
    messageCount: 1,
};

async function authenticate(page: Page) {
    await installSession(page);
}

test.describe('Conversation actions', () => {
    test('sends a message from the chat panel', async ({ page }) => {
        let sentContent: string | null = null;

        await installApiRouter(page, [
            ...authHandlers(adminUser),
            async ({ path, method, route }) => {
                if (path !== '/conversations/conv-actions-1' || method !== 'GET') return false;
                await fulfillJson(route, conversation);
                return true;
            },
            async ({ path, method, route }) => {
                if (path !== '/conversations/conv-actions-1/messages' || method !== 'GET') return false;
                await fulfillJson(route, []);
                return true;
            },
            async ({ path, method, request, route }) => {
                if (path !== '/conversations/conv-actions-1/messages' || method !== 'POST') return false;
                const body = request.postDataJSON() as { content: string };
                sentContent = body.content;
                await fulfillJson(route, { messageId: 'm-created' });
                return true;
            },
            async ({ path, method, route }) => {
                if (path !== '/stream/acme/conv-actions-1' || method !== 'GET') return false;
                await route.fulfill({
                    status: 200,
                    contentType: 'text/event-stream',
                    body: '',
                });
                return true;
            },
        ]);

        await authenticate(page);
        await installSilentEventSource(page);

        await page.goto('/conversations/conv-actions-1');

        await page.getByPlaceholder('Type a message...').fill('preciso do status do pedido');
        await page.getByRole('button', { name: 'Send' }).click();

        await expect(page.getByText('preciso do status do pedido')).toBeVisible();
        await expect.poll(() => sentContent).toBe('preciso do status do pedido');
    });

    test('submits a suspended form and resumes the conversation', async ({ page }) => {
        let resumePayload: Record<string, unknown> | null = null;

        await installApiRouter(page, [
            ...authHandlers(adminUser),
            async ({ path, method, route }) => {
                if (path === '/conversations/conv-actions-1/messages' && method === 'GET') {
                    await fulfillJson(route, []);
                    return true;
                }
                if (path !== '/conversations/conv-actions-1' || method !== 'GET') return false;
                await fulfillJson(route, {
                    ...conversation,
                    status: 'SUSPENDED',
                    resumeToken: 'resume-token-1',
                    formData: {
                        id: 'form-1',
                        title: 'Customer confirmation',
                        description: 'Confirm the order details',
                        submitLabel: 'Send confirmation',
                        cancelLabel: 'Abort',
                        fields: [
                            { id: 'order', label: 'Order', type: 'TEXT', required: true },
                            { id: 'accept', label: 'Accept terms', type: 'CHECKBOX' },
                        ],
                    },
                });
                return true;
            },
            async ({ path, method, request, route }) => {
                if (path !== '/conversations/resume' || method !== 'POST') return false;
                const body = request.postDataJSON() as {
                    resumeToken: string;
                    formData: Record<string, unknown>;
                };
                resumePayload = body.formData;
                await fulfillJson(route, {
                    conversationId: 'conv-actions-1',
                    status: 'ACTIVE',
                    messages: [
                        {
                            id: 'm-result',
                            role: 'assistant',
                            content: 'Pedido 12345 confirmado.',
                            timestamp: '2026-04-10T09:07:00.000Z',
                        },
                    ],
                });
                return true;
            },
            async ({ path, method, route }) => {
                if (path !== '/stream/acme/conv-actions-1' || method !== 'GET') return false;
                await route.fulfill({
                    status: 200,
                    contentType: 'text/event-stream',
                    body: '',
                });
                return true;
            },
        ]);

        await authenticate(page);
        await installSilentEventSource(page);

        await page.goto('/conversations/conv-actions-1');

        await expect(page.getByRole('main').getByText('Customer confirmation', { exact: true })).toBeVisible();
        await page.getByLabel('Order').fill('12345');
        await page.getByLabel('Accept terms').check();
        await page.getByRole('button', { name: 'Send confirmation' }).click();

        await expect.poll(() => resumePayload).toEqual({ order: '12345', accept: true });
        await expect(page.getByText('Pedido 12345 confirmado.')).toBeVisible();
    });

    test('cancels a suspended conversation', async ({ page }) => {
        let cancelled = false;

        await installApiRouter(page, [
            ...authHandlers(adminUser),
            async ({ path, method, route }) => {
                if (path === '/conversations/conv-actions-1/messages' && method === 'GET') {
                    await fulfillJson(route, []);
                    return true;
                }
                if (path !== '/conversations/conv-actions-1' || method !== 'GET') return false;
                await fulfillJson(route, {
                    ...conversation,
                    status: 'SUSPENDED',
                    resumeToken: 'resume-token-1',
                    formData: {
                        id: 'form-1',
                        title: 'Need customer input',
                        fields: [{ id: 'note', label: 'Note', type: 'TEXT', required: true }],
                    },
                });
                return true;
            },
            async ({ path, method, route }) => {
                if (path === '/conversations/conv-actions-1' && method === 'DELETE') {
                    cancelled = true;
                    await route.fulfill({ status: 204 });
                    return true;
                }
                if (path !== '/stream/acme/conv-actions-1' || method !== 'GET') return false;
                await route.fulfill({
                    status: 200,
                    contentType: 'text/event-stream',
                    body: '',
                });
                return true;
            },
        ]);

        await authenticate(page);
        await installSilentEventSource(page);

        await page.goto('/conversations/conv-actions-1');

        await page.getByRole('button', { name: 'Cancel' }).click();

        await expect.poll(() => cancelled).toBeTruthy();
        await expect(page.getByText('Conversation cancelled.')).toBeVisible();
    });
});
