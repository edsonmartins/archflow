import { useEffect, useRef, useState } from 'react';

/**
 * Streaming event protocol mirror of the backend ArchflowEvent envelope
 * (see archflow-agent/streaming/ArchflowEvent.java).
 *
 * Domains: chat | thinking | tool | audit | interaction | system | payload
 * Types per domain are documented in archflow-agent/streaming/ArchflowEventType.java
 */
export type ArchflowDomain =
    | 'chat'
    | 'thinking'
    | 'tool'
    | 'audit'
    | 'interaction'
    | 'system'
    | 'payload';

export type ArchflowEventType =
    // universal
    | 'start'
    | 'end'
    | 'error'
    // chat
    | 'delta'
    | 'message'
    // thinking
    | 'thinking'
    | 'reflection'
    | 'verification'
    // tool
    | 'tool_start'
    | 'progress'
    | 'result'
    | 'tool_error'
    // audit
    | 'trace'
    | 'span'
    | 'metric'
    | 'log'
    // interaction
    | 'suspend'
    | 'form'
    | 'resume'
    | 'cancel'
    // system
    | 'connected'
    | 'disconnected'
    | 'heartbeat'
    // payload
    | 'payload_chunk'
    | 'payload_complete';

export interface ArchflowEventEnvelope {
    domain: ArchflowDomain;
    type: ArchflowEventType;
    id: string;
    timestamp: string;
    correlationId?: string;
    executionId?: string;
    tenantId?: string;
}

export interface ArchflowEvent<TData = Record<string, unknown>> {
    envelope: ArchflowEventEnvelope;
    data: TData;
    metadata?: Record<string, unknown>;
}

// ── Connection state ────────────────────────────────────────────────

export type StreamStatus = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed' | 'error';

export interface StreamConfig {
    /** Tenant id used to scope the SSE connection. */
    tenantId: string;
    /** Session id (usually the conversationId). */
    sessionId: string;
    /** Override the base API path. Defaults to `import.meta.env.VITE_API_BASE || '/api'`. */
    baseUrl?: string;
    /** Auto-reconnect with exponential backoff. Default true. */
    autoReconnect?: boolean;
    /** Max reconnect attempts (default 5). */
    maxReconnectAttempts?: number;
}

const DEFAULT_BASE = (import.meta.env.VITE_API_BASE as string | undefined) ?? '/api';

/**
 * EventSource wrapper that knows the ArchFlow envelope format.
 *
 * Browsers do not support setting Authorization headers on EventSource, so we
 * pass the JWT as a `?token=` query parameter when present in localStorage.
 * The backend StreamController accepts both header- and query-based auth.
 *
 * Reconnect strategy: exponential backoff (250ms, 500ms, 1s, 2s, 4s) up to
 * `maxReconnectAttempts`. After that the stream is marked as `error` and the
 * caller can decide whether to retry manually.
 */
export class ArchflowEventStream {
    private source: EventSource | null = null;
    private status: StreamStatus = 'idle';
    private listeners = new Set<(evt: ArchflowEvent) => void>();
    private statusListeners = new Set<(status: StreamStatus) => void>();
    private reconnectAttempts = 0;
    private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    private closedByUser = false;
    /**
     * Tracks already-dispatched event ids to make the stream idempotent
     * across reconnects and React StrictMode double-mounting in dev. The set
     * is bounded to the most recent 1000 ids to keep memory predictable.
     */
    private seenIds: string[] = [];
    private readonly seenLimit = 1000;

    constructor(private config: StreamConfig) {}

    open(): void {
        this.closedByUser = false;
        this.connect();
    }

    close(): void {
        this.closedByUser = true;
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
            this.reconnectTimer = null;
        }
        if (this.source) {
            this.source.close();
            this.source = null;
        }
        this.setStatus('closed');
    }

    onEvent(listener: (evt: ArchflowEvent) => void): () => void {
        this.listeners.add(listener);
        return () => this.listeners.delete(listener);
    }

    onStatus(listener: (status: StreamStatus) => void): () => void {
        this.statusListeners.add(listener);
        listener(this.status);
        return () => this.statusListeners.delete(listener);
    }

    getStatus(): StreamStatus {
        return this.status;
    }

    // ── internals ──────────────────────────────────────────────────

    private connect(): void {
        const { tenantId, sessionId, baseUrl = DEFAULT_BASE } = this.config;
        const token = localStorage.getItem('archflow_token');
        const url = new URL(
            `${baseUrl}/stream/${encodeURIComponent(tenantId)}/${encodeURIComponent(sessionId)}`,
            window.location.origin,
        );
        if (token) url.searchParams.set('token', token);

        this.setStatus(this.reconnectAttempts === 0 ? 'connecting' : 'reconnecting');

        const source = new EventSource(url.toString());
        this.source = source;

        source.onopen = () => {
            this.reconnectAttempts = 0;
            this.setStatus('open');
        };

        source.onmessage = (e) => {
            this.dispatchRaw(e.data);
        };

        // Backend may also emit named events per envelope.type — bind the most
        // common ones so the parser doesn't drop them when only `event:` is sent.
        const namedEvents: ArchflowEventType[] = [
            'delta',
            'message',
            'tool_start',
            'progress',
            'result',
            'tool_error',
            'suspend',
            'form',
            'resume',
            'thinking',
            'heartbeat',
            'error',
            'start',
            'end',
        ];
        for (const name of namedEvents) {
            source.addEventListener(name, (e) => this.dispatchRaw((e as MessageEvent).data));
        }

        source.onerror = () => {
            if (this.closedByUser) return;
            source.close();
            this.source = null;
            if (this.config.autoReconnect === false) {
                this.setStatus('error');
                return;
            }
            const max = this.config.maxReconnectAttempts ?? 5;
            if (this.reconnectAttempts >= max) {
                this.setStatus('error');
                return;
            }
            const delay = Math.min(4000, 250 * 2 ** this.reconnectAttempts);
            this.reconnectAttempts += 1;
            this.setStatus('reconnecting');
            this.reconnectTimer = setTimeout(() => this.connect(), delay);
        };
    }

    private dispatchRaw(raw: string): void {
        if (!raw) return;
        let parsed: ArchflowEvent;
        try {
            parsed = JSON.parse(raw) as ArchflowEvent;
        } catch (err) {
            // Heartbeat frames are sometimes plain text — ignore them.
            return;
        }
        if (!parsed?.envelope?.type) return;
        // Idempotency: skip events we've already dispatched. Required because
        // EventSource auto-reconnect (and React StrictMode double-mount in
        // dev) can re-deliver the same SSE frames.
        const id = parsed.envelope.id;
        if (id) {
            if (this.seenIds.includes(id)) return;
            this.seenIds.push(id);
            if (this.seenIds.length > this.seenLimit) {
                this.seenIds.splice(0, this.seenIds.length - this.seenLimit);
            }
        }
        for (const listener of this.listeners) {
            try {
                listener(parsed);
            } catch (err) {
                console.error('event listener error', err);
            }
        }
    }

    private setStatus(status: StreamStatus): void {
        if (this.status === status) return;
        this.status = status;
        for (const listener of this.statusListeners) listener(status);
    }
}

// ── React hook ──────────────────────────────────────────────────────

export interface UseEventStreamResult {
    events: ArchflowEvent[];
    status: StreamStatus;
    /** Stream instance — useful for advanced consumers (subscribe to one event type). */
    stream: ArchflowEventStream | null;
    /** Reset accumulated events. The stream stays open. */
    clear: () => void;
}

/**
 * React hook that opens an SSE stream for `(tenantId, sessionId)` and returns
 * the accumulated events plus the connection status. The stream auto-closes
 * on unmount.
 *
 * For high-frequency cases (token deltas) prefer subscribing via
 * `result.stream.onEvent(...)` and applying deltas to your own state to
 * avoid re-rendering the entire event list on every chunk.
 */
export function useEventStream(
    tenantId: string | undefined,
    sessionId: string | undefined,
    options?: { autoReconnect?: boolean; maxReconnectAttempts?: number },
): UseEventStreamResult {
    const [events, setEvents] = useState<ArchflowEvent[]>([]);
    const [status, setStatus] = useState<StreamStatus>('idle');
    const streamRef = useRef<ArchflowEventStream | null>(null);

    useEffect(() => {
        if (!tenantId || !sessionId) {
            setStatus('idle');
            return;
        }
        const stream = new ArchflowEventStream({
            tenantId,
            sessionId,
            autoReconnect: options?.autoReconnect ?? true,
            maxReconnectAttempts: options?.maxReconnectAttempts,
        });
        streamRef.current = stream;
        const offEvent = stream.onEvent((evt) => {
            setEvents((prev) => [...prev, evt]);
        });
        const offStatus = stream.onStatus(setStatus);
        stream.open();
        return () => {
            offEvent();
            offStatus();
            stream.close();
            streamRef.current = null;
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [tenantId, sessionId]);

    return {
        events,
        status,
        stream: streamRef.current,
        clear: () => setEvents([]),
    };
}

// ── Type guards (handy for consumers) ──────────────────────────────

export function isChatDelta(
    evt: ArchflowEvent,
): evt is ArchflowEvent<{ content: string; index?: number }> {
    return evt.envelope.domain === 'chat' && evt.envelope.type === 'delta';
}

export function isChatMessage(
    evt: ArchflowEvent,
): evt is ArchflowEvent<{ content: string; role: string; model?: string; totalTokens?: number }> {
    return evt.envelope.domain === 'chat' && evt.envelope.type === 'message';
}

export function isChatStart(evt: ArchflowEvent): boolean {
    return evt.envelope.domain === 'chat' && evt.envelope.type === 'start';
}

export function isChatEnd(
    evt: ArchflowEvent,
): evt is ArchflowEvent<{
    finishReason: string;
    totalTokens?: number;
    promptTokens?: number;
    completionTokens?: number;
}> {
    return evt.envelope.domain === 'chat' && evt.envelope.type === 'end';
}

export function isToolStart(
    evt: ArchflowEvent,
): evt is ArchflowEvent<{
    toolName: string;
    toolCallId?: string;
    input: Record<string, unknown>;
}> {
    return evt.envelope.domain === 'tool' && evt.envelope.type === 'tool_start';
}

export function isToolResult(
    evt: ArchflowEvent,
): evt is ArchflowEvent<{
    toolName: string;
    toolCallId?: string;
    result: unknown;
    durationMs?: number;
}> {
    return evt.envelope.domain === 'tool' && evt.envelope.type === 'result';
}

export function isToolError(
    evt: ArchflowEvent,
): evt is ArchflowEvent<{
    toolName: string;
    toolCallId?: string;
    error: string;
    errorType?: string;
}> {
    return evt.envelope.domain === 'tool' && evt.envelope.type === 'tool_error';
}

export function isToolEnd(
    evt: ArchflowEvent,
): evt is ArchflowEvent<{
    toolName: string;
    toolCallId?: string;
    durationMs?: number;
    success?: boolean;
}> {
    return evt.envelope.domain === 'tool' && evt.envelope.type === 'end';
}

export function isInteractionForm(
    evt: ArchflowEvent,
): evt is ArchflowEvent<{ formId: string; fields: unknown[]; resumeToken?: string }> {
    return evt.envelope.domain === 'interaction' && evt.envelope.type === 'form';
}

export function isHeartbeat(evt: ArchflowEvent): boolean {
    return evt.envelope.domain === 'system' && evt.envelope.type === 'heartbeat';
}
