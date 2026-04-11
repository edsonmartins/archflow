import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
    ActionIcon,
    Alert,
    Badge,
    Group,
    Loader,
    Paper,
    ScrollArea,
    Stack,
    Text,
    Title,
    Tooltip,
} from '@mantine/core';
import { IconAlertCircle, IconArrowLeft, IconRefresh } from '@tabler/icons-react';
import ChatPanel from '../components/chat/ChatPanel';
import { conversationApi, type Conversation } from '../services/conversation-api';

/**
 * Conversation detail page with split layout.
 *
 * Left pane: live chat surface (ChatPanel) consuming SSE.
 * Right pane: contextual metadata — persona, status, timestamps, raw JSON.
 */
export default function ConversationPage() {
    const navigate = useNavigate();
    const { id } = useParams<{ id: string }>();
    const [conversation, setConversation] = useState<Conversation | null>(null);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (!id) return;
        let cancelled = false;
        setLoading(true);
        setError(null);
        conversationApi
            .getConversation(id)
            .then((c) => {
                if (!cancelled) setConversation(c);
            })
            .catch((err) => {
                if (!cancelled) {
                    setError(err instanceof Error ? err.message : 'Failed to load');
                    setConversation(null);
                }
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => {
            cancelled = true;
        };
    }, [id]);

    if (!id) {
        return (
            <Stack p="md">
                <Alert color="red" icon={<IconAlertCircle size={16} />}>
                    Missing conversation id.
                </Alert>
            </Stack>
        );
    }

    return (
        <Stack h="100%" gap={0}>
            <Group justify="space-between" px="md" py="sm" style={{ borderBottom: '1px solid var(--mantine-color-gray-3)' }}>
                <Group gap="sm">
                    <Tooltip label="Back to conversations">
                        <ActionIcon variant="subtle" onClick={() => navigate('/conversations')}>
                            <IconArrowLeft size={18} />
                        </ActionIcon>
                    </Tooltip>
                    <Stack gap={0}>
                        <Title order={4}>
                            {conversation?.title ?? `Conversation ${id.slice(0, 8)}`}
                        </Title>
                        <Group gap={6}>
                            <Text size="xs" c="dimmed" ff="DM Mono, monospace">
                                {id}
                            </Text>
                            {conversation?.status && (
                                <Badge size="xs" variant="light">
                                    {conversation.status}
                                </Badge>
                            )}
                            {conversation?.persona && (
                                <Badge size="xs" variant="outline" color="grape">
                                    {conversation.persona}
                                </Badge>
                            )}
                        </Group>
                    </Stack>
                </Group>
                <Tooltip label="Refresh conversation metadata">
                    <ActionIcon
                        variant="subtle"
                        onClick={() => {
                            if (id) conversationApi.getConversation(id).then(setConversation);
                        }}
                    >
                        <IconRefresh size={18} />
                    </ActionIcon>
                </Tooltip>
            </Group>

            {error && (
                <Alert m="sm" color="red" icon={<IconAlertCircle size={16} />}>
                    {error}
                </Alert>
            )}

            <Group align="stretch" gap={0} style={{ flex: 1, minHeight: 0 }}>
                {/* Chat surface */}
                <Paper
                    style={{ flex: 1, minWidth: 0, borderRight: '1px solid var(--mantine-color-gray-3)' }}
                >
                    <ChatPanel
                        conversationId={id}
                        workflowId={conversation?.workflowId}
                        tenantId={conversation?.tenantId}
                    />
                </Paper>

                {/* Context sidebar */}
                <Paper w={320} p="md" style={{ overflow: 'hidden' }}>
                    {loading ? (
                        <Group justify="center" py="md">
                            <Loader size="sm" />
                        </Group>
                    ) : conversation ? (
                        <ScrollArea h="100%">
                            <Stack gap="md">
                                <Section label="Tenant">
                                    <Text size="sm">{conversation.tenantId ?? '—'}</Text>
                                </Section>
                                <Section label="User">
                                    <Text size="sm">{conversation.userId ?? '—'}</Text>
                                </Section>
                                <Section label="Channel">
                                    <Text size="sm">{conversation.channel ?? '—'}</Text>
                                </Section>
                                <Section label="Created">
                                    <Text size="xs" c="dimmed">
                                        {formatDate(conversation.createdAt)}
                                    </Text>
                                </Section>
                                <Section label="Updated">
                                    <Text size="xs" c="dimmed">
                                        {formatDate(conversation.updatedAt)}
                                    </Text>
                                </Section>
                                {conversation.workflowId && (
                                    <Section label="Workflow">
                                        <Text size="xs" ff="DM Mono, monospace">
                                            {conversation.workflowId}
                                        </Text>
                                    </Section>
                                )}
                                <Section label="Raw">
                                    <Paper
                                        withBorder
                                        radius="sm"
                                        p={6}
                                        style={(theme) => ({
                                            backgroundColor: theme.colors.dark[0],
                                            fontFamily: 'DM Mono, monospace',
                                            fontSize: 11,
                                            whiteSpace: 'pre-wrap',
                                            wordBreak: 'break-all',
                                        })}
                                    >
                                        {JSON.stringify(conversation, null, 2)}
                                    </Paper>
                                </Section>
                            </Stack>
                        </ScrollArea>
                    ) : (
                        <Text size="sm" c="dimmed">
                            No metadata.
                        </Text>
                    )}
                </Paper>
            </Group>
        </Stack>
    );
}

function Section({ label, children }: { label: string; children: React.ReactNode }) {
    return (
        <Stack gap={2}>
            <Text size="xs" tt="uppercase" c="dimmed" style={{ letterSpacing: 0.5 }}>
                {label}
            </Text>
            {children}
        </Stack>
    );
}

function formatDate(iso: string): string {
    try {
        return new Date(iso).toLocaleString();
    } catch {
        return iso;
    }
}
