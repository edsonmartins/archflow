import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
    Badge,
    Button,
    Group,
    Select,
    Stack,
    Table,
    Text,
    TextInput,
    Title,
} from '@mantine/core';
import { IconRefresh, IconSearch } from '@tabler/icons-react';
import {
    conversationApi,
    type Conversation,
    type ConversationListParams,
    type ConversationStatus,
} from '../services/conversation-api';
import { DataTable, clickableRow, tabularNums } from '../components/DataTable';
import { StatusBadge } from '../components/StatusBadge';

const STATUS_VALUES: ConversationListParams['status'][] =
    ['ALL', 'ACTIVE', 'AWAITING_HUMAN', 'SUSPENDED', 'ESCALATED', 'CLOSED', 'COMPLETED', 'CANCELLED'];

const STATUS_COLOR: Record<ConversationStatus, string> = {
    ACTIVE: 'teal',
    AWAITING_HUMAN: 'orange',
    SUSPENDED: 'yellow',
    ESCALATED: 'red',
    CLOSED: 'gray',
    COMPLETED: 'blue',
    CANCELLED: 'gray',
};

export default function ConversationsListPage() {
    const navigate = useNavigate();
    const { t, i18n } = useTranslation();
    const [conversations, setConversations] = useState<Conversation[]>([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [search, setSearch] = useState('');
    const [status, setStatus] = useState<ConversationListParams['status']>('ALL');

    const load = useMemo(
        () => async () => {
            setLoading(true);
            setError(null);
            try {
                const result = await conversationApi.listConversations({
                    status: status === 'ALL' ? undefined : status,
                    search: search || undefined,
                    pageSize: 100,
                });
                setConversations(result.items);
            } catch (err) {
                console.error(err);
                setError(
                    err instanceof Error ? err.message : t('conversations.loadError'),
                );
                setConversations([]);
            } finally {
                setLoading(false);
            }
        },
        [search, status, t],
    );

    useEffect(() => {
        load();
    }, [load]);

    return (
        <Stack p="md" gap="md">
            <Group justify="space-between">
                <Title order={2}>{t('conversations.title')}</Title>
                <Button
                    leftSection={<IconRefresh size={16} />}
                    variant="subtle"
                    size="sm"
                    onClick={() => load()}
                >
                    {t('common.refresh')}
                </Button>
            </Group>

            <Group gap="sm" wrap="wrap">
                <TextInput
                    placeholder={t('conversations.search')}
                    leftSection={<IconSearch size={14} />}
                    value={search}
                    onChange={(e) => setSearch(e.currentTarget.value)}
                    style={{ flex: 1, minWidth: 240 }}
                />
                <Select
                    placeholder={t('conversations.status')}
                    data={STATUS_VALUES.map((v) => ({ value: String(v), label: t(`conversations.statuses.${v}`) }))}
                    value={String(status)}
                    onChange={(v) => setStatus((v as ConversationListParams['status']) ?? 'ALL')}
                    w={180}
                />
            </Group>

            <DataTable
                columns={7}
                minWidth={760}
                striped
                highlightOnHover
                loading={loading}
                error={error}
                onRetry={() => load()}
                isEmpty={conversations.length === 0}
                emptyMessage={t('conversations.empty')}
                head={
                    <Table.Tr>
                        <Table.Th>{t('conversations.table.id')}</Table.Th>
                        <Table.Th>{t('conversations.table.user')}</Table.Th>
                        <Table.Th>{t('conversations.table.channel')}</Table.Th>
                        <Table.Th>{t('conversations.table.persona')}</Table.Th>
                        <Table.Th>{t('conversations.table.status')}</Table.Th>
                        <Table.Th>{t('conversations.table.updated')}</Table.Th>
                        <Table.Th>{t('conversations.table.messages')}</Table.Th>
                    </Table.Tr>
                }
            >
                {conversations.map((c) => (
                    <Table.Tr key={c.id} {...clickableRow(() => navigate(`/conversations/${c.id}`))}>
                        <Table.Td>
                            <Text size="xs" ff="DM Mono, monospace">
                                {c.id.slice(0, 8)}
                            </Text>
                        </Table.Td>
                        <Table.Td>{c.userId ?? '—'}</Table.Td>
                        <Table.Td>{c.channel ?? '—'}</Table.Td>
                        <Table.Td>
                            {c.persona ? (
                                <Badge size="xs" variant="outline" color="grape">
                                    {c.persona}
                                </Badge>
                            ) : (
                                '—'
                            )}
                        </Table.Td>
                        <Table.Td>
                            <StatusBadge
                                size="xs"
                                status={c.status}
                                color={STATUS_COLOR[c.status] ?? 'gray'}
                                label={t(`conversations.statuses.${c.status}`, { defaultValue: c.status })}
                            />
                        </Table.Td>
                        <Table.Td>{formatRelative(c.updatedAt, t, i18n.resolvedLanguage ?? i18n.language)}</Table.Td>
                        <Table.Td style={tabularNums}>{c.messageCount ?? '—'}</Table.Td>
                    </Table.Tr>
                ))}
            </DataTable>
        </Stack>
    );
}

type TFn = (key: string, opts?: Record<string, unknown>) => string;

function formatRelative(iso: string, t: TFn, locale: string): string {
    try {
        const d = new Date(iso);
        const diffMs = Date.now() - d.getTime();
        const diffSec = Math.floor(diffMs / 1000);
        if (diffSec < 60) return t('conversations.time.seconds', { n: diffSec });
        const diffMin = Math.floor(diffSec / 60);
        if (diffMin < 60) return t('conversations.time.minutes', { n: diffMin });
        const diffH = Math.floor(diffMin / 60);
        if (diffH < 24) return t('conversations.time.hours', { n: diffH });
        const diffD = Math.floor(diffH / 24);
        if (diffD < 7) return t('conversations.time.days', { n: diffD });
        return d.toLocaleDateString(locale);
    } catch {
        return iso;
    }
}
