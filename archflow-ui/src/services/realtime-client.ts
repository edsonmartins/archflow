/**
 * Realtime voice client for ArchFlow.
 *
 * Bridges the browser microphone (via Web Audio API) with a backend
 * WebSocket endpoint that proxies to a realtime-capable LLM provider
 * (OpenAI Realtime, Gemini Live, etc).
 *
 * Wire format mirrors the SSE envelope used by ArchflowEvent so the
 * frontend can reuse the same domain types. Each WS message is a JSON
 * envelope with `type` ∈ {ready, transcript, audio, agent_done, error}
 * and a `data` payload. Audio payloads are base64-encoded PCM16 mono
 * 24kHz frames.
 *
 * Inspired by PraisonAI's `realtimeclient` Python module — same
 * separation of concerns (low-level WebSocket / state / public API)
 * adapted to a TypeScript class.
 */

export type RealtimeStatus =
    | 'idle'
    | 'requesting_mic'
    | 'connecting'
    | 'recording'
    | 'speaking'
    | 'closed'
    | 'error';

export type RealtimeMessage =
    | { type: 'ready'; data: { sessionId: string } }
    | { type: 'transcript'; data: { speaker: 'user' | 'agent'; text: string; final: boolean } }
    | { type: 'audio'; data: { pcm16: string; sampleRate: number } }
    | { type: 'agent_done'; data: Record<string, never> }
    | { type: 'error'; data: { message: string } };

export interface RealtimeConfig {
    /** Tenant id used to scope the realtime session. */
    tenantId: string;
    /** Persona id (selected by the user). */
    personaId: string;
    /** Override the WS base URL. Defaults to `${baseUrl}/realtime`. */
    baseUrl?: string;
    /**
     * Optional factory used by tests to inject a fake WebSocket.
     * Returns an object that conforms to the WebSocket interface.
     */
    webSocketFactory?: (url: string) => WebSocket;
}

const DEFAULT_BASE = (import.meta.env.VITE_REALTIME_BASE as string | undefined) ?? '/api';

/**
 * High-level realtime client. Lifecycle:
 *
 *   const client = new RealtimeClient({ tenantId, personaId });
 *   client.onStatus(s => setStatus(s));
 *   client.onMessage(msg => append(msg));
 *   await client.start();
 *   // ... user speaks ...
 *   await client.stop();
 *
 * Internally `start()` requests the mic, opens the WS, kicks off an
 * AudioWorklet (or fallback ScriptProcessor) that downsamples the live
 * audio to PCM16 24kHz and ships frames as `audio` messages. Inbound
 * `audio` frames are decoded and queued to a single AudioBufferSource
 * playing back through the default destination.
 */
export class RealtimeClient {
    private status: RealtimeStatus = 'idle';
    private statusListeners = new Set<(s: RealtimeStatus) => void>();
    private messageListeners = new Set<(m: RealtimeMessage) => void>();

    private ws: WebSocket | null = null;
    private mediaStream: MediaStream | null = null;
    private audioContext: AudioContext | null = null;
    private workletNode: AudioWorkletNode | null = null;
    private legacyProcessor: ScriptProcessorNode | null = null;
    private playbackQueue: AudioBuffer[] = [];
    private playbackPlaying = false;

    constructor(private config: RealtimeConfig) {}

    onStatus(listener: (s: RealtimeStatus) => void): () => void {
        this.statusListeners.add(listener);
        listener(this.status);
        return () => this.statusListeners.delete(listener);
    }

    onMessage(listener: (m: RealtimeMessage) => void): () => void {
        this.messageListeners.add(listener);
        return () => this.messageListeners.delete(listener);
    }

    getStatus(): RealtimeStatus {
        return this.status;
    }

    /**
     * Acquire the microphone, open the WS and start streaming audio.
     * Throws if mic permission is denied or the WS fails to open within 5s.
     */
    async start(): Promise<void> {
        if (this.status !== 'idle' && this.status !== 'closed' && this.status !== 'error') {
            return;
        }

        this.setStatus('requesting_mic');
        try {
            this.mediaStream = await navigator.mediaDevices.getUserMedia({
                audio: { channelCount: 1, sampleRate: 24_000 },
            });
        } catch (err) {
            this.setStatus('error');
            this.emit({ type: 'error', data: { message: 'Microphone permission denied' } });
            throw err;
        }

        this.setStatus('connecting');
        await this.openWebSocket();

        await this.startCapture();
        this.setStatus('recording');
    }

    /**
     * Stop capture, close the WS and release the mic.
     */
    async stop(): Promise<void> {
        if (this.workletNode) {
            try {
                this.workletNode.port.onmessage = null;
                this.workletNode.disconnect();
            } catch { /* node already disconnected */ }
            this.workletNode = null;
        }
        if (this.legacyProcessor) {
            try {
                this.legacyProcessor.disconnect();
            } catch { /* processor already disconnected */ }
            this.legacyProcessor = null;
        }
        if (this.audioContext) {
            try {
                await this.audioContext.close();
            } catch { /* context already closed */ }
            this.audioContext = null;
        }
        if (this.mediaStream) {
            for (const track of this.mediaStream.getTracks()) track.stop();
            this.mediaStream = null;
        }
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
            try {
                this.ws.close();
            } catch { /* socket already closing */ }
        }
        this.ws = null;
        this.setStatus('closed');
    }

    // ── internals ──────────────────────────────────────────────────

    private openWebSocket(): Promise<void> {
        return new Promise((resolve, reject) => {
            const { tenantId, personaId, baseUrl = DEFAULT_BASE, webSocketFactory } = this.config;
            const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
            const path = `${baseUrl}/realtime/${encodeURIComponent(tenantId)}/${encodeURIComponent(personaId)}`;
            const url = baseUrl.startsWith('ws')
                ? path
                : `${protocol}//${window.location.host}${path}`;

            const ws = webSocketFactory ? webSocketFactory(url) : new WebSocket(url);
            this.ws = ws;

            const timeout = setTimeout(() => {
                reject(new Error('WebSocket connect timeout'));
                try {
                    ws.close();
                } catch { /* socket never opened */ }
            }, 5000);

            ws.onopen = () => {
                clearTimeout(timeout);
                resolve();
            };

            ws.onmessage = (e) => {
                this.handleMessage(e.data);
            };

            ws.onerror = () => {
                clearTimeout(timeout);
                this.setStatus('error');
                this.emit({ type: 'error', data: { message: 'WebSocket error' } });
                reject(new Error('WebSocket error'));
            };

            ws.onclose = () => {
                if (this.status !== 'closed') this.setStatus('closed');
            };
        });
    }

    private handleMessage(raw: unknown): void {
        if (typeof raw !== 'string') return;
        let msg: RealtimeMessage;
        try {
            msg = JSON.parse(raw) as RealtimeMessage;
        } catch {
            return;
        }
        if (!msg?.type) return;

        if (msg.type === 'audio') {
            this.queuePlayback(msg.data.pcm16, msg.data.sampleRate);
            if (this.status === 'recording') this.setStatus('speaking');
        } else if (msg.type === 'agent_done') {
            this.setStatus('recording');
        }

        this.emit(msg);
    }

    private async startCapture(): Promise<void> {
        if (!this.mediaStream) return;
        const Ctx =
            window.AudioContext ||
            (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
        const ctx = new Ctx({ sampleRate: 24_000 });
        this.audioContext = ctx;
        const source = ctx.createMediaStreamSource(this.mediaStream);

        // Preferred path: AudioWorklet running on the audio rendering
        // thread. No GC jitter and no deprecated API. Falls back to
        // ScriptProcessor only when the worklet fails to load (older
        // browsers, offline test environments, missing public asset).
        const sampleRate = ctx.sampleRate;
        const worklet = ctx.audioWorklet;
        if (worklet && typeof worklet.addModule === 'function') {
            try {
                await worklet.addModule('/worklets/pcm16-encoder.worklet.js');
                const node = new AudioWorkletNode(ctx, 'pcm16-encoder');
                node.port.onmessage = (event: MessageEvent<Int16Array>) => {
                    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
                    const bytes = new Uint8Array(
                        event.data.buffer,
                        event.data.byteOffset,
                        event.data.byteLength,
                    );
                    this.ws.send(
                        JSON.stringify({
                            type: 'audio',
                            data: { pcm16: bytesToBase64(bytes), sampleRate },
                        }),
                    );
                };
                source.connect(node);
                node.connect(ctx.destination);
                this.workletNode = node;
                return;
            } catch (err) {
                console.warn('[realtime] AudioWorklet unavailable, falling back to ScriptProcessor', err);
            }
        }

        // Legacy fallback — keeps tests and older browsers working.
        const processor = ctx.createScriptProcessor(2048, 1, 1);
        this.legacyProcessor = processor;
        processor.onaudioprocess = (e) => {
            if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
            const channel = e.inputBuffer.getChannelData(0);
            const pcm16 = floatToPcm16(channel);
            this.ws.send(
                JSON.stringify({
                    type: 'audio',
                    data: { pcm16: bytesToBase64(pcm16), sampleRate },
                }),
            );
        };
        source.connect(processor);
        processor.connect(ctx.destination);
    }

    private queuePlayback(pcm16Base64: string, sampleRate: number): void {
        if (!this.audioContext) return;
        const bytes = base64ToBytes(pcm16Base64);
        const samples = pcm16ToFloat(bytes);
        const buffer = this.audioContext.createBuffer(1, samples.length, sampleRate);
        buffer.copyToChannel(samples, 0);
        this.playbackQueue.push(buffer);
        this.drainPlayback();
    }

    private drainPlayback(): void {
        if (this.playbackPlaying || !this.audioContext) return;
        const next = this.playbackQueue.shift();
        if (!next) return;
        this.playbackPlaying = true;
        const src = this.audioContext.createBufferSource();
        src.buffer = next;
        src.connect(this.audioContext.destination);
        src.onended = () => {
            this.playbackPlaying = false;
            this.drainPlayback();
        };
        src.start();
    }

    private setStatus(status: RealtimeStatus): void {
        if (this.status === status) return;
        this.status = status;
        for (const listener of this.statusListeners) listener(status);
    }

    private emit(msg: RealtimeMessage): void {
        for (const listener of this.messageListeners) {
            try {
                listener(msg);
            } catch (err) {
                console.error('realtime listener error', err);
            }
        }
    }
}

// ── PCM16 helpers ──────────────────────────────────────────────────

function floatToPcm16(input: Float32Array): Uint8Array {
    const out = new Uint8Array(input.length * 2);
    const view = new DataView(out.buffer);
    for (let i = 0; i < input.length; i++) {
        const s = Math.max(-1, Math.min(1, input[i]));
        view.setInt16(i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true);
    }
    return out;
}

function pcm16ToFloat(bytes: Uint8Array): Float32Array {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const out = new Float32Array(bytes.length / 2);
    for (let i = 0; i < out.length; i++) {
        const v = view.getInt16(i * 2, true);
        out[i] = v < 0 ? v / 0x8000 : v / 0x7fff;
    }
    return out;
}

function bytesToBase64(bytes: Uint8Array): string {
    let s = '';
    for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return btoa(s);
}

function base64ToBytes(b64: string): Uint8Array {
    const s = atob(b64);
    const out = new Uint8Array(s.length);
    for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
    return out;
}
