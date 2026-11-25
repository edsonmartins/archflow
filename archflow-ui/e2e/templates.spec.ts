import { expect, test, type Page } from '@playwright/test';
import { adminUser, authHandlers, fulfillJson, installApiRouter, installSession } from './support/api';

const createdWorkflowId = 'wf-from-template';

/** Um template SPI como o backend o serializa: grafo real em steps[].connections (RCHFLW-18). */
const backendTemplate = {
    id: 'customer-support',
    displayName: 'Customer Support with RAG',
    description: 'Multi-agent customer support workflow',
    category: 'support',
    tags: ['rag'],
    stepCount: 2,
    steps: [
        { id: 'input', type: 'input', componentId: 'input', label: 'Customer Inquiry Input',
          position: { x: 80, y: 80 }, configuration: {},
          connections: [{ sourceId: 'input', targetId: 'kb-search', isErrorPath: false }] },
        { id: 'kb-search', type: 'vector-search', componentId: 'vector-search', label: 'Knowledge Base Search',
          position: { x: 340, y: 80 }, configuration: { topK: 5 }, connections: [] },
    ],
    // `input` é um ControlStep no motor; só o vector-search (sem provider) fica sem executor.
    unsupportedNodeTypes: ['vector-search'],
};

async function mockApi(page: Page, options: { backendTemplates?: unknown[] } = {}) {
    // O que o POST /workflows recebeu: o GET devolve exatamente isso, como o backend real
    // (antes o GET fixo com `steps: []` escondia que o clone perdia arestas e config — RCHFLW-45).
    const state: { created: Record<string, unknown> | null } = { created: null };
    await installApiRouter(page, [
        ...authHandlers(adminUser, { loginShape: 'token' }),
        // Keep the gallery hermetic: without this the request reaches whatever backend the dev server
        // proxies to. The backend catalog is MERGED with the bundled one (RCHFLW-46); 404 = local only.
        async ({ path, method, route }) => {
            if (path !== '/templates' || method !== 'GET') return false;
            if (options.backendTemplates) {
                await fulfillJson(route, options.backendTemplates);
            } else {
                await fulfillJson(route, { message: 'not mocked' }, 404);
            }
            return true;
        },
        async ({ path, method, request, route }) => {
            if (path !== '/templates/unsupported-node-types' || method !== 'POST') return false;
            const body = (request.postDataJSON() ?? {}) as Record<string, unknown>;
            await fulfillJson(route, Object.fromEntries(Object.keys(body).map((id) => [id, ['agent']])));
            return true;
        },
        async ({ path, method, request, route }) => {
            if (path !== '/workflows' || method !== 'POST') return false;
            const body = request.postDataJSON() as Record<string, unknown>;
            const meta = (body?.metadata ?? {}) as Record<string, unknown>;
            const created = {
                id: createdWorkflowId,
                metadata: meta,
                steps: body?.steps ?? [],
                configuration: body?.configuration ?? {},
            };
            state.created = created;
            await fulfillJson(route, created, 201);
            return true;
        },
        async ({ path, method, route }) => {
            if (path !== `/workflows/${createdWorkflowId}` || method !== 'GET') return false;
            await fulfillJson(route, state.created ?? { id: createdWorkflowId, metadata: {}, steps: [] });
            return true;
        },
    ]);
    return state;
}

type ClonedStep = { id: string; configuration?: Record<string, unknown>; connections?: unknown[] };

async function authenticate(page: Page) {
    await installSession(page);
}

test.describe('Templates gallery', () => {
    test('lists templates and filters by category', async ({ page }) => {
        await mockApi(page);
        await authenticate(page);

        await page.goto('/templates');

        await expect(page.getByRole('heading', { name: 'Templates' })).toBeVisible();
        await expect(page.getByText('Customer support agent')).toBeVisible();
        await expect(page.getByText('Document Q&A (RAG)')).toBeVisible();
        await expect(page.getByText('Code reviewer')).toBeVisible();

        // Filter by Code chip — Mantine Chip renders as a label, click directly
        await page.locator('label').filter({ hasText: /^Code$/ }).click();
        await expect(page.getByText('Code reviewer')).toBeVisible();
        await expect(page.getByText('Customer support agent')).toBeHidden();

        // Reset filter and search by tag
        await page.locator('label').filter({ hasText: /^All$/ }).click();
        await page.getByTestId('templates-search').fill('rag');
        await expect(page.getByText('Document Q&A (RAG)')).toBeVisible();
        await expect(page.getByText('Customer support agent')).toBeHidden();
    });

    test('opens detail page and clones into a workflow', async ({ page }) => {
        const state = await mockApi(page);
        await authenticate(page);

        await page.goto('/templates');

        await page.getByTestId('template-card-sac-basic').click();
        await expect(page).toHaveURL(/\/templates\/sac-basic$/);
        await expect(page.getByRole('heading', { name: 'Customer support agent' })).toBeVisible();
        // The pipeline list shows step labels — match the first occurrence
        // (the same text also appears inside the JSON code block).
        await expect(page.getByText('Inbound message').first()).toBeVisible();

        await page.getByTestId('template-clone').click();
        await expect(page).toHaveURL(new RegExp(`/editor/${createdWorkflowId}$`));

        // Documento canônico: arestas dentro dos passos, config em `configuration`, nada no topo.
        const steps = (state.created?.steps ?? []) as ClonedStep[];
        expect(steps).toHaveLength(7);
        expect(steps.flatMap((s) => s.connections ?? [])).toHaveLength(8);
        expect(steps.find((s) => s.id === 's2')?.configuration).toEqual({ strategy: 'llm' });
        expect(state.created?.configuration).not.toHaveProperty('connections');

        // O editor abre o clone com os nós E as arestas.
        await expect(page.locator('.react-flow__node')).toHaveCount(7);
        await expect(page.locator('.react-flow__edge')).toHaveCount(8);
    });

    test('filters by complexity', async ({ page }) => {
        await mockApi(page);
        await authenticate(page);

        await page.goto('/templates');

        await page.getByTestId('templates-complexity').click();
        await page.getByRole('option', { name: 'Starter' }).click();

        // Only the starter template should remain (Document Q&A is starter)
        await expect(page.getByText('Document Q&A (RAG)')).toBeVisible();
        await expect(page.getByText('Customer support agent')).toBeHidden();
        await expect(page.getByText('Web research → report')).toBeHidden();

        // Reset
        await page.getByTestId('templates-complexity').click();
        await page.getByRole('option', { name: 'Any complexity' }).click();
        await expect(page.getByText('Customer support agent')).toBeVisible();
    });

    test('merges the backend catalog with the bundled one and marks nodes without executor', async ({ page }) => {
        await mockApi(page, { backendTemplates: [backendTemplate] });
        await authenticate(page);

        await page.goto('/templates');

        // Antes a lista do backend substituía a local: as 6 receitas sumiam.
        await expect(page.getByTestId('template-card-customer-support')).toBeVisible();
        await expect(page.getByTestId('template-card-sac-basic')).toBeVisible();
        await expect(page.getByTestId('template-card-email-triage')).toBeVisible();
        await expect(page.getByTestId('template-unsupported-customer-support')).toContainText('vector-search');
        await expect(page.getByTestId('template-unsupported-sac-basic')).toContainText('agent');

        await page.getByTestId('template-card-customer-support').click();
        await expect(page.getByTestId('template-unsupported')).toContainText('vector-search');
        await expect(page.getByText('Knowledge Base Search').first()).toBeVisible();
        await expect(page.getByTestId('template-clone')).toBeEnabled();
    });

    test('shows not found for unknown template id', async ({ page }) => {
        await mockApi(page);
        await authenticate(page);

        await page.goto('/templates/does-not-exist');
        await expect(page.getByText('Template not found.')).toBeVisible();
    });
});
