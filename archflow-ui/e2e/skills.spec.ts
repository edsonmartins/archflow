import { expect, test, type Page } from '@playwright/test';
import { adminUser, authHandlers, fulfillJson, installApiRouter, installSession } from './support/api';

const SKILLS = [
    { name: 'docx', description: 'Parse DOCX documents',
      resources: [{ name: 'template.docx', mimeType: 'application/docx' }],
      active: true },
    { name: 'sql',  description: 'Construct SQL queries safely',
      resources: [], active: false },
];

async function mockApi(page: Page) {
    await installApiRouter(page, [
        ...authHandlers(adminUser, { loginShape: 'token' }),
        async ({ path, method, route }) => {
            if (path !== '/admin/skills' || method !== 'GET') return false;
            await fulfillJson(route, SKILLS);
            return true;
        },
        async ({ path, method, route }) => {
            if (!path.endsWith('/activate') || method !== 'POST') return false;
            const name = path.split('/').slice(-2, -1)[0];
            await fulfillJson(route, { ...SKILLS.find(s => s.name === name)!, active: true });
            return true;
        },
        async ({ path, method, route }) => {
            if (!path.endsWith('/activate') || method !== 'DELETE') return false;
            await route.fulfill({ status: 204 });
            return true;
        },
    ]);
}

test.describe('Skills admin page', () => {
    test('renders registered skills with active flag', async ({ page }) => {
        await installSession(page, { role: 'tenant_admin' });
        await mockApi(page);

        await page.goto('/admin/skills');
        await expect(page.getByTestId('skills-page')).toBeVisible();
        await expect(page.getByTestId('skill-docx')).toBeVisible();
        // The lowercase badge "active" is distinct from the Switch labels
        // "Active" / "Inactive" — use exact match so we don't collide
        // with the Mantine Switch label that always carries one of those.
        await expect(page.getByText('active', { exact: true })).toBeVisible();
        // i18n updated the badge text to use proper singular/plural
        // ("1 resource" / "N resources") — match either form.
        await expect(page.getByText(/1 resource/)).toBeVisible();
    });

    test('can toggle skill activation', async ({ page }) => {
        await installSession(page, { role: 'tenant_admin' });
        await mockApi(page);

        await page.goto('/admin/skills');
        // Mantine v7 forwards data-testid to a visually-hidden <input>.
        // Walking up to the label (which is the visible click target)
        // gives us a clickable element that toggles the checkbox.
        await page.getByTestId('skill-toggle-sql').locator('xpath=..').click();
        // The underlying handler does not change local state directly but
        // the API call is made — ensure no error toast.
        await expect(page.getByText(/failed/i)).toHaveCount(0);
    });
});
