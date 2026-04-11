import { expect, test, type Page, type Route } from '@playwright/test';

const user = {
    id: 'user-e2e',
    username: 'admin',
    name: 'Admin User',
    roles: ['admin'],
};

async function mockApi(page: Page) {
    await page.route('**/api/**', async (route) => {
        const url = new URL(route.request().url());
        const path = url.pathname.replace(/^\/api/, '');
        const method = route.request().method();

        if (path === '/auth/login' && method === 'POST') {
            await json(route, { token: 'e2e-token', refreshToken: 'e2e-refresh-token' });
            return;
        }
        if (path === '/auth/me' && method === 'GET') {
            await json(route, user);
            return;
        }
        await route.continue();
    });
}

async function authenticate(page: Page) {
    await page.addInitScript(() => {
        localStorage.setItem('archflow_token', 'e2e-token');
        localStorage.setItem('archflow_refresh_token', 'e2e-refresh-token');
    });
}

async function json(route: Route, body: unknown, status = 200) {
    await route.fulfill({
        status,
        contentType: 'application/json',
        body: JSON.stringify(body),
    });
}

/**
 * Drives a fake getUserMedia + WebSocket so the page can be exercised
 * without an actual mic or backend. The fake WS dispatches a sequence of
 * realtime messages on demand via window.__voiceFake.simulate(...).
 */
async function installVoiceFake(page: Page) {
    await page.addInitScript(() => {
        // Fake getUserMedia returns an empty MediaStream-ish object that
        // the realtime client can call .getTracks() on.
        const fakeStream = {
            getTracks: () => [
                {
                    stop() {},
                    kind: 'audio',
                    enabled: true,
                },
            ],
        } as unknown as MediaStream;

        const md = (navigator as { mediaDevices?: MediaDevices }).mediaDevices ?? ({} as MediaDevices);
        Object.defineProperty(navigator, 'mediaDevices', {
            configurable: true,
            value: { ...md, getUserMedia: () => Promise.resolve(fakeStream) },
        });

        // Fake AudioContext (just enough to satisfy the realtime client).
        // audioWorklet is intentionally undefined so the client falls back
        // to the ScriptProcessor path — we don't want to bootstrap an
        // AudioWorkletNode in jsdom/headless chromium.
        class FakeAudioContext {
            sampleRate = 24_000;
            destination = {} as AudioDestinationNode;
            audioWorklet = undefined;
            createMediaStreamSource() {
                return { connect: () => undefined };
            }
            createScriptProcessor() {
                return {
                    connect: () => undefined,
                    disconnect: () => undefined,
                    onaudioprocess: null as ((e: { inputBuffer: { getChannelData: () => Float32Array } }) => void) | null,
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

        // Fake WebSocket exposed as a singleton on window for assertions.
        type Listener = (e: { data: string }) => void;
        class FakeWebSocket {
            static OPEN = 1;
            readyState = 1;
            onopen: (() => void) | null = null;
            onmessage: Listener | null = null;
            onerror: (() => void) | null = null;
            onclose: (() => void) | null = null;
            sent: string[] = [];
            constructor(public url: string) {
                (window as unknown as { __voiceWs: FakeWebSocket }).__voiceWs = this;
                setTimeout(() => this.onopen && this.onopen(), 5);
            }
            send(payload: string) {
                this.sent.push(payload);
            }
            close() {
                this.readyState = 3;
                if (this.onclose) this.onclose();
            }
            // Test helper used from the page
            simulate(msg: unknown) {
                if (this.onmessage) this.onmessage({ data: JSON.stringify(msg) });
            }
        }
        (window as unknown as { WebSocket: typeof FakeWebSocket }).WebSocket = FakeWebSocket;
    });
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
