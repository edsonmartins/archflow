import { useState, useEffect, useRef, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Badge, Box, Button, Center, Group, Loader, Paper, ScrollArea, Stack, Text, TextInput, ThemeIcon } from '@mantine/core';
import { IconMessageChatbot, IconRobot, IconSend } from '@tabler/icons-react';
import EmptyState from '../EmptyState';
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
    /**
     * Chamado quando o registro da conversa mudou no servidor: mensagem do usuário aceita, resposta
     * do agente concluída (chat/end), formulário de suspensão recebido, retomada ou cancelamento.
     *
     * <p>A transcrição vive aqui, alimentada pelo stream, mas contagem, última mensagem e status são
     * do registro que a página dona busca — sem este aviso os painéis de detalhes ficavam parados
     * até o "Atualizar" (RCHFLW-81). Só avisa, não manda dados: quem calcula é o servidor, e o
     * chat/end só é publicado depois de a resposta ser gravada, então um GET logo em seguida já a
     * enxerga.</p>
     */
    onConversationChanged?: () => void;
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

export default function ChatPanel({ conversationId, workflowId, tenantId, onConversationChanged }: ChatPanelProps) {
    const { t } = useTranslation();
    // Por ref: o callback do pai muda de identidade a cada render e, como dependência do efeito do
    // SSE, faria o stream fechar e reabrir — perdendo eventos no meio de uma resposta.
    const changedRef = useRef(onConversationChanged);
    changedRef.current = onConversationChanged;
    const notifyChanged = useCallback(() => changedRef.current?.(), []);
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
                notifyChanged();
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
                notifyChanged();
            }
        });

        stream.open();
        return () => {
            offStatus();
            offEvent();
            stream.close();
        };
    }, [conversationId, tenantId, notifyChanged]);

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
            // A resposta chega pelo SSE; o registro, porém, já conta a mensagem do usuário.
            notifyChanged();
        } catch (err) {
            console.error('Failed to send message:', err);
            setMessages((prev) => [
                ...prev,
                {
                    id: crypto.randomUUID(),
                    role: 'system',
                    content: t('chat.sendFailed'),
                    timestamp: new Date().toISOString(),
                },
            ]);
            setSending(false);
        } finally {
            inputRef.current?.focus();
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
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
                notifyChanged();
            } catch (err) {
                console.error('Failed to resume conversation:', err);
                setMessages((prev) => [
                    ...prev,
                    {
                        id: crypto.randomUUID(),
                        role: 'system',
                        content: t('chat.submitFormFailed'),
                        timestamp: new Date().toISOString(),
                    },
                ]);
            } finally {
                setSending(false);
            }
        },
        // eslint-disable-next-line react-hooks/exhaustive-deps
        [resumeToken],
    );

    const handleFormCancel = useCallback(async () => {
        if (!conversationId) return;
        setSuspendedForm(null);
        setResumeToken(null);
        try {
            await conversationApi.cancelConversation(conversationId);
            notifyChanged();
            setMessages((prev) => [
                ...prev,
                {
                    id: crypto.randomUUID(),
                    role: 'system',
                    content: t('chat.conversationCancelled'),
                    timestamp: new Date().toISOString(),
                },
            ]);
        } catch (err) {
            console.error('Failed to cancel conversation:', err);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
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
            <Center h="100%" mih={240}>
                <Loader size="sm" color="teal" />
            </Center>
        );
    }

    const empty = messages.length === 0 && !pending && !suspendedForm;
    const locked = sending || !!suspendedForm;

    return (
        <Stack h="100%" gap={0}>
            {/* Header: transcript label + live stream status */}
            <Group
                justify="space-between"
                px="md"
                py="xs"
                wrap="nowrap"
                style={{ borderBottom: '1px solid var(--mantine-color-default-border)' }}
            >
                <Group gap={8} wrap="nowrap">
                    <IconMessageChatbot size={16} color="var(--mantine-color-teal-text)" />
                    <Text size="sm" fw={600}>{t('chat.transcript')}</Text>
                    {messages.length > 0 && (
                        <Badge size="xs" variant="light" color="gray" radius="sm">
                            {messages.length}
                        </Badge>
                    )}
                </Group>
                <StreamBadge status={streamStatus} t={t} />
            </Group>

            {/* Messages area */}
            <ScrollArea
                style={{ flex: 1, background: 'var(--bg3)' }}
                px="md"
                py="xs"
                viewportRef={scrollRef}
                role="log"
                aria-live="polite"
                aria-relevant="additions"
                aria-label={t('chat.messagesRegion')}
            >
                {empty && (
                    <EmptyState
                        compact
                        icon={<IconMessageChatbot />}
                        accent="teal"
                        title={workflowId ? t('chat.startWithWorkflow') : t('chat.noMessages')}
                        description={t('chat.emptyHint')}
                    />
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
                    <Group justify="flex-start" gap="sm" my="md" wrap="nowrap">
                        <ThemeIcon size={32} radius="xl" variant="gradient"
                                   gradient={{ from: 'teal.4', to: 'cyan.7', deg: 135 }} aria-hidden>
                            <IconRobot size={17} />
                        </ThemeIcon>
                        <Paper
                            px="md"
                            py={10}
                            style={{
                                borderRadius: '16px 16px 16px 4px',
                                border: '1px solid var(--mantine-color-default-border)',
                                background: 'var(--mantine-color-body)',
                            }}
                        >
                            <Loader size="xs" type="dots" color="teal" aria-label={t('chat.thinking')} />
                        </Paper>
                    </Group>
                )}
            </ScrollArea>

            {/* Composer */}
            <Box p="sm" style={{ borderTop: '1px solid var(--mantine-color-default-border)' }}>
                <Group
                    gap="xs"
                    wrap="nowrap"
                    pl="md"
                    pr={6}
                    py={6}
                    style={{
                        border: '1px solid var(--mantine-color-default-border)',
                        borderRadius: 999,
                        background: locked ? 'var(--mantine-color-default-hover)' : 'var(--mantine-color-body)',
                    }}
                >
                    <TextInput
                        ref={inputRef}
                        variant="unstyled"
                        placeholder={t('chat.placeholder')}
                        aria-label={t('chat.placeholder')}
                        size="sm"
                        style={{ flex: 1 }}
                        value={input}
                        onChange={(e) => setInput(e.currentTarget.value)}
                        onKeyDown={handleKeyDown}
                        disabled={locked}
                    />
                    <Button
                        size="sm"
                        radius="xl"
                        color="teal"
                        onClick={handleSend}
                        disabled={!input.trim() || locked}
                        leftSection={<IconSend size={15} />}
                    >
                        {t('chat.send')}
                    </Button>
                </Group>
            </Box>
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

function StreamBadge({ status, t }: { status: StreamStatus; t: (k: string) => string }) {
    const colorMap: Record<StreamStatus, string> = {
        idle: 'gray',
        connecting: 'yellow',
        open: 'teal',
        reconnecting: 'yellow',
        closed: 'gray',
        error: 'red',
    };
    return (
        <Badge size="sm" variant="dot" color={colorMap[status]} radius="sm" aria-live="polite">
            {t(`chat.stream.${status}`)}
        </Badge>
    );
}
