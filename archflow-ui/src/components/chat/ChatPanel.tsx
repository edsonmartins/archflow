import { useState, useEffect, useRef, useCallback } from 'react';
import { Stack, TextInput, Button, Group, ScrollArea, Text, Loader, Center } from '@mantine/core';
import { IconSend } from '@tabler/icons-react';
import ChatMessage from './ChatMessage';
import { conversationApi } from '../../services/conversation-api';
import type { ConversationMessage, FormDataType } from '../../services/conversation-api';

interface ChatPanelProps {
    conversationId?: string;
    workflowId?: string;
}

export default function ChatPanel({ conversationId, workflowId }: ChatPanelProps) {
    const [messages, setMessages] = useState<ConversationMessage[]>([]);
    const [input, setInput] = useState('');
    const [loading, setLoading] = useState(false);
    const [suspendedForm, setSuspendedForm] = useState<FormDataType | null>(null);
    const [resumeToken, setResumeToken] = useState<string | null>(null);
    const [initialLoading, setInitialLoading] = useState(false);

    const scrollRef = useRef<HTMLDivElement>(null);
    const inputRef = useRef<HTMLInputElement>(null);

    const scrollToBottom = useCallback(() => {
        setTimeout(() => {
            if (scrollRef.current) {
                scrollRef.current.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
            }
        }, 50);
    }, []);

    // Load existing conversation messages
    useEffect(() => {
        if (!conversationId) return;

        const loadConversation = async () => {
            setInitialLoading(true);
            try {
                const [conversation, msgs] = await Promise.all([
                    conversationApi.getConversation(conversationId),
                    conversationApi.getMessages(conversationId),
                ]);
                setMessages(msgs);

                if (conversation.status === 'SUSPENDED' && conversation.formData && conversation.resumeToken) {
                    setSuspendedForm(conversation.formData);
                    setResumeToken(conversation.resumeToken);
                }
            } catch (err) {
                console.error('Failed to load conversation:', err);
            } finally {
                setInitialLoading(false);
            }
        };

        loadConversation();
    }, [conversationId]);

    useEffect(() => {
        scrollToBottom();
    }, [messages, suspendedForm, scrollToBottom]);

    const handleSend = useCallback(async () => {
        const text = input.trim();
        if (!text || loading) return;

        const userMessage: ConversationMessage = {
            id: crypto.randomUUID(),
            role: 'user',
            content: text,
            timestamp: new Date().toISOString(),
        };

        setMessages((prev) => [...prev, userMessage]);
        setInput('');
        setLoading(true);

        try {
            if (conversationId) {
                const msgs = await conversationApi.getMessages(conversationId);
                setMessages(msgs);

                const conversation = await conversationApi.getConversation(conversationId);
                if (conversation.status === 'SUSPENDED' && conversation.formData && conversation.resumeToken) {
                    setSuspendedForm(conversation.formData);
                    setResumeToken(conversation.resumeToken);
                }
            }
        } catch (err) {
            console.error('Failed to send message:', err);
            const errorMessage: ConversationMessage = {
                id: crypto.randomUUID(),
                role: 'system',
                content: 'Failed to send message. Please try again.',
                timestamp: new Date().toISOString(),
            };
            setMessages((prev) => [...prev, errorMessage]);
        } finally {
            setLoading(false);
            inputRef.current?.focus();
        }
    }, [input, loading, conversationId]);

    const handleFormSubmit = useCallback(async (data: Record<string, unknown>) => {
        if (!resumeToken) return;

        setLoading(true);
        setSuspendedForm(null);

        try {
            const response = await conversationApi.resumeConversation(resumeToken, data);
            setMessages(response.messages);
            setResumeToken(null);
        } catch (err) {
            console.error('Failed to resume conversation:', err);
            const errorMessage: ConversationMessage = {
                id: crypto.randomUUID(),
                role: 'system',
                content: 'Failed to submit form. Please try again.',
                timestamp: new Date().toISOString(),
            };
            setMessages((prev) => [...prev, errorMessage]);
        } finally {
            setLoading(false);
        }
    }, [resumeToken]);

    const handleFormCancel = useCallback(async () => {
        if (!conversationId) return;

        setSuspendedForm(null);
        setResumeToken(null);

        try {
            await conversationApi.cancelConversation(conversationId);
            const systemMessage: ConversationMessage = {
                id: crypto.randomUUID(),
                role: 'system',
                content: 'Conversation cancelled.',
                timestamp: new Date().toISOString(),
            };
            setMessages((prev) => [...prev, systemMessage]);
        } catch (err) {
            console.error('Failed to cancel conversation:', err);
        }
    }, [conversationId]);

    const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            handleSend();
        }
    }, [handleSend]);

    if (initialLoading) {
        return (
            <Center h="100%">
                <Loader size="sm" />
            </Center>
        );
    }

    return (
        <Stack h="100%" gap={0}>
            {/* Messages area */}
            <ScrollArea style={{ flex: 1 }} px="sm" py="xs" viewportRef={scrollRef}>
                {messages.length === 0 && !suspendedForm && (
                    <Center h={200}>
                        <Text size="sm" c="dimmed">
                            {workflowId ? 'Start a conversation with this workflow.' : 'No messages yet.'}
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
                        onFormSubmit={msg.formData ? handleFormSubmit : undefined}
                        onFormCancel={msg.formData ? handleFormCancel : undefined}
                    />
                ))}

                {suspendedForm && (
                    <ChatMessage
                        role="assistant"
                        content=""
                        formData={suspendedForm}
                        onFormSubmit={handleFormSubmit}
                        onFormCancel={handleFormCancel}
                    />
                )}

                {loading && (
                    <Group justify="flex-start" my="xs">
                        <Loader size="xs" type="dots" />
                    </Group>
                )}
            </ScrollArea>

            {/* Input bar */}
            <Group gap="xs" p="sm" style={{ borderTop: '1px solid var(--mantine-color-gray-3)' }}>
                <TextInput
                    ref={inputRef}
                    placeholder="Type a message..."
                    size="sm"
                    style={{ flex: 1 }}
                    value={input}
                    onChange={(e) => setInput(e.currentTarget.value)}
                    onKeyDown={handleKeyDown}
                    disabled={loading || !!suspendedForm}
                />
                <Button
                    size="sm"
                    onClick={handleSend}
                    disabled={!input.trim() || loading || !!suspendedForm}
                    leftSection={<IconSend size={16} />}
                >
                    Send
                </Button>
            </Group>
        </Stack>
    );
}
