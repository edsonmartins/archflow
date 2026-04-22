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

async function mockApi(page: Page) {
  return installEditorApi(page, {
    workflowId: 'wf-prop',
    initialWorkflow,
    initialYaml: '',
  });
}

async function authenticate(page: Page) {
  await installSession(page);
}

test.describe('Workflow editor — PropertyPanel', () => {
  test('loads the agent node and surfaces its config in PropertyPanel', async ({ page }) => {
    const pageErrors: Error[] = [];
    page.on('pageerror', (err) => pageErrors.push(err));
    await mockApi(page);
    await authenticate(page);
    await page.goto('/editor/wf-prop');

    // Wait for canvas to render the agent node label
    const node = page.locator('.react-flow__node').first();
    await expect(node).toBeVisible();
    await expect(page.getByText('Support Agent').first()).toBeVisible();

    // Empty panel is shown until a node is selected
    await expect(page.getByText('No node selected')).toBeVisible();

    // Click the node to select it
    await node.click({ force: true });
    await page.waitForTimeout(500);
    if (pageErrors.length > 0) {
      throw new Error('Uncaught page errors: ' + pageErrors.map(e => e.message).join('; '));
    }

    // PropertyPanel swaps to the node fields — the Model select label
    // is only rendered for agent nodes, so its presence proves the
    // panel picked up the selection.
    await expect(page.getByRole('textbox', { name: 'Model' })).toBeVisible({ timeout: 10000 });
    await expect(page.getByRole('textbox', { name: 'Execution strategy' })).toBeVisible();
  });

  test('changing fields in PropertyPanel updates the canvas snapshot and Save sends merged config', async ({ page }) => {
    const state = await mockApi(page);
    await authenticate(page);
    await page.goto('/editor/wf-prop');

    // Select the agent node
    await expect(page.locator('.react-flow__node').first()).toBeVisible();
    await page.locator('.react-flow__node').first().click();

    // Change agent pattern to ReWOO
    await page.getByRole('textbox', { name: 'Execution strategy' }).click();
    await page.getByRole('option', { name: /ReWOO/i }).click();

    // Change model to Claude Opus
    await page.getByRole('textbox', { name: 'Model' }).click();
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

  test('persona selector populates the system prompt', async ({ page }) => {
    await mockApi(page);
    await authenticate(page);
    await page.goto('/editor/wf-prop');

    await expect(page.locator('.react-flow__node').first()).toBeVisible();
    await page.locator('.react-flow__node').first().click();

    // Persona dropdown should be visible (the API returned 2 personas)
    await expect(page.getByRole('textbox', { name: 'Persona' })).toBeVisible();
    await page.getByRole('textbox', { name: 'Persona' }).click();
    await page.getByRole('option', { name: /Order Tracking/ }).click();

    // System prompt becomes populated and disabled
    const prompt = page.getByPlaceholder('You are a helpful assistant...');
    await expect(prompt).toHaveValue(/Tracks orders/);
    await expect(prompt).toBeDisabled();
  });

  test('governance accordion surfaces profile selector and seeds fields', async ({ page }) => {
    await mockApi(page);
    await authenticate(page);
    await page.goto('/editor/wf-prop');

    await expect(page.locator('.react-flow__node').first()).toBeVisible();
    await page.locator('.react-flow__node').first().click();

    // Open Governance accordion
    await page.getByRole('button', { name: 'Governance' }).click();

    // Profile selector is visible
    await expect(page.getByRole('textbox', { name: 'Profile' })).toBeVisible();
    await page.getByRole('textbox', { name: 'Profile' }).click();
    await page.getByRole('option', { name: 'Strict' }).click();

    // Escalation threshold is seeded from the shared strict profile fixture.
    const threshold = page.getByLabel('Escalation threshold');
    await expect(threshold).toHaveValue('0.75');
  });
});
