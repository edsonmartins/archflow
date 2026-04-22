import { expect, test, type Page } from '@playwright/test';
import { adminUser, authHandlers, installApiRouter, installSession } from './support/api';
import { installVoiceFake } from './support/browser';

async function mockApi(page: Page) {
    await installApiRouter(page, [...authHandlers(adminUser, { loginShape: 'token' })]);
}

async function authenticate(page: Page) {
    await installSession(page);
}

test.describe('Voice playground', () => {
    test('renders idle state and persona selector', async ({ page }) => {
        await mockApi(page);
        await authenticate(page);
        await installVoiceFake(page);

        await page.goto('/playground/voice');

        await expect(page.getByRole('heading', { name: 'Voice playground' })).toBeVisible();
        await expect(page.getByTestId('voice-status')).toHaveText('idle');
        await expect(page.getByText('No transcript yet — start the session to begin.')).toBeVisible();

        const mic = page.getByTestId('mic-button');
        await expect(mic).toBeVisible();
        await expect(mic).toHaveAttribute('data-status', 'idle');
    });

    test('starts a session, simulates transcript, then resets', async ({ page }) => {
        await mockApi(page);
        await authenticate(page);
        await installVoiceFake(page);

        await page.goto('/playground/voice');

        await page.getByTestId('mic-button').click();

        // Wait until the realtime client transitions to recording
        await expect(page.getByTestId('voice-status')).toHaveText('recording');
        await expect(page.getByTestId('mic-button')).toHaveAttribute('data-status', 'recording');

        // Push a fake transcript fragment from the server
        await page.evaluate(() => {
            const ws = (window as unknown as { __voiceWs?: { simulate: (m: unknown) => void } }).__voiceWs;
            ws?.simulate({
                type: 'transcript',
                data: { speaker: 'user', text: 'rastrear pedido', final: true },
            });
            ws?.simulate({
                type: 'transcript',
                data: { speaker: 'agent', text: 'Seu pedido está em trânsito.', final: true },
            });
        });

        await expect(page.getByText('rastrear pedido')).toBeVisible();
        await expect(page.getByText('Seu pedido está em trânsito.')).toBeVisible();

        // Reset clears state
        await page.getByTestId('voice-reset').click();
        await expect(page.getByTestId('voice-status')).toHaveText('idle');
        await expect(page.getByText('No transcript yet — start the session to begin.')).toBeVisible();
    });
});
