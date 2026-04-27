import { expect, test, type Page } from '@playwright/test';
import { adminUser, authHandlers, fulfillJson, installApiRouter, installSession } from './support/api';

const CONVERSATIONS = [
    {
        id: 'conv-1',
        status: 'open',
        channelType: 'whatsapp',
        lastMessagePreview: 'Hi, I need help with my order',
        updatedAt: '2026-04-23T09:00:00Z',
    },
    {
        id: 'conv-2',
        status: 'resolved',
        channelType: 'telegram',
        lastMessagePreview: 'Thanks, problem solved!',
        updatedAt: '2026-04-22T18:00:00Z',
    },
];

const MESSAGES = [
    { id: 'm1', direction: 'inbound',  content: 'Hi, I need help',      createdAt: '2026-04-23T08:59:00Z' },
    { id: 'm2', direction: 'outbound', content: 'Hello! How can I assist?', createdAt: '2026-04-23T09:00:00Z' },
];

async function mockApi(page: Page) {
    await installApiRouter(page, [
        ...authHandlers(adminUser, { loginShape: 'token' }),
        async ({ path, method, route }) => {
            if (!path.startsWith('/admin/linktor/inbox/conversations')) return false;
            if (method === 'GET' && path.endsWith('/messages')) {
                await fulfillJson(route, MESSAGES);
                return true;
            }
            if (method === 'POST' && path.endsWith('/messages')) {
                await fulfillJson(route, { id: 'm3', direction: 'outbound', content: 'sent' });
                return true;
            }
            if (method === 'POST' && path.endsWith('/assign')) {
                await fulfillJson(route, { ...CONVERSATIONS[0], status: 'assigned' });
                return true;
            }
            if (method === 'POST' && path.endsWith('/resolve')) {
                await fulfillJson(route, { ...CONVERSATIONS[0], status: 'resolved' });
                return true;
            }
            if (method === 'GET' && /\/conversations\/[^/]+$/.test(path)) {
                await fulfillJson(route, CONVERSATIONS[0]);
                return true;
            }
            if (method === 'GET' && path.endsWith('/conversations')) {
                await fulfillJson(route, CONVERSATIONS);
                return true;
            }
            return false;
        },
        async ({ path, method, route }) => {
            if (path !== '/admin/linktor/inbox/contacts' || method !== 'GET') return false;
            await fulfillJson(route, []);
            return true;
        },
        async ({ path, method, route }) => {
            if (path !== '/admin/linktor/inbox/channels' || method !== 'GET') return false;
            await fulfillJson(route, []);
            return true;
        },
    ]);
}

test.describe('Linktor inbox', () => {
    test('lists conversations and opens detail', async ({ page }) => {
        await installSession(page, { role: 'tenant_admin' });
        await mockApi(page);

        await page.goto('/admin/linktor/inbox');
        await expect(page.getByTestId('linktor-inbox')).toBeVisible();
        await expect(page.getByTestId('lk-conv-conv-1')).toBeVisible();
        await expect(page.getByText('Hi, I need help with my order')).toBeVisible();

        await page.getByTestId('lk-conv-conv-1').getByRole('button', { name: 'Open' }).click();
        await expect(page.getByTestId('linktor-conversation')).toBeVisible();
        await expect(page.getByText('Hello! How can I assist?')).toBeVisible();
    });

    test('sends a reply from the detail page', async ({ page }) => {
        await installSession(page, { role: 'tenant_admin' });
        await mockApi(page);

        await page.goto('/admin/linktor/inbox/conv-1');
        await page.getByTestId('lk-reply-input').fill('Got it — shipping now');
        await page.getByTestId('lk-reply-send').click();
        // After send the input is cleared and the page reloads messages.
        await expect(page.getByTestId('lk-reply-input')).toHaveValue('');
    });

    test('assigns a conversation to a user', async ({ page }) => {
        await installSession(page, { role: 'tenant_admin' });
        await mockApi(page);

        await page.goto('/admin/linktor/inbox/conv-1');
        await page.getByTestId('lk-assignee').fill('user-123');
        await page.getByTestId('lk-assign').click();
        await expect(page.getByText(/handed off/)).toBeVisible();
    });
});
