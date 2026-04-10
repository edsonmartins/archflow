import { test } from '@playwright/test';

async function setupAdminMocks(page: any, role: 'superadmin' | 'tenant_admin' = 'superadmin') {
  await page.addInitScript((r: string) => {
    localStorage.setItem('archflow_token', 'mock-jwt-token');
    sessionStorage.setItem('archflow_role', r);
  }, role);
  await page.route('**/api/auth/me', (route: any) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ id: '1', username: 'superadmin', name: 'Edson Martins', roles: [role] })
  }));
  await page.route('**/api/workflows', (route: any) => route.fulfill({
    status: 200, contentType: 'application/json', body: '[]'
  }));
}

test.describe('Admin Visual Audit', () => {
  test('01 — tenant list', async ({ page }) => {
    await setupAdminMocks(page, 'superadmin');
    await page.goto('/admin/tenants');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(500);
    await page.screenshot({ path: 'screenshots/admin-01-tenant-list.png', fullPage: true });
  });

  test('02 — create tenant', async ({ page }) => {
    await setupAdminMocks(page, 'superadmin');
    await page.goto('/admin/tenants/new');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(500);
    await page.screenshot({ path: 'screenshots/admin-02-tenant-new.png', fullPage: true });
  });

  test('03 — global config', async ({ page }) => {
    await setupAdminMocks(page, 'superadmin');
    await page.goto('/admin/global');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(500);
    await page.screenshot({ path: 'screenshots/admin-03-global-config.png', fullPage: true });
  });

  test('04 — billing', async ({ page }) => {
    await setupAdminMocks(page, 'superadmin');
    await page.goto('/admin/billing');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(500);
    await page.screenshot({ path: 'screenshots/admin-04-usage-billing.png', fullPage: true });
  });

  test('05 — workspace', async ({ page }) => {
    await setupAdminMocks(page, 'tenant_admin');
    await page.goto('/admin/workspace');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(500);
    await page.screenshot({ path: 'screenshots/admin-05-workspace.png', fullPage: true });
  });

  test('06 — users', async ({ page }) => {
    await setupAdminMocks(page, 'tenant_admin');
    await page.goto('/admin/workspace/users');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(500);
    await page.screenshot({ path: 'screenshots/admin-06-users.png', fullPage: true });
  });

  test('07 — api keys', async ({ page }) => {
    await setupAdminMocks(page, 'tenant_admin');
    await page.goto('/admin/workspace/keys');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(500);
    await page.screenshot({ path: 'screenshots/admin-07-api-keys.png', fullPage: true });
  });
});
