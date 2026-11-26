import { expect, test, type Page } from '@playwright/test';
import { installSession } from './support/api';
import {
  type EditorWorkflow,
  installEditorApi,
} from './support/editor';

/**
 * End-to-end coverage for the workflow editor PropertyPanel.
 *
 * Exercises the full round-trip that the unit tests cannot: click an
 * agent node on the canvas, edit its fields (model, pattern,
 * temperature, timeout, governance), save, and verify the backend
 * received the merged configuration. The mock handler captures the PUT
 * payload so assertions can inspect exactly which keys were persisted.
 */

function initialWorkflow(): EditorWorkflow {
  return {
    id: 'wf-prop',
    metadata: {
      name: 'Property Panel Flow',
      description: 'Unit test target',
      version: '1.0.0',
      category: 'test',
      tags: ['e2e'],
    },
    steps: [
      {
        id: 'step-agent',
        type: 'AGENT',
        componentId: 'agent',
        operation: 'Support Agent',
        position: { x: 260, y: 200 },
        configuration: {
          provider: 'anthropic',
          model: 'claude-sonnet-4-6',
          temperature: 0.7,
          maxTokens: 4096,
          agentPattern: 'react',
        },
        connections: [],
      },
    ],
    configuration: {},
  };
}

async function mockApi(page: Page, configuration?: Record<string, unknown>) {
  return installEditorApi(page, {
    workflowId: 'wf-prop',
    initialWorkflow: configuration ? () => ({ ...initialWorkflow(), configuration }) : initialWorkflow,
    initialYaml: '',
  });
}

async function authenticate(page: Page) {
  await installSession(page);
}

async function selectFirstNode(page: Page, expectedTitle = 'Support Agent') {
  const node = page.locator('.af-node-card').first();
  await expect(node).toBeVisible();
  await node.click();
  await expect(page.getByTestId('property-panel-node-title')).toHaveText(expectedTitle);
}

test.describe('Workflow editor — PropertyPanel', () => {
  test('loads the agent node and surfaces its config in PropertyPanel', async ({ page }) => {
    const pageErrors: Error[] = [];
    page.on('pageerror', (err) => pageErrors.push(err));
    await mockApi(page);
    await authenticate(page);
    await page.goto('/editor/wf-prop');

    // Wait for canvas to render the agent node label
    const node = page.locator('.af-node-card').first();
    await expect(node).toBeVisible();
    await expect(page.getByText('Support Agent').first()).toBeVisible();

    // With no node selected the panel shows the flow-level model defaults
    await expect(page.getByText('Flow defaults')).toBeVisible();

    // Click the node to select it
    await node.click();
    await expect(page.getByTestId('property-panel-node-title')).toHaveText('Support Agent');
    await page.waitForTimeout(500);
    if (pageErrors.length > 0) {
      throw new Error('Uncaught page errors: ' + pageErrors.map(e => e.message).join('; '));
    }

    // PropertyPanel swaps to the node fields — the Model select label
    // is only rendered for agent nodes, so its presence proves the
    // panel picked up the selection.
    await expect(page.getByRole('combobox', { name: 'Model' })).toBeVisible({ timeout: 10000 });
    await expect(page.getByRole('combobox', { name: 'Execution strategy' })).toBeVisible();
  });

  test('changing fields in PropertyPanel updates the canvas snapshot and Save sends merged config', async ({ page }) => {
    const state = await mockApi(page);
    await authenticate(page);
    await page.goto('/editor/wf-prop');

    // Select the agent node
    await selectFirstNode(page);

    // Change agent pattern to ReWOO
    await page.getByRole('combobox', { name: 'Execution strategy' }).click();
    await page.getByRole('option', { name: /ReWOO/i }).click();

    // Change model to Claude Opus
    await page.getByRole('combobox', { name: 'Model' }).click();
    await page.getByRole('option', { name: /Claude Opus/i }).click();

    // Adjust temperature
    const tempInput = page.getByLabel('Temperature');
    await tempInput.fill('0.3');

    // Save
    await page.getByTestId('editor-save').click();

    // Wait for PUT to land
    await page.waitForFunction(() => true, { timeout: 500 }).catch(() => {});
    await expect.poll(() => state.getLastPut() !== null, { timeout: 3000 }).toBeTruthy();

    const step = state.getLastPut()!.steps[0];
    expect(step.id).toBe('step-agent');
    expect(step.configuration.agentPattern).toBe('rewoo');
    expect(step.configuration.model).toBe('claude-opus-4-6');
    expect(step.configuration.temperature).toBe(0.3);
  });

  test('flow defaults panel persists flow-level LLM config on save', async ({ page }) => {
    // O painel só oferece os modelos do provider que os nós herdam (RCHFLW-182): aqui o do fluxo.
    const state = await mockApi(page, { llmConfig: { provider: 'anthropic' } });
    await authenticate(page);
    await page.goto('/editor/wf-prop');

    // No node selected → flow defaults panel (the "flow" tier of the
    // model inheritance chain).
    await expect(page.locator('.af-node-card').first()).toBeVisible();
    await expect(page.getByText('Flow defaults')).toBeVisible();

    // Pick a default model for the whole flow.
    await page.getByRole('combobox', { name: 'Model' }).click();
    await page.getByRole('option', { name: /Claude Opus/i }).click();

    // Set default max tokens.
    await page.getByLabel('Max tokens').fill('2048');

    // Save and verify the PUT carried the flow-level llmConfig.
    await page.getByTestId('editor-save').click();
    await expect.poll(() => state.getLastPut() !== null, { timeout: 3000 }).toBeTruthy();

    const llmConfig = (state.getLastPut()!.configuration as { llmConfig?: Record<string, unknown> }).llmConfig ?? {};
    expect(llmConfig.provider).toBe('anthropic');
    expect(llmConfig.model).toBe('claude-opus-4-6');
    expect(llmConfig.maxTokens).toBe(2048);
  });

  test('flow defaults panel offers only the inherited provider models', async ({ page }) => {
    // Sem provider no fluxo e sem o da plataforma (o mock não manda platformDefault): nenhuma sugestão, em vez dos
    // modelos de todos os providers — escolher um deles gravava um id que o provider herdado recusa (RCHFLW-182).
    await mockApi(page);
    await authenticate(page);
    await page.goto('/editor/wf-prop');

    await expect(page.getByText('Flow defaults')).toBeVisible();
    await expect(page.getByTestId('flow-model-provider-hint')).toHaveText(/did not report the provider/i);
    await page.getByRole('combobox', { name: 'Model' }).click();
    await expect(page.getByRole('option', { name: /Claude|GPT/i })).toHaveCount(0);
  });

  test('persona selector populates the system prompt', async ({ page }) => {
    await mockApi(page);
    await authenticate(page);
    await page.goto('/editor/wf-prop');

    await selectFirstNode(page);

    const persona = page.getByRole('combobox', { name: 'Persona' });
    const choose = async (name: RegExp) => {
      await persona.click();
      await page.getByRole('option', { name }).click();
    };

    // Persona dropdown should be visible (the API returned 2 personas)
    await expect(persona).toBeVisible();
    await choose(/Order Tracking/);

    // O campo vazio recebe o prompt da persona e continua editável (RCHFLW-183: antes ele travava).
    const prompt = page.getByPlaceholder('You are a helpful assistant...');
    await expect(prompt).toHaveValue(/Tracks orders/);
    await expect(prompt).toBeEnabled();

    // Voltar para Personalizado desfaz só o que a persona pôs.
    await choose(/Custom \(free text\)/);
    await expect(prompt).toHaveValue('');

    // O prompt escrito pelo autor sobrevive à troca de persona e à volta para Personalizado.
    await prompt.fill('Answer only about returns.');
    await choose(/Customer Support/);
    await expect(prompt).toHaveValue('Answer only about returns.');
    await choose(/Custom \(free text\)/);
    await expect(prompt).toHaveValue('Answer only about returns.');
  });

  test('governance accordion surfaces profile selector and seeds fields', async ({ page }) => {
    await mockApi(page);
    await authenticate(page);
    await page.goto('/editor/wf-prop');

    await selectFirstNode(page);

    // Open Governance accordion
    await page.getByRole('button', { name: 'Governance' }).click();

    // Profile selector is visible
    await expect(page.getByRole('combobox', { name: 'Profile' })).toBeVisible();
    await page.getByRole('combobox', { name: 'Profile' }).click();
    await page.getByRole('option', { name: 'Strict' }).click();

    // Escalation threshold is seeded from the shared strict profile fixture.
    const threshold = page.getByLabel('Escalation threshold');
    await expect(threshold).toHaveValue('0.75');
  });
});
