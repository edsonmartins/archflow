import type { Page } from '@playwright/test';

export function installSilentEventSource(page: Page) {
    return page.addInitScript(() => {
        class SilentEventSource {
            url: string;
            onopen: null | (() => void) = null;
            onmessage: null | ((e: { data: string }) => void) = null;
            onerror: null | (() => void) = null;

            constructor(url: string) {
                this.url = url;
                setTimeout(() => this.onopen && this.onopen(), 0);
            }

            addEventListener() {}
            close() {}
        }

        Object.defineProperty(window, 'EventSource', {
            configurable: true,
            writable: true,
            value: SilentEventSource,
        });
    });
}

export async function installVoiceFake(page: Page) {
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

        const md = (navigator as { mediaDevices?: MediaDevices }).mediaDevices ?? ({} as MediaDevices);
        Object.defineProperty(navigator, 'mediaDevices', {
            configurable: true,
            value: { ...md, getUserMedia: () => Promise.resolve(fakeStream) },
        });

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

            simulate(msg: unknown) {
                if (this.onmessage) this.onmessage({ data: JSON.stringify(msg) });
            }
        }

        (window as unknown as { WebSocket: typeof FakeWebSocket }).WebSocket = FakeWebSocket;
    });
}
