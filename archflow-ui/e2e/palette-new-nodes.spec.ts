import { expect, test, type Page } from '@playwright/test';
import { installSession } from './support/api';
import { type EditorWorkflow, installEditorApi } from './support/editor';

/**
 * Smoke test for the node palette entries added for
 * skills / approval / subflow / mcp-tool / linktor-send /
 * linktor-escalate. Each is expected to appear in the left-hand
 * palette search results.
 */
function blankWorkflow(): EditorWorkflow {
    return {
        id: 'wf-palette',
        metadata: {
            name: 'Palette smoke',
            description: 'Smoke coverage for new node types',
            version: '1.0.0',
            category: 'test',
            tags: [],
        },
        steps: [],
    };
}

async function setup(page: Page) {
    await installSession(page);
    await installEditorApi(page, {
        workflowId: 'wf-palette',
        initialWorkflow: blankWorkflow,
    });
    await page.goto('/editor/wf-palette');
    await expect(page.getByText('Palette smoke')).toBeVisible();
}

const EXPECTED = [
    { id: 'skills',           label: 'Skills' },
    { id: 'approval',         label: 'Human approval' },
    { id: 'subflow',          label: 'Subflow' },
    { id: 'mcp-tool',         label: 'MCP tool' },
    { id: 'linktor-send',     label: 'Linktor send' },
    { id: 'linktor-escalate', label: 'Linktor escalate' },
];

test.describe('Palette — new nodes', () => {
    for (const entry of EXPECTED) {
        test(`palette exposes ${entry.label}`, async ({ page }) => {
            await setup(page);
            await expect(page.getByText(entry.label).first()).toBeVisible();
        });
    }
});
