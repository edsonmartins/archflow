import { useState, useEffect, useRef, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Badge, Box, Button, Center, Group, Loader, Paper, ScrollArea, Stack, Text, TextInput, ThemeIcon } from '@mantine/core';
import { IconMessageChatbot, IconRobot, IconSend } from '@tabler/icons-react';
import EmptyState from '../EmptyState';
import ChatMessage from './ChatMessage';
import type { ToolCallView } from './ToolCallBlock';
import type { Citation } from './CitationList';
import { ApiError } from '../../services/api';
import { conversationApi } from '../../services/conversation-api';
import { usePermissions, type Permission } from '../../lib/permissions';
import { PermissionTooltip } from '../PermissionTooltip';
import type { ConversationMessage, FormDataType } from '../../services/conversation-api';
import {
    ArchflowEventStream,
    isChatDelta,
    isChatEnd,
    isChatError,
    isChatMessage,
    isChatStart,
    isHeartbeat,
    isInteractionCancel,
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
    /** Papel da fala: a resposta de um fluxo retomado pode ser um aviso do sistema, não do assistente. */
    role?: ConversationMessage['role'];
    content: string;
    toolCalls: ToolCallView[];
    citations: Citation[];
    personaId?: string;
    personaIcon?: string;
    finished: boolean;
}

export default function ChatPanel({ conversationId, workflowId, onConversationChanged }: ChatPanelProps) {
    const { t } = useTranslation();
    const can = usePermissions();
    const canExecute = can('workflow:execute');
    const canManageExecutions = can('execution:manage');
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
    // A execução que o formulário pendente continua (GET da conversa ou evento do formulário). Com ela,
    // enviar exige execution:manage, como decidir na fila de aprovações (QA2 RCHFLW-136).
    const [formExecutionId, setFormExecutionId] = useState<string | null>(null);
    // Conversa cancelada: o servidor recusa novos turnos (409), então o campo fica travado e diz por quê,
    // em vez de aceitar o texto e só depois mostrar o erro.
    const [cancelledConversation, setCancelledConversation] = useState(false);
    const [initialLoading, setInitialLoading] = useState(false);
    // Conversa cujo histórico já foi carregado (com sucesso ou não): o stream só abre depois dele.
    const [historyLoadedFor, setHistoryLoadedFor] = useState<string | undefined>(undefined);
    const [streamStatus, setStreamStatus] = useState<StreamStatus>('idle');

    const scrollRef = useRef<HTMLDivElement>(null);
    const inputRef = useRef<HTMLInputElement>(null);
    /**
     * Cross-instance dedupe of event ids. Survives React StrictMode
     * double-mount in dev — same ref is shared by both effect runs.
     */
    const processedEventIds = useRef<Set<string>>(new Set());
    /**
     * Falas deste visualizador ainda com o id local (id local → texto): o servidor publica cada
     * mensagem do usuário no stream com o id gravado (RCHFLW-194), e é por aqui que a fala otimista
     * vira a do servidor em vez de aparecer duas vezes — chegue primeiro o evento ou a resposta HTTP.
     */
    const optimisticUser = useRef<Map<string, string>>(new Map());

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
                setCancelledConversation(conversation.status === 'CANCELLED');
                if (
                    conversation.status === 'SUSPENDED' &&
                    conversation.formData &&
                    conversation.resumeToken
                ) {
                    setSuspendedForm(conversation.formData);
                    setResumeToken(conversation.resumeToken);
                    setFormExecutionId(conversation.executionId ?? null);
                }
            })
            .catch((err) => {
                console.error('Failed to load conversation:', err);
            })
            .finally(() => {
                if (!cancelled) {
                    setInitialLoading(false);
                    setHistoryLoadedFor(conversationId);
                }
            });
        return () => {
            cancelled = true;
        };
    }, [conversationId]);

    // ── SSE subscription (real-time deltas, tool calls, suspends) ──
    useEffect(() => {
        // Só depois do histórico: o setMessages da carga inicial substitui a lista inteira, e uma resposta
        // que chegasse pelo stream antes dele sumiria da tela. Antes isso acontecia por acaso — o stream só
        // abria quando a conversa (e o tenantId dela) carregava.
        if (!conversationId || historyLoadedFor !== conversationId) return;
        // Sem tenantId: o servidor assina o canal do tenant da requisição — o mesmo em que publica a
        // resposta da mensagem enviada por este usuário. A UI mandava o tenant da conversa ou
        // 'default', um tenant inventado que o stream agora recusa (RCHFLW-49).
        const stream = new ArchflowEventStream({
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

            // A fala do usuário (deste ou de outro visualizador): entra na transcrição, sem mexer na
            // resposta em curso. Antes o stream só trazia as do assistente, e a outra aba nunca via o
            // que o cliente escreveu.
            if (isChatMessage(evt) && evt.data.role === 'user') {
                const serverId = evt.data.messageId ?? evt.envelope.id;
                const content = evt.data.content ?? '';
                setMessages((m) => {
                    if (m.some((existing) => existing.id === serverId)) return m;
                    const local = [...optimisticUser.current.entries()]
                        .find(([, text]) => text === content)?.[0];
                    if (local) {
                        optimisticUser.current.delete(local);
                        return m.map((existing) => (existing.id === local ? { ...existing, id: serverId } : existing));
                    }
                    return [...m, { id: serverId, role: 'user', content, timestamp: evt.envelope.timestamp }];
                });
                notifyChanged();
                return;
            }

            if (isChatMessage(evt)) {
                // Full message replaces any in-flight delta accumulation. O id gravado no servidor
                // (messageId) vence o do evento: é por ele que a lista recarregada reconhece a fala.
                setPending((prev) => ({
                    id: evt.data.messageId ?? prev?.id ?? evt.envelope.id,
                    role: evt.data.role === 'system' ? 'system' : 'assistant',
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
                        role: prev.role ?? 'assistant',
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

            // Falha da resposta: sem isto o "digitando" ficava para sempre e o campo, travado.
            if (isChatError(evt)) {
                setPending(null);
                setSending(false);
                setMessages((m) => [
                    ...m,
                    {
                        id: evt.envelope.id,
                        role: 'system',
                        content: evt.data.message ?? '',
                        timestamp: evt.envelope.timestamp,
                    },
                ]);
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
                // O servidor manda título, descrição e rótulos nos dados do evento (como o
                // InteractionEvent.form); os metadados continuam aceitos, como antes.
                const form: FormDataType = {
                    id: evt.data.formId ?? `form_${evt.envelope.id}`,
                    title: evt.data.title ?? (evt.metadata?.title as string) ?? 'Input required',
                    description: evt.data.description ?? (evt.metadata?.description as string | undefined),
                    fields,
                    submitLabel: evt.data.submitLabel,
                    cancelLabel: evt.data.cancelLabel,
                };
                setSuspendedForm(form);
                setResumeToken((evt.data.resumeToken as string | undefined) ?? null);
                setFormExecutionId((evt.data.executionId as string | undefined) ?? null);
                notifyChanged();
                return;
            }

            // O servidor retirou o formulário (conversa cancelada, aprovação recusada ou expirada): sem
            // isto o formulário ficava na tela e o campo livre travado num chat que já não esperava nada.
            if (isInteractionCancel(evt)) {
                setSuspendedForm(null);
                setResumeToken(null);
                setFormExecutionId(null);
                notifyChanged();
                // O mesmo evento vale para o formulário retirado (a conversa segue aberta) e para a conversa
                // cancelada (não recebe mais nada): só o registro diz qual dos dois.
                conversationApi.getConversation(conversationId)
                    .then((c) => { if (c.status === 'CANCELLED') setCancelledConversation(true); })
                    .catch((err) => console.error('Failed to refresh conversation status:', err));
            }
        });

        stream.open();
        return () => {
            offStatus();
            offEvent();
            stream.close();
        };
    }, [conversationId, historyLoadedFor, notifyChanged]);

    useEffect(() => {
        scrollToBottom();
    }, [messages, pending, suspendedForm, scrollToBottom]);

    const handleSend = useCallback(async () => {
        const text = input.trim();
        // O Enter também chega aqui: sem workflow:execute o servidor recusaria a mensagem (403).
        if (!text || sending || !conversationId || !canExecute) return;

        const userMessage: ConversationMessage = {
            id: crypto.randomUUID(),
            role: 'user',
            content: text,
            timestamp: new Date().toISOString(),
        };

        optimisticUser.current.set(userMessage.id, text);
        setMessages((prev) => [...prev, userMessage]);
        setInput('');
        setSending(true);

        try {
            const messageId = (await conversationApi.sendMessage(conversationId, text))?.messageId;
            // A fala otimista passa a ter o id gravado — a não ser que o evento do stream já a tenha casado.
            if (messageId && optimisticUser.current.delete(userMessage.id)) {
                setMessages((prev) => (prev.some((m) => m.id === messageId)
                    ? prev.filter((m) => m.id !== userMessage.id)
                    : prev.map((m) => (m.id === userMessage.id ? { ...m, id: messageId } : m))));
            }
            // A resposta chega pelo SSE; o registro, porém, já conta a mensagem do usuário.
            notifyChanged();
        } catch (err) {
            console.error('Failed to send message:', err);
            optimisticUser.current.delete(userMessage.id);
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
    }, [input, sending, conversationId, canExecute]);

    const handleFormSubmit = useCallback(
        async (data: Record<string, unknown>) => {
            if (!resumeToken) return;
            const form = suspendedForm;
            setSending(true);
            setSuspendedForm(null);
            try {
                const response = await conversationApi.resumeConversation(resumeToken, data);
                // A resposta do fluxo (ou do agente) chega pelo stream e pode chegar antes desta
                // resposta HTTP: as falas que vieram por lá e o servidor ainda não listou ficam. As do
                // usuário ficam de fora — as otimistas têm id local e já estão na lista do servidor.
                setMessages((prev) => {
                    const known = new Set(response.messages.map((m) => m.id));
                    const streamed = prev.filter((m) => m.role !== 'user' && !known.has(m.id));
                    return [...response.messages, ...streamed];
                });
                setResumeToken(null);
                notifyChanged();
                // Sem setSending(false): o "digitando" segue até o chat/end ou chat/error da resposta.
                return;
            } catch (err) {
                console.error('Failed to resume conversation:', err);
                // Recusado (400/409), o formulário só volta se a conversa ainda o espera: quando a execução
                // já terminou (aprovação recusada, expirada, parada), o servidor o retira e devolver o
                // formulário travava o chat num envio que nunca seria aceito (RCHFLW-194). O aviso é a
                // frase do servidor, que diz o motivo; o texto genérico só quando não há uma.
                const stillWaiting = conversationId
                    ? await conversationApi.getConversation(conversationId)
                        .then((c) => c.status === 'SUSPENDED' && !!c.resumeToken && c.resumeToken === resumeToken)
                        // Sem a resposta do GET não há como saber: fica como era, com o formulário.
                        .catch(() => true)
                    : true;
                if (stillWaiting) {
                    setSuspendedForm(form);
                } else {
                    setResumeToken(null);
                    setFormExecutionId(null);
                    notifyChanged();
                }
                setMessages((prev) => [
                    ...prev,
                    {
                        id: crypto.randomUUID(),
                        role: 'system',
                        content: err instanceof ApiError && err.message ? err.message : t('chat.submitFormFailed'),
                        timestamp: new Date().toISOString(),
                    },
                ]);
                setSending(false);
            }
        },
        // eslint-disable-next-line react-hooks/exhaustive-deps
        [resumeToken, suspendedForm, conversationId],
    );

    const handleFormCancel = useCallback(async () => {
        if (!conversationId) return;
        setSuspendedForm(null);
        setResumeToken(null);
        try {
            await conversationApi.cancelConversation(conversationId);
            setCancelledConversation(true);
            notifyChanged();
            // O aviso de cancelamento é gravado e publicado pelo servidor (os outros visualizadores o recebem
            // pelo stream); relida, a transcrição o traz com o id gravado, e o evento que chegar depois não o
            // repete. Sem a releitura, fica o aviso local de antes.
            try {
                setMessages(await conversationApi.getMessages(conversationId));
            } catch (reloadErr) {
                console.error('Failed to reload messages after cancel:', reloadErr);
                setMessages((prev) => [
                    ...prev,
                    {
                        id: crypto.randomUUID(),
                        role: 'system',
                        content: t('chat.conversationCancelled'),
                        timestamp: new Date().toISOString(),
                    },
                ]);
            }
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
    const locked = sending || !!suspendedForm || cancelledConversation;
    // O que o servidor recusaria (403): toda escrita em /api/conversations pede workflow:execute; o envio do
    // formulário ligado a uma execução pede também execution:manage (SpringConversationController.resume).
    const sendMissing: Permission | null = canExecute ? null : 'workflow:execute';
    const formSubmitMissing: Permission | null = sendMissing
        ?? (formExecutionId && !canManageExecutions ? 'execution:manage' : null);

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
                        formSubmitMissing={formSubmitMissing}
                        formCancelMissing={sendMissing}
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
                        formSubmitMissing={formSubmitMissing}
                        formCancelMissing={sendMissing}
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
                        placeholder={cancelledConversation
                            ? t('chat.cancelledPlaceholder')
                            : suspendedForm ? t('chat.awaitingForm') : t('chat.placeholder')}
                        aria-label={t('chat.placeholder')}
                        size="sm"
                        style={{ flex: 1 }}
                        value={input}
                        onChange={(e) => setInput(e.currentTarget.value)}
                        onKeyDown={handleKeyDown}
                        disabled={locked || !canExecute}
                    />
                    <PermissionTooltip missing={sendMissing}>
                        <Button
                            size="sm"
                            radius="xl"
                            color="teal"
                            onClick={handleSend}
                            disabled={!input.trim() || locked || !!sendMissing}
                            leftSection={<IconSend size={15} />}
                            data-testid="chat-send"
                        >
                            {t('chat.send')}
                        </Button>
                    </PermissionTooltip>
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
