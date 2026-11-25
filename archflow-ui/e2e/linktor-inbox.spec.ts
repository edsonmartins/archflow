import { expect, test, type Page } from '@playwright/test';
import { adminUser, authHandlers, fulfillJson, installApiRouter, installSession } from './support/api';

// Formas reais do Linktor (snake_case, tags JSON de internal/domain/entity), já sem o envelope
// {success, data} que o backend tira (RCHFLW-112).
const CONVERSATIONS = [
    {
        id: 'conv-1',
        status: 'open',
        unread_count: 1,
        updated_at: '2026-04-23T09:00:00Z',
        contact: { id: 'c-1', name: 'Maria Souza' },
        channel: { id: 'ch-1', type: 'whatsapp', name: 'WhatsApp Loja' },
        last_message: { id: 'm1', content: 'Hi, I need help with my order', content_type: 'text',
                        sender_type: 'contact', created_at: '2026-04-23T09:00:00Z' },
    },
    {
        id: 'conv-2',
        status: 'resolved',
        unread_count: 0,
        updated_at: '2026-04-22T18:00:00Z',
        contact: { id: 'c-2', name: 'João Lima' },
        channel: { id: 'ch-2', type: 'telegram', name: 'Telegram' },
        last_message: { id: 'm9', content: 'Thanks, problem solved!', content_type: 'text',
                        sender_type: 'contact', created_at: '2026-04-22T18:00:00Z' },
    },
];

// O Linktor devolve a mais nova primeiro.
const MESSAGES = [
    { id: 'm2', sender_type: 'user',    content: 'Hello! How can I assist?', created_at: '2026-04-23T09:00:00Z' },
    { id: 'm1', sender_type: 'contact', content: 'Hi, I need help',          created_at: '2026-04-23T08:59:00Z' },
];

const USERS = [
    { id: 'user-123', name: 'Ana Operadora', email: 'ana@example.com', role: 'agent', status: 'active' },
];

async function mockApi(page: Page) {
    await installApiRouter(page, [
        ...authHandlers(adminUser, { loginShape: 'token', workflows: [] }),
        async ({ path, method, route }) => {
            if (!path.startsWith('/admin/linktor/inbox/conversations')) return false;
            if (method === 'GET' && path.endsWith('/messages')) {
                await fulfillJson(route, MESSAGES);
                return true;
            }
            if (method === 'POST' && path.endsWith('/messages')) {
                await fulfillJson(route, { id: 'm3', sender_type: 'user', content: 'sent' });
                return true;
            }
            if (method === 'POST' && path.endsWith('/assign')) {
                await fulfillJson(route, { ...CONVERSATIONS[0], assigned_user_id: 'user-123' });
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
            if (path !== '/admin/linktor/inbox/users' || method !== 'GET') return false;
            await fulfillJson(route, USERS);
            return true;
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

        await page.goto('/admin/linktor/inbox', { waitUntil: 'commit' });
        await expect(page.getByRole('heading', { name: 'Linktor Inbox' })).toBeVisible({ timeout: 30_000 });
        await expect(page.getByTestId('lk-conv-conv-1')).toBeAttached({ timeout: 30_000 });
        await expect(page.getByTestId('lk-conv-conv-1')).toContainText('Hi, I need help with my order');
        await expect(page.getByTestId('lk-conv-conv-1')).toContainText('Maria Souza');

        await page.getByTestId('lk-conv-conv-1').dispatchEvent('click');
        await expect(page.getByTestId('linktor-conversation')).toBeVisible();
        await expect(page.getByText('Hello! How can I assist?')).toBeVisible();
    });

    test('sends a reply from the detail page', async ({ page }) => {
        await installSession(page, { role: 'tenant_admin' });
        await mockApi(page);

        await page.goto('/admin/linktor/inbox/conv-1', { waitUntil: 'commit' });
        await page.getByTestId('lk-reply-input').fill('Got it — shipping now');
        await page.getByTestId('lk-reply-send').click();
        // After send the input is cleared and the page reloads messages.
        await expect(page.getByTestId('lk-reply-input')).toHaveValue('');
    });

    test('assigns a conversation to an operator picked from the Linktor users', async ({ page }) => {
        await installSession(page, { role: 'tenant_admin' });
        await mockApi(page);

        await page.goto('/admin/linktor/inbox/conv-1', { waitUntil: 'commit' });
        await page.getByTestId('lk-assignee').selectOption('user-123');
        await page.getByTestId('lk-assign').click();
        await expect(page.getByText(/handed off/)).toBeVisible();
    });
});
