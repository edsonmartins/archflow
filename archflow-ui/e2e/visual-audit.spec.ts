import { test } from '@playwright/test';

// Mock API responses for all protected routes
async function setupMocks(page: any) {
  await page.route('**/api/auth/me', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ id: '1', username: 'admin', name: 'Admin User', roles: ['admin'] })
  }));
  await page.route('**/api/workflows', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify([
      { id: 'wf-1', name: 'Customer Support Flow', description: 'AI-powered customer support automation', version: '1.2.0', status: 'active', updatedAt: '2026-04-01T10:00:00Z', stepCount: 5 },
      { id: 'wf-2', name: 'Invoice Processing', description: 'Automated invoice extraction and approval', version: '2.0.0', status: 'draft', updatedAt: '2026-04-05T14:30:00Z', stepCount: 8 },
      { id: 'wf-3', name: 'Sales Copilot - Briefing', description: 'Weekly sales briefing for vendors', version: '1.0.0', status: 'active', updatedAt: '2026-04-08T09:00:00Z', stepCount: 3 },
    ])
  }));
  await page.route('**/api/workflows/*', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      id: 'wf-1', metadata: { name: 'Customer Support Flow', description: 'AI support', version: '1.2.0', category: 'support', tags: ['ai', 'chat'] },
      steps: [
        { id: 'step-1', type: 'AGENT', componentId: 'triage-agent', operation: 'executeTask', connections: [{ sourceId: 'step-1', targetId: 'step-2' }], configuration: { model: 'gpt-4', systemPrompt: 'You are a support triage agent' } },
        { id: 'step-2', type: 'ASSISTANT', componentId: 'response-assistant', operation: 'chat', connections: [{ sourceId: 'step-2', targetId: 'step-3' }], configuration: { model: 'claude-3-sonnet' } },
        { id: 'step-3', type: 'TOOL', componentId: 'ticket-tool', operation: 'createTicket', connections: [], configuration: { toolId: 'zendesk-create' } },
      ],
      configuration: { timeout: 30000, llmConfig: { defaultModel: 'gpt-4' } }
    })
  }));
  await page.route('**/api/executions**', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify([
      { id: 'exec-1', workflowId: 'wf-1', workflowName: 'Customer Support Flow', status: 'COMPLETED', startedAt: '2026-04-09T10:00:00Z', completedAt: '2026-04-09T10:00:45Z', duration: 45000, error: null },
      { id: 'exec-2', workflowId: 'wf-2', workflowName: 'Invoice Processing', status: 'FAILED', startedAt: '2026-04-09T09:30:00Z', completedAt: '2026-04-09T09:31:00Z', duration: 60000, error: 'Timeout connecting to LLM provider' },
      { id: 'exec-3', workflowId: 'wf-1', workflowName: 'Customer Support Flow', status: 'RUNNING', startedAt: '2026-04-09T12:00:00Z', completedAt: null, duration: null, error: null },
    ])
  }));
  // Set auth token
  await page.addInitScript(() => {
    localStorage.setItem('archflow_token', 'mock-jwt-token');
  });
}

test.describe('Visual Audit — ArchFlow UI', () => {

  test('01 — login page', async ({ page }) => {
    await page.goto('/login');
    await page.waitForLoadState('networkidle');
    await page.screenshot({ path: 'screenshots/01-login.png', fullPage: true });
  });

  test('02 — workflow list (dashboard)', async ({ page }) => {
    await setupMocks(page);
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(500);
    await page.screenshot({ path: 'screenshots/02-workflow-list.png', fullPage: true });
  });

  test('03 — workflow editor', async ({ page }) => {
    await setupMocks(page);
    await page.goto('/editor/wf-1');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);
    await page.screenshot({ path: 'screenshots/03-workflow-editor.png', fullPage: true });
  });

  test('04 — execution history', async ({ page }) => {
    await setupMocks(page);
    await page.goto('/executions');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(500);
    await page.screenshot({ path: 'screenshots/04-execution-history.png', fullPage: true });
  });

  test('05 — new workflow editor (empty)', async ({ page }) => {
    await setupMocks(page);
    await page.goto('/editor');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1500);
    await page.screenshot({ path: 'screenshots/05-editor-empty.png', fullPage: true });
  });

  test('06 — mobile view (375px)', async ({ page }) => {
    await setupMocks(page);
    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto('/');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(500);
    await page.screenshot({ path: 'screenshots/06-mobile-375px.png', fullPage: true });
  });

});
