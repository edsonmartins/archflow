import { useState, useEffect, useRef, useCallback } from 'react';
import { Badge, Button, Center, Group, Loader, ScrollArea, Stack, Text, TextInput } from '@mantine/core';
import { IconSend } from '@tabler/icons-react';
import ChatMessage from './ChatMessage';
import type { ToolCallView } from './ToolCallBlock';
import type { Citation } from './CitationList';
import { conversationApi } from '../../services/conversation-api';
import type { ConversationMessage, FormDataType } from '../../services/conversation-api';
import {
    ArchflowEventStream,
    isChatDelta,
    isChatEnd,
    isChatMessage,
    isChatStart,
    isHeartbeat,
    isInteractionForm,
    isToolEnd,
    isToolError,
    isToolResult,
    isToolStart,
    type StreamStatus,
} from '../../services/event-stream';

interface ChatPanelProps {
    /** Conversation id (also used as the SSE session id). */
    conversationId?: string;
    /** Workflow id this conversation runs against. */
    workflowId?: string;
    /** Tenant id used for SSE scoping. Falls back to "default" when undefined. */
    tenantId?: string;
}

interface PendingAssistant {
    id: string;
    content: string;
    toolCalls: ToolCallView[];
    citations: Citation[];
    personaId?: string;
    personaIcon?: string;
    finished: boolean;
}

export default function ChatPanel({ conversationId, workflowId, tenantId }: ChatPanelProps) {
    const [messages, setMessages] = useState<ConversationMessage[]>([]);
    const [pending, setPending] = useState<PendingAssistant | null>(null);
    const [input, setInput] = useState('');
    const [sending, setSending] = useState(false);
    const [suspendedForm, setSuspendedForm] = useState<FormDataType | null>(null);
    const [resumeToken, setResumeToken] = useState<string | null>(null);
    const [initialLoading, setInitialLoading] = useState(false);
    const [streamStatus, setStreamStatus] = useState<StreamStatus>('idle');

    const scrollRef = useRef<HTMLDivElement>(null);
    const inputRef = useRef<HTMLInputElement>(null);
    /**
     * Cross-instance dedupe of event ids. Survives React StrictMode
     * double-mount in dev — same ref is shared by both effect runs.
     */
    const processedEventIds = useRef<Set<string>>(new Set());

    const scrollToBottom = useCallback(() => {
        setTimeout(() => {
            if (scrollRef.current) {
                scrollRef.current.scrollTo({
                    top: scrollRef.current.scrollHeight,
                    behavior: 'smooth',
                });
            }
        }, 50);
    }, []);

    // ── Initial REST load (full history) ────────────────────────────
    useEffect(() => {
        if (!conversationId) return;
        let cancelled = false;
        setInitialLoading(true);
        Promise.all([
            conversationApi.getConversation(conversationId),
            conversationApi.getMessages(conversationId),
        ])
            .then(([conversation, msgs]) => {
                if (cancelled) return;
                setMessages(msgs);
                if (
                    conversation.status === 'SUSPENDED' &&
                    conversation.formData &&
                    conversation.resumeToken
                ) {
                    setSuspendedForm(conversation.formData);
                    setResumeToken(conversation.resumeToken);
                }
            })
            .catch((err) => {
                console.error('Failed to load conversation:', err);
            })
            .finally(() => {
                if (!cancelled) setInitialLoading(false);
            });
        return () => {
            cancelled = true;
        };
    }, [conversationId]);

    // ── SSE subscription (real-time deltas, tool calls, suspends) ──
    useEffect(() => {
        if (!conversationId) return;
        const stream = new ArchflowEventStream({
            tenantId: tenantId ?? 'default',
            sessionId: conversationId,
            autoReconnect: true,
        });

        const offStatus = stream.onStatus(setStreamStatus);

        const offEvent = stream.onEvent((evt) => {
            if (isHeartbeat(evt)) return;
            // Dedupe across reconnects + StrictMode double-mount.
            const eid = evt.envelope.id;
            if (eid) {
                if (processedEventIds.current.has(eid)) return;
                processedEventIds.current.add(eid);
            }

            // Chat lifecycle
            if (isChatStart(evt)) {
                setPending({
                    id: evt.envelope.id,
                    content: '',
                    toolCalls: [],
                    citations: [],
                    finished: false,
                });
                return;
            }

            if (isChatDelta(evt)) {
                setPending((prev) => {
                    const base: PendingAssistant = prev ?? {
                        id: evt.envelope.id,
                        content: '',
                        toolCalls: [],
                        citations: [],
                        finished: false,
                    };
                    return { ...base, content: base.content + (evt.data.content ?? '') };
                });
                return;
            }

            if (isChatMessage(evt)) {
                // Full message replaces any in-flight delta accumulation.
                setPending((prev) => ({
                    id: prev?.id ?? evt.envelope.id,
                    content: evt.data.content ?? '',
                    toolCalls: prev?.toolCalls ?? [],
                    citations: prev?.citations ?? [],
                    personaId: prev?.personaId,
                    personaIcon: prev?.personaIcon,
                    finished: false,
                }));
                return;
            }

            if (isChatEnd(evt)) {
                setPending((prev) => {
                    if (!prev) return null;
                    const finalMessage: ConversationMessage = {
                        id: prev.id,
                        role: 'assistant',
                        content: prev.content,
                        timestamp: evt.envelope.timestamp,
                        toolCalls: prev.toolCalls,
                        citations: prev.citations,
                        personaId: prev.personaId,
                        personaIcon: prev.personaIcon,
                    };
                    setMessages((m) =>
                        m.some((existing) => existing.id === finalMessage.id)
                            ? m
                            : [...m, finalMessage],
                    );
                    return null;
                });
                setSending(false);
                return;
            }

            // Tool lifecycle
            if (isToolStart(evt)) {
                const callId =
                    (evt.data.toolCallId as string | undefined) ?? `tc_${evt.envelope.id}`;
                setPending((prev) => {
                    const base: PendingAssistant = prev ?? {
                        id: evt.envelope.id,
                        content: '',
                        toolCalls: [],
                        citations: [],
                        finished: false,
                    };
                    return {
                        ...base,
                        toolCalls: [
                            ...base.toolCalls,
                            {
                                id: callId,
                                name: evt.data.toolName,
                                status: 'running',
                                input: (evt.data.input as Record<string, unknown>) ?? null,
                            },
                        ],
                    };
                });
                return;
            }

            if (isToolResult(evt)) {
                const callId = (evt.data.toolCallId as string | undefined) ?? null;
                setPending((prev) => {
                    if (!prev) return prev;
                    return {
                        ...prev,
                        toolCalls: prev.toolCalls.map((tc) =>
                            matchesToolCall(tc, callId, evt.data.toolName)
                                ? {
                                      ...tc,
                                      status: 'success',
                                      output: evt.data.result,
                                      durationMs: (evt.data.durationMs as number) ?? tc.durationMs,
                                  }
                                : tc,
                        ),
                    };
                });
                return;
            }

            if (isToolError(evt)) {
                const callId = (evt.data.toolCallId as string | undefined) ?? null;
                setPending((prev) => {
                    if (!prev) return prev;
                    return {
                        ...prev,
                        toolCalls: prev.toolCalls.map((tc) =>
                            matchesToolCall(tc, callId, evt.data.toolName)
                                ? { ...tc, status: 'error', error: evt.data.error }
                                : tc,
                        ),
                    };
                });
                return;
            }

            if (isToolEnd(evt)) {
                const callId = (evt.data.toolCallId as string | undefined) ?? null;
                setPending((prev) => {
                    if (!prev) return prev;
                    return {
                        ...prev,
                        toolCalls: prev.toolCalls.map((tc) =>
                            matchesToolCall(tc, callId, evt.data.toolName)
                                ? {
                                      ...tc,
                                      durationMs:
                                          (evt.data.durationMs as number) ?? tc.durationMs,
                                  }
                                : tc,
                        ),
                    };
                });
                return;
            }

            // Interaction (suspended form)
            if (isInteractionForm(evt)) {
                const fields = (evt.data.fields as unknown as FormDataType['fields']) ?? [];
                const form: FormDataType = {
                    id: evt.data.formId ?? `form_${evt.envelope.id}`,
                    title: (evt.metadata?.title as string) ?? 'Input required',
                    description: evt.metadata?.description as string | undefined,
                    fields,
                };
                setSuspendedForm(form);
                setResumeToken((evt.data.resumeToken as string | undefined) ?? null);
            }
        });

        stream.open();
        return () => {
            offStatus();
            offEvent();
            stream.close();
        };
    }, [conversationId, tenantId]);

    useEffect(() => {
        scrollToBottom();
    }, [messages, pending, suspendedForm, scrollToBottom]);

    const handleSend = useCallback(async () => {
        const text = input.trim();
        if (!text || sending || !conversationId) return;

        const userMessage: ConversationMessage = {
            id: crypto.randomUUID(),
            role: 'user',
            content: text,
            timestamp: new Date().toISOString(),
        };

        setMessages((prev) => [...prev, userMessage]);
        setInput('');
        setSending(true);

        try {
            await conversationApi.sendMessage(conversationId, text);
            // Streaming response will arrive via SSE — no need to refetch.
        } catch (err) {
            console.error('Failed to send message:', err);
            setMessages((prev) => [
                ...prev,
                {
                    id: crypto.randomUUID(),
                    role: 'system',
                    content: 'Failed to send message. Please try again.',
                    timestamp: new Date().toISOString(),
                },
            ]);
            setSending(false);
        } finally {
            inputRef.current?.focus();
        }
    }, [input, sending, conversationId]);

    const handleFormSubmit = useCallback(
        async (data: Record<string, unknown>) => {
            if (!resumeToken) return;
            setSending(true);
            setSuspendedForm(null);
            try {
                const response = await conversationApi.resumeConversation(resumeToken, data);
                setMessages(response.messages);
                setResumeToken(null);
            } catch (err) {
                console.error('Failed to resume conversation:', err);
                setMessages((prev) => [
                    ...prev,
                    {
                        id: crypto.randomUUID(),
                        role: 'system',
                        content: 'Failed to submit form. Please try again.',
                        timestamp: new Date().toISOString(),
                    },
                ]);
            } finally {
                setSending(false);
            }
        },
        [resumeToken],
    );

    const handleFormCancel = useCallback(async () => {
        if (!conversationId) return;
        setSuspendedForm(null);
        setResumeToken(null);
        try {
            await conversationApi.cancelConversation(conversationId);
            setMessages((prev) => [
                ...prev,
                {
                    id: crypto.randomUUID(),
                    role: 'system',
                    content: 'Conversation cancelled.',
                    timestamp: new Date().toISOString(),
                },
            ]);
        } catch (err) {
            console.error('Failed to cancel conversation:', err);
        }
    }, [conversationId]);

    const handleKeyDown = useCallback(
        (e: React.KeyboardEvent) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                handleSend();
            }
        },
        [handleSend],
    );

    if (initialLoading) {
        return (
            <Center h="100%">
                <Loader size="sm" />
            </Center>
        );
    }

    return (
        <Stack h="100%" gap={0}>
            {/* Stream status indicator */}
            <Group justify="flex-end" px="sm" py={4}>
                <StreamBadge status={streamStatus} />
            </Group>

            {/* Messages area */}
            <ScrollArea style={{ flex: 1 }} px="sm" py="xs" viewportRef={scrollRef}>
                {messages.length === 0 && !pending && !suspendedForm && (
                    <Center h={200}>
                        <Text size="sm" c="dimmed">
                            {workflowId
                                ? 'Start a conversation with this workflow.'
                                : 'No messages yet.'}
                        </Text>
                    </Center>
                )}

                {messages.map((msg) => (
                    <ChatMessage
                        key={msg.id}
                        role={msg.role}
                        content={msg.content}
                        timestamp={msg.timestamp}
                        formData={msg.formData}
                        toolCalls={msg.toolCalls}
                        citations={msg.citations}
                        personaId={msg.personaId}
                        personaIcon={msg.personaIcon}
                        onFormSubmit={msg.formData ? handleFormSubmit : undefined}
                        onFormCancel={msg.formData ? handleFormCancel : undefined}
                    />
                ))}

                {pending && (
                    <ChatMessage
                        key={pending.id}
                        role="assistant"
                        content={pending.content}
                        toolCalls={pending.toolCalls}
                        citations={pending.citations}
                        personaId={pending.personaId}
                        personaIcon={pending.personaIcon}
                        streaming
                    />
                )}

                {suspendedForm && (
                    <ChatMessage
                        role="assistant"
                        content=""
                        formData={suspendedForm}
                        onFormSubmit={handleFormSubmit}
                        onFormCancel={handleFormCancel}
                    />
                )}

                {sending && !pending && (
                    <Group justify="flex-start" my="xs">
                        <Loader size="xs" type="dots" />
                    </Group>
                )}
            </ScrollArea>

            {/* Input bar */}
            <Group
                gap="xs"
                p="sm"
                style={{ borderTop: '1px solid var(--mantine-color-gray-3)' }}
            >
                <TextInput
                    ref={inputRef}
                    placeholder="Type a message..."
                    size="sm"
                    style={{ flex: 1 }}
                    value={input}
                    onChange={(e) => setInput(e.currentTarget.value)}
                    onKeyDown={handleKeyDown}
                    disabled={sending || !!suspendedForm}
                />
                <Button
                    size="sm"
                    onClick={handleSend}
                    disabled={!input.trim() || sending || !!suspendedForm}
                    leftSection={<IconSend size={16} />}
                >
                    Send
                </Button>
            </Group>
        </Stack>
    );
}

// ── helpers ────────────────────────────────────────────────────────

function matchesToolCall(
    tc: ToolCallView,
    callId: string | null,
    name: string,
): boolean {
    if (callId && tc.id === callId) return true;
    // Fallback when toolCallId isn't propagated: match the most recent
    // running call with the same name.
    return tc.name === name && tc.status === 'running';
}

function StreamBadge({ status }: { status: StreamStatus }) {
    const map: Record<StreamStatus, { color: string; label: string }> = {
        idle: { color: 'gray', label: 'idle' },
        connecting: { color: 'yellow', label: 'connecting' },
        open: { color: 'teal', label: 'live' },
        reconnecting: { color: 'yellow', label: 'reconnecting' },
        closed: { color: 'gray', label: 'closed' },
        error: { color: 'red', label: 'error' },
    };
    const { color, label } = map[status];
    return (
        <Badge size="xs" variant="dot" color={color}>
            {label}
        </Badge>
    );
}
