import { expect, test } from '@playwright/test';

const API_BASE = process.env.E2E_API_BASE ?? 'http://127.0.0.1:8080/api';

test.describe('Real backend smoke', () => {
  test('logs in, lists workflows, executes one and shows it in history', async ({ page }) => {
    await page.goto('/login');

    await page.getByLabel('Username').fill('admin');
    await page.getByLabel('Password').fill('admin123');
    await page.getByRole('button', { name: /sign in/i }).click();

    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole('main').getByText('Workflows', { exact: true })).toBeVisible();
    await expect(page.getByText('Customer Support Agent')).toBeVisible();
    await expect(page.getByText('Active')).toBeVisible();

    await page.locator('button[title="Execute"]').first().click();
    await expect(page).toHaveURL(/\/executions\?id=exec-/);
    await expect(page.getByText('Execution History', { exact: true })).toBeVisible();
    await expect(page.getByText('Running').first()).toBeVisible();
  });

  test('loads conversations without backend errors and exports usage csv', async ({ page, request }) => {
    const conversations = await request.get(`${API_BASE}/conversations`);
    expect(conversations.ok()).toBeTruthy();
    const conversationBody = await conversations.json();
    expect(conversationBody).toMatchObject({
      items: expect.any(Array),
      page: 0,
    });

    const exportCsv = await request.get(`${API_BASE}/admin/global/usage/export?month=2026-04`);
    expect(exportCsv.ok()).toBeTruthy();
    expect(exportCsv.headers()['content-type']).toContain('text/csv');
    const csv = await exportCsv.text();
    expect(csv).toContain('tenantId,tenantName,executions');
    expect(csv).toContain('tenant_rio_quality');

    await page.goto('/login');
    await page.getByLabel('Username').fill('admin');
    await page.getByLabel('Password').fill('admin123');
    await page.getByRole('button', { name: /sign in/i }).click();
    await expect(page).toHaveURL(/\/$/);

    await page.goto('/conversations');
    await expect(page).toHaveURL(/\/conversations$/);
    await expect(page.getByRole('heading', { name: 'Conversations' })).toBeVisible();
    await expect(page.getByText('No conversations found.')).toBeVisible();
    await expect(page.getByText('Internal Server Error')).toHaveCount(0);
  });
});
