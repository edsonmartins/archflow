import { useEffect, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router-dom';
import {
    Alert,
    Badge,
    Box,
    Button,
    Group,
    Paper,
    SimpleGrid,
    Skeleton,
    Stack,
    Text,
} from '@mantine/core';
import {
    IconAlertCircle,
    IconBraces,
    IconInfoCircle,
    IconMessage,
    IconRefresh,
} from '@tabler/icons-react';
import ChatPanel from '../components/chat/ChatPanel';
import { conversationApi, type Conversation, type ConversationStatus } from '../services/conversation-api';
import { formatInstant } from '../lib/format';
import { PageHeader } from '../components/PageHeader';
import { SectionCard } from '../components/SectionCard';
import EmptyState from '../components/EmptyState';
import { JsonViewer } from '../components/JsonViewer';

const STATUS_COLOR: Record<ConversationStatus, string> = {
    ACTIVE: 'teal',
    AWAITING_HUMAN: 'orange',
    SUSPENDED: 'yellow',
    ESCALATED: 'red',
    CLOSED: 'gray',
    COMPLETED: 'blue',
    CANCELLED: 'gray',
};

/**
 * Conversation detail page with split layout.
 *
 * Left pane: live chat surface (ChatPanel) consuming SSE.
 * Right pane: contextual metadata — persona, status, timestamps, raw JSON.
 */
export default function ConversationPage() {
    const { t, i18n } = useTranslation();
    const locale = i18n.resolvedLanguage ?? i18n.language;
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
                    setError(err instanceof Error ? err.message : t('conversationDetail.loadError'));
                    setConversation(null);
                }
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => {
            cancelled = true;
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [id]);

    if (!id) {
        return (
            <Stack p="md">
                <Alert color="red" icon={<IconAlertCircle size={16} />}>
                    {t('conversationDetail.missingId')}
                </Alert>
            </Stack>
        );
    }

    const title = conversation?.title ?? t('conversationDetail.title', { id: id.slice(0, 8) });

    return (
        <Stack gap="lg" p="md">
            <PageHeader
                icon={IconMessage}
                accent="teal"
                title={title}
                subtitle={t('conversationDetail.subtitle')}
                breadcrumbs={[
                    { label: t('conversations.title'), to: '/conversations' },
                    { label: id.slice(0, 8) },
                ]}
                actions={
                    <>
                        {conversation?.status && (
                            <Badge
                                size="lg"
                                radius="sm"
                                variant="dot"
                                color={STATUS_COLOR[conversation.status] ?? 'gray'}
                            >
                                {t(`conversations.statuses.${conversation.status}`, { defaultValue: conversation.status })}
                            </Badge>
                        )}
                        {conversation?.persona && (
                            <Badge size="lg" radius="sm" variant="light" color="grape">
                                {conversation.persona}
                            </Badge>
                        )}
                        <Button
                            variant="default"
                            leftSection={<IconRefresh size={16} />}
                            title={t('conversationDetail.refresh')}
                            onClick={() => {
                                if (id) conversationApi.getConversation(id).then(setConversation);
                            }}
                        >
                            {t('common.refresh')}
                        </Button>
                    </>
                }
            />

            {error && (
                <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />}>
                    {error}
                </Alert>
            )}

            <Group align="stretch" gap="md" wrap="wrap">
                {/* Chat surface */}
                <Paper
                    withBorder
                    style={{
                        flex: '1 1 520px',
                        minWidth: 0,
                        height: 'max(520px, calc(100vh - 250px))',
                        overflow: 'hidden',
                        display: 'flex',
                        flexDirection: 'column',
                    }}
                >
                    <ChatPanel
                        conversationId={id}
                        workflowId={conversation?.workflowId}
                        tenantId={conversation?.tenantId}
                    />
                </Paper>

                {/* Context sidebar */}
                <Stack gap="md" style={{ flex: '0 1 340px', minWidth: 280 }}>
                    <SectionCard
                        icon={IconInfoCircle}
                        accent="teal"
                        title={t('conversationDetail.details')}
                        description={t('conversationDetail.detailsHint')}
                    >
                        {loading ? (
                            <Stack gap="sm">
                                {Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} height={14} radius="sm" />)}
                            </Stack>
                        ) : conversation ? (
                            <SimpleGrid cols={2} spacing="md" verticalSpacing="md">
                                <Prop label={t('conversationDetail.section.tenant')}>{conversation.tenantId ?? '—'}</Prop>
                                <Prop label={t('conversationDetail.section.user')}>{conversation.userId ?? '—'}</Prop>
                                <Prop label={t('conversationDetail.section.channel')}>{conversation.channel ?? '—'}</Prop>
                                <Prop label={t('conversations.table.messages')}>{conversation.messageCount ?? '—'}</Prop>
                                <Box style={{ gridColumn: '1 / -1' }}>
                                    <Prop label={t('conversationDetail.section.created')} dim>
                                        {formatInstant(conversation.createdAt, locale)}
                                    </Prop>
                                </Box>
                                <Box style={{ gridColumn: '1 / -1' }}>
                                    <Prop label={t('conversationDetail.section.updated')} dim>
                                        {formatInstant(conversation.updatedAt, locale)}
                                    </Prop>
                                </Box>
                                {conversation.workflowId && (
                                    <Box style={{ gridColumn: '1 / -1' }}>
                                        <Prop label={t('conversationDetail.section.workflow')} dim>
                                            {conversation.workflowId}
                                        </Prop>
                                    </Box>
                                )}
                                <Box style={{ gridColumn: '1 / -1' }}>
                                    <Prop label={t('conversations.table.id')} dim>{id}</Prop>
                                </Box>
                            </SimpleGrid>
                        ) : (
                            <EmptyState
                                compact
                                icon={<IconInfoCircle />}
                                accent="slate"
                                title={t('conversationDetail.noMetadata')}
                            />
                        )}
                    </SectionCard>

                    {conversation && (
                        <SectionCard
                            icon={IconBraces}
                            accent="slate"
                            title={t('conversationDetail.section.raw')}
                            description={t('conversationDetail.rawHint')}
                        >
                            <JsonViewer value={conversation} autoExpandDepth={1} />
                        </SectionCard>
                    )}
                </Stack>
            </Group>
        </Stack>
    );
}

function Prop({ label, children, dim }: { label: string; children: ReactNode; dim?: boolean }) {
    return (
        <Stack gap={3} style={{ minWidth: 0 }}>
            <Text size="xs" fw={600} c="dimmed" tt="uppercase" style={{ letterSpacing: '0.06em' }}>
                {label}
            </Text>
            <Text size="sm" c={dim ? 'dimmed' : undefined} style={{ overflowWrap: 'anywhere' }}>
                {children}
            </Text>
        </Stack>
    );
}
