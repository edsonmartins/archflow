import { expect, test, type Page } from '@playwright/test';
import { installSession } from './support/api';
import { type EditorWorkflow, installEditorApi } from './support/editor';

/**
 * Canvas table-stakes interactions added in "Onda B": click-to-add from
 * the palette, undo/redo, duplicate, and the tidy-up layout button.
 */

function initialWorkflow(): EditorWorkflow {
    return {
        id: 'wf-canvas',
        metadata: {
            name: 'Canvas Actions Flow',
            description: 'e2e target',
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
                configuration: {},
                connections: [],
            },
        ],
        configuration: {},
    };
}

async function openEditor(page: Page) {
    await installSession(page);
    await installEditorApi(page, { workflowId: 'wf-canvas', initialWorkflow, initialYaml: '' });
    await page.goto('/editor/wf-canvas');
    await expect(page.locator('.af-node-card').first()).toBeVisible();
}

test.describe('Workflow editor — canvas actions', () => {
    test('click-to-add inserts a node; undo removes it; redo restores it', async ({ page }) => {
        await openEditor(page);
        await expect(page.locator('.af-node-card')).toHaveCount(1);

        // Undo/redo start disabled — nothing to travel to yet.
        await expect(page.getByTestId('canvas-undo')).toBeDisabled();
        await expect(page.getByTestId('canvas-redo')).toBeDisabled();

        // Click a palette entry (no drag) → node lands on the canvas.
        await page.getByRole('button', { name: 'LLM Chat' }).click();
        await expect(page.locator('.af-node-card')).toHaveCount(2);

        await page.getByTestId('canvas-undo').click();
        await expect(page.locator('.af-node-card')).toHaveCount(1);

        await page.getByTestId('canvas-redo').click();
        await expect(page.locator('.af-node-card')).toHaveCount(2);
    });

    test('mod+D duplicates the selected node', async ({ page }) => {
        await openEditor(page);

        await page.locator('.af-node-card').first().click();
        await page.keyboard.press(process.platform === 'darwin' ? 'Meta+d' : 'Control+d');
        await expect(page.locator('.af-node-card')).toHaveCount(2);

        // Both copies keep the same label.
        await expect(page.locator('.af-node-card', { hasText: 'Support Agent' })).toHaveCount(2);
    });

    test('tidy up relayouts without changing the node count', async ({ page }) => {
        await openEditor(page);
        await page.getByRole('button', { name: 'LLM Chat' }).click();
        await expect(page.locator('.af-node-card')).toHaveCount(2);

        await page.getByTestId('canvas-tidy').click();
        await expect(page.locator('.af-node-card')).toHaveCount(2);
        // Tidy pushes an undo step.
        await expect(page.getByTestId('canvas-undo')).toBeEnabled();
    });
});
