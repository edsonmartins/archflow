import { expect, test } from '@playwright/test';

const API_BASE = process.env.E2E_API_BASE ?? 'http://127.0.0.1:8080/api';

async function login(page: import('@playwright/test').Page, options?: { role?: string }) {
  if (options?.role) {
    await page.addInitScript((role) => {
      window.sessionStorage.setItem('archflow_role', role);
    }, options.role);
  }

  await page.goto('/login');
  await page.getByLabel('Username').fill('admin');
  await page.getByLabel('Password').fill('admin123');
  await page.getByRole('button', { name: /sign in/i }).click();
  await expect(page).toHaveURL(/\/$/);
}

async function impersonateWorkspace(page: import('@playwright/test').Page) {
  await page.goto('/admin/tenants');
  await expect(page.getByRole('heading', { name: 'Tenants' })).toBeVisible();
  await page.getByRole('button', { name: 'Enter' }).first().click();
  await expect(page).toHaveURL(/\/admin\/workspace$/);
  await expect(page.getByTestId('impersonation-banner')).toBeVisible();
}

async function installRealtimeBrowserFakes(page: import('@playwright/test').Page) {
  await page.addInitScript(() => {
    const fakeStream = {
      getTracks: () => [
        {
          stop() {},
          kind: 'audio',
          enabled: true,
        },
      ],
    } as unknown as MediaStream;

    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: {
        getUserMedia: () => Promise.resolve(fakeStream),
      },
    });

    class FakeAudioContext {
      sampleRate = 24_000;
      destination = {} as AudioDestinationNode;
      audioWorklet = undefined;

      createMediaStreamSource() {
        return { connect: () => undefined };
      }

      createScriptProcessor() {
        let callback: ((e: { inputBuffer: { getChannelData: () => Float32Array } }) => void) | null = null;
        return {
          connect: () => {
            setTimeout(() => {
              callback?.({
                inputBuffer: {
                  getChannelData: () => new Float32Array([0.1, -0.1, 0.2, -0.2]),
                },
              });
            }, 60);
          },
          disconnect: () => undefined,
          set onaudioprocess(fn: ((e: { inputBuffer: { getChannelData: () => Float32Array } }) => void) | null) {
            callback = fn;
          },
          get onaudioprocess() {
            return callback;
          },
        };
      }

      createBuffer() {
        return { copyToChannel: () => undefined } as unknown as AudioBuffer;
      }

      createBufferSource() {
        return {
          buffer: null as AudioBuffer | null,
          connect: () => undefined,
          onended: null as null | (() => void),
          start() {
            if (typeof this.onended === 'function') {
              setTimeout(() => this.onended && this.onended(), 0);
            }
          },
        };
      }

      close() {
        return Promise.resolve();
      }
    }

    (window as unknown as { AudioContext: typeof FakeAudioContext }).AudioContext = FakeAudioContext;
  });
}

test.describe('Real backend smoke', () => {
  test('enforces auth and role gating on admin routes', async ({ page }) => {
    await page.goto('/admin/tenants');
    await expect(page).toHaveURL(/\/login$/);

    await login(page);
    await page.goto('/admin');
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole('main').getByText('Workflows', { exact: true })).toBeVisible();

    await page.goto('/admin/tenants');
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole('main').getByText('Workflows', { exact: true })).toBeVisible();

    const tenantAdminPage = await page.context().newPage();
    await tenantAdminPage.addInitScript(() => {
      window.sessionStorage.setItem('archflow_role', 'tenant_admin');
    });

    await tenantAdminPage.goto('/admin/workspace');
    await expect(tenantAdminPage).toHaveURL(/\/admin\/workspace$/);
    await expect(tenantAdminPage.getByText('Workspace Overview')).toBeVisible();

    await tenantAdminPage.goto('/admin/global');
    await expect(tenantAdminPage).toHaveURL(/\/$/);
    await expect(tenantAdminPage.getByRole('main').getByText('Workflows', { exact: true })).toBeVisible();
    await tenantAdminPage.close();
  });

  test('logs in, lists workflows, executes one and shows it in history', async ({ page }) => {
    await login(page);
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

    await login(page);
    await page.goto('/conversations');
    await expect(page).toHaveURL(/\/conversations$/);
    await expect(page.getByRole('heading', { name: 'Conversations' })).toBeVisible();
    await expect(page.getByText('No conversations found.')).toBeVisible();
    await expect(page.getByText('Internal Server Error')).toHaveCount(0);
  });

  test('loads admin tenant views, toggles tenant status and impersonates workspace', async ({ page, request }) => {
    await login(page, { role: 'superadmin' });

    await page.goto('/admin/tenants');
    await expect(page.getByRole('heading', { name: 'Tenants' })).toBeVisible();
    await expect(page.getByText('Rio Quality')).toBeVisible();
    await expect(page.getByText('tenant_rio_quality')).toBeVisible();
    await expect(page.getByText('Active tenants')).toBeVisible();

    await page.goto('/admin/tenants/tenant_rio_quality');
    await expect(page.getByRole('heading', { name: 'Tenant Detail' })).toBeVisible();
    await expect(page.getByText('admin@rioquality.com.br')).toBeVisible();

    await page.getByRole('button', { name: 'Suspend' }).click();
    await expect.poll(async () => {
      const response = await request.get(`${API_BASE}/admin/tenants/tenant_rio_quality`);
      const body = await response.json();
      return body.status;
    }).toBe('suspended');

    const activateResponse = await request.post(`${API_BASE}/admin/tenants/tenant_rio_quality/activate`);
    expect(activateResponse.ok()).toBeTruthy();
    await expect.poll(async () => {
      const response = await request.get(`${API_BASE}/admin/tenants/tenant_rio_quality`);
      const body = await response.json();
      return body.status;
    }).toBe('active');

    await impersonateWorkspace(page);
    await expect(page.getByText('Workspace Overview')).toBeVisible();
    await expect(page.getByText('Executions today')).toBeVisible();
    await expect(page.locator('div').filter({ hasText: /^340$/ }).first()).toBeVisible();
  });

  test('manages workspace users and api keys through the real admin UI', async ({ page, request }) => {
    await login(page, { role: 'superadmin' });
    await impersonateWorkspace(page);

    await page.goto('/admin/workspace/users');
    await expect(page.getByRole('heading', { name: 'Users' })).toBeVisible();
    await expect(page.getByText('João Silva')).toBeVisible();
    await expect(page.getByText('Maria Santos')).toBeVisible();

    await page.getByRole('button', { name: 'Invite User' }).click();
    const inviteModal = page.getByRole('dialog', { name: 'Invite User' });
    await inviteModal.getByLabel('Email').fill('qa.real@rioquality.com.br');
    await inviteModal.getByRole('button', { name: 'Send Invite' }).evaluate((element: HTMLButtonElement) => element.click());
    const invitedRow = page.locator('tr').filter({ hasText: 'qa.real@rioquality.com.br' });
    await expect(invitedRow).toBeVisible();
    await expect(invitedRow.getByText('Invite pending')).toBeVisible();

    await expect.poll(async () => {
      const response = await request.get(`${API_BASE}/admin/workspace/users`);
      const users = await response.json();
      return users.find((user: { email: string; role: string }) => user.email === 'qa.real@rioquality.com.br')?.role;
    }).toBe('editor');

    await invitedRow.locator('button').nth(1).click();
    await expect.poll(async () => {
      const response = await request.get(`${API_BASE}/admin/workspace/users`);
      const users = await response.json();
      return users.some((user: { email: string }) => user.email === 'qa.real@rioquality.com.br');
    }).toBe(false);

    await page.goto('/admin/workspace/keys');
    await expect(page.getByRole('heading', { name: 'API Keys' })).toBeVisible();
    await expect(page.getByText('VendaX Backend')).toBeVisible();

    await page.getByRole('button', { name: 'Create Key' }).click();
    const keyModal = page.getByRole('dialog', { name: 'Create API Key' });
    await keyModal.getByLabel('Key name').fill('Playwright Real Key');
    await keyModal.getByRole('button', { name: 'Create' }).evaluate((element: HTMLButtonElement) => element.click());
    await expect(keyModal.getByText('Your new API key:')).toBeVisible();
    await expect(keyModal.getByText('af_live_', { exact: false })).toBeVisible();
    await keyModal.getByRole('button', { name: 'Done' }).evaluate((element: HTMLButtonElement) => element.click());

    await expect.poll(async () => {
      const response = await request.get(`${API_BASE}/admin/workspace/keys`);
      const keys = await response.json();
      return keys.some((key: { name: string }) => key.name === 'Playwright Real Key');
    }).toBe(true);

    const createdKeyRow = page.locator('tr').filter({ hasText: 'Playwright Real Key' });
    await createdKeyRow.locator('button').last().click();
    await expect.poll(async () => {
      const response = await request.get(`${API_BASE}/admin/workspace/keys`);
      const keys = await response.json();
      return keys.some((key: { name: string }) => key.name === 'Playwright Real Key');
    }).toBe(false);
  });

  test('creates and deletes a tenant through the real admin UI', async ({ page, request }) => {
    await login(page, { role: 'superadmin' });

    const suffix = Date.now().toString().slice(-6);
    const tenantName = `Playwright Health ${suffix}`;
    const tenantId = `playwright_health_${suffix}`;

    await page.goto('/admin/tenants/new');
    await expect(page.getByRole('heading', { name: 'Create Tenant' })).toBeVisible();

    await page.getByLabel('Tenant name').fill(tenantName);
    await expect(page.getByLabel('Tenant ID')).toHaveValue(tenantId);
    await page.getByLabel('Admin email').fill(`qa.${suffix}@playwright.dev`);
    await page.getByRole('textbox', { name: 'Sector' }).evaluate((element: HTMLInputElement) => element.click());
    await page.getByRole('option', { name: 'Healthcare' }).click();
    await page.getByRole('button', { name: 'Create tenant' }).click();

    await expect(page).toHaveURL(/\/admin\/tenants$/);
    await expect(page.locator('tr').filter({ hasText: tenantName })).toBeVisible();
    await expect.poll(async () => {
      const response = await request.get(`${API_BASE}/admin/tenants`);
      const tenants = await response.json();
      return tenants.some((tenant: { id: string }) => tenant.id === tenantId);
    }).toBe(true);

    await page.goto(`/admin/tenants/${tenantId}`);
    await expect(page.getByRole('heading', { name: 'Tenant Detail' })).toBeVisible();
    await expect(page.getByText(tenantName)).toBeVisible();
    await page.getByRole('button', { name: 'Delete' }).click();

    await expect.poll(async () => {
      const response = await request.get(`${API_BASE}/admin/tenants`);
      const tenants = await response.json();
      return tenants.some((tenant: { id: string }) => tenant.id === tenantId);
    }).toBe(false);
    await page.goto('/admin/tenants');
    await expect(page.getByText(tenantName)).toHaveCount(0);
  });

  test('updates a workspace user role through the real admin UI', async ({ page, request }) => {
    await login(page, { role: 'superadmin' });
    await impersonateWorkspace(page);

    await page.goto('/admin/workspace/users');
    await expect(page.getByRole('heading', { name: 'Users' })).toBeVisible();

    const usersBeforeResponse = await request.get(`${API_BASE}/admin/workspace/users`);
    const usersBefore = await usersBeforeResponse.json();
    const maria = usersBefore.find((user: { email: string }) => user.email === 'maria@rioquality.com.br');
    expect(maria).toBeTruthy();

    const mariaRow = page.locator('tr').filter({ hasText: 'Maria Santos' });
    await mariaRow.locator('button').first().click();

    const editModal = page.getByRole('dialog', { name: 'Edit role' });
    await editModal.getByLabel('Role').evaluate((element: HTMLInputElement) => element.click());
    await page.getByRole('option', { name: 'Viewer' }).click();
    await editModal.getByRole('button', { name: 'Save' }).evaluate((element: HTMLButtonElement) => element.click());

    await expect.poll(async () => {
      const response = await request.get(`${API_BASE}/admin/workspace/users`);
      const users = await response.json();
      return users.find((user: { id: string; role: string }) => user.id === maria.id)?.role;
    }).toBe('viewer');

    await expect(mariaRow.getByText('viewer')).toBeVisible();

    const restoreResponse = await request.put(`${API_BASE}/admin/workspace/users/${maria.id}`, {
      data: { role: 'editor' },
    });
    expect(restoreResponse.ok()).toBeTruthy();
    await expect.poll(async () => {
      const response = await request.get(`${API_BASE}/admin/workspace/users`);
      const users = await response.json();
      return users.find((user: { id: string; role: string }) => user.id === maria.id)?.role;
    }).toBe('editor');
  });

  test('loads global config, updates a toggle and views usage billing', async ({ page, request }) => {
    await login(page, { role: 'superadmin' });

    await page.goto('/admin/global');
    await expect(page.getByRole('heading', { name: 'Global Configuration' })).toBeVisible();
    await expect(page.getByText('LLM Models')).toBeVisible();
    await expect(page.getByText('Plan Defaults')).toBeVisible();
    await expect(page.getByText('Feature Toggles')).toBeVisible();
    await expect(page.getByText('GPT-4o')).toBeVisible();

    const modelResponse = await request.get(`${API_BASE}/admin/global/models`);
    const modelsBefore = await modelResponse.json();
    const gpt35Before = modelsBefore.find((model: { id: string; status: string }) => model.id === 'gpt-3.5-turbo');
    expect(gpt35Before?.status).toBe('deprecated');

    const gpt35Switch = page.getByLabel('Enable GPT-3.5 Turbo');
    await gpt35Switch.evaluate((element: HTMLInputElement) => element.click());
    await expect.poll(async () => {
      const response = await request.get(`${API_BASE}/admin/global/models`);
      const models = await response.json();
      return models.find((model: { id: string; status: string }) => model.id === 'gpt-3.5-turbo')?.status;
    }).toBe('active');

    const restoreModelResponse = await request.put(`${API_BASE}/admin/global/models/gpt-3.5-turbo`, {
      data: { active: false },
    });
    expect(restoreModelResponse.ok()).toBeTruthy();
    await expect.poll(async () => {
      const response = await request.get(`${API_BASE}/admin/global/models`);
      const models = await response.json();
      return models.find((model: { id: string; status: string }) => model.id === 'gpt-3.5-turbo')?.status;
    }).toBe('deprecated');

    const togglesBefore = await request.get(`${API_BASE}/admin/global/toggles`);
    const toggleBodyBefore = await togglesBefore.json();
    expect(toggleBodyBefore.debugMode).toBe(false);

    const debugToggle = page.getByLabel('Debug mode — full trace per node');
    await debugToggle.evaluate((element: HTMLInputElement) => element.click());
    await expect.poll(async () => {
      const response = await request.get(`${API_BASE}/admin/global/toggles`);
      const toggles = await response.json();
      return toggles.debugMode;
    }).toBe(true);

    const restoreToggleResponse = await request.put(`${API_BASE}/admin/global/toggles`, {
      data: toggleBodyBefore,
    });
    expect(restoreToggleResponse.ok()).toBeTruthy();
    await expect.poll(async () => {
      const response = await request.get(`${API_BASE}/admin/global/toggles`);
      const toggles = await response.json();
      return toggles.debugMode;
    }).toBe(false);

    await page.goto('/admin/billing');
    await expect(page.getByRole('heading', { name: 'Usage & Billing' })).toBeVisible();
    await expect(page.getByText('Rio Quality')).toBeVisible();
    await expect(page.getByText('$67.80')).toBeVisible();

    const exportLink = page.getByRole('link', { name: 'Export CSV' });
    await expect(exportLink).toHaveAttribute('href', /month=2026-04/);

    const usageResponse = await request.get(`${API_BASE}/admin/global/usage?month=2026-03`);
    expect(usageResponse.ok()).toBeTruthy();
    const usageRows = await usageResponse.json();
    expect(usageRows.length).toBeGreaterThan(0);
  });

  test('shows a realtime voice error when microphone permission is denied', async ({ page }) => {
    await page.addInitScript(() => {
      Object.defineProperty(navigator, 'mediaDevices', {
        configurable: true,
        value: {
          getUserMedia: () => Promise.reject(new Error('denied')),
        },
      });
    });

    await login(page);
    await page.goto('/playground/voice');
    await expect(page.getByRole('heading', { name: 'Voice playground' })).toBeVisible();
    await expect(page.getByTestId('voice-status')).toContainText('idle');

    await page.getByTestId('mic-button').click();
    await expect(page.getByTestId('voice-status')).toContainText('error');
    await expect(page.getByRole('alert')).toContainText(/Microphone permission denied|denied|Failed to start session/i);
  });

  test('streams realtime voice transcripts through the real backend websocket', async ({ page }) => {
    await installRealtimeBrowserFakes(page);
    await login(page);

    await page.goto('/playground/voice');
    await expect(page.getByRole('heading', { name: 'Voice playground' })).toBeVisible();
    await expect(page.getByTestId('voice-status')).toContainText('idle');

    await page.getByTestId('mic-button').click();
    await expect(page.getByTestId('voice-status')).toContainText(/recording|speaking/);
    await expect(page.getByText('quero rastrear meu pedido')).toBeVisible();
    await expect(page.getByText(/Pedido localizado.*default/i)).toBeVisible();
  });

  test('loads observability pages against the real backend', async ({ page, request }) => {
    await login(page, { role: 'superadmin' });

    const overviewResponse = await request.get(`${API_BASE}/admin/observability/overview`);
    expect(overviewResponse.ok()).toBeTruthy();
    const overview = await overviewResponse.json();
    expect(overview).toMatchObject({
      totalExecutionsToday: expect.any(Number),
      successRate: expect.any(Number),
      avgLatencyMs: expect.any(Number),
      p95LatencyMs: expect.any(Number),
      errorRate: expect.any(Number),
      activeStreams: expect.any(Number),
      totalAuditEventsToday: expect.any(Number),
      topPersonas: expect.any(Array),
      latencySparkline: expect.any(Array),
    });

    const metricsResponse = await request.get(`${API_BASE}/admin/observability/metrics`);
    expect(metricsResponse.ok()).toBeTruthy();
    const metrics = await metricsResponse.json();
    expect(metrics).toMatchObject({
      counters: expect.any(Object),
      values: expect.any(Object),
      stats: expect.any(Object),
    });

    const tracesResponse = await request.get(`${API_BASE}/admin/observability/traces?page=0&size=10`);
    expect(tracesResponse.ok()).toBeTruthy();
    const traces = await tracesResponse.json();
    expect(traces).toMatchObject({
      items: expect.any(Array),
      page: 0,
    });

    const auditResponse = await request.get(`${API_BASE}/admin/observability/audit?page=0&size=10`);
    expect(auditResponse.ok()).toBeTruthy();
    const audit = await auditResponse.json();
    expect(audit).toMatchObject({
      items: expect.any(Array),
      page: 0,
    });

    const runningResponse = await request.get(`${API_BASE}/admin/observability/running`);
    expect(runningResponse.ok()).toBeTruthy();
    const running = await runningResponse.json();
    expect(Array.isArray(running)).toBeTruthy();

    await page.goto('/admin/observability');
    await expect(page.getByText('Executions today')).toBeVisible();
    await expect(page.getByText('Success rate')).toBeVisible();
    await expect(page.getByText('Top personas (last 24h)')).toBeVisible();

    await page.goto('/admin/observability/running');
    await expect(page.getByRole('paragraph').filter({ hasText: 'Running flows' })).toBeVisible();
    await expect(page.getByText('0 active')).toBeVisible();
    await expect(page.getByText('No flows running right now')).toBeVisible();
  });
});
