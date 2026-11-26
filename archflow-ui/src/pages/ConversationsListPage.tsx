import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Button, Group, Select, SimpleGrid, Stack, TextInput } from '@mantine/core';
import {
    IconAlertTriangle,
    IconMessages,
    IconMessageCircle,
    IconPlus,
    IconRefresh,
    IconSearch,
    IconUserQuestion,
    IconBolt,
} from '@tabler/icons-react';
import {
    conversationApi,
    type Conversation,
    type ConversationListParams,
    type ConversationStatus,
} from '../services/conversation-api';
import { PageHeader } from '../components/PageHeader';
import { SectionCard } from '../components/SectionCard';
import { StatCard } from '../components/admin/StatCard';
import { VxTable, colunaApagada, colunaDupla, colunaStatus, type VxColumn } from '../components/VTable';
import { selo } from '../components/VTable/tokens';
import { usePermissions } from '../lib/permissions';
import { PermissionTooltip } from '../components/PermissionTooltip';

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
    // Abrir uma conversa é acionar o agente: o servidor exige workflow:execute (QA2 RCHFLW-158).
    const canStart = usePermissions()('workflow:execute');
    const locale = i18n.resolvedLanguage ?? i18n.language;
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

    const counts = useMemo(() => {
        const by = (...s: ConversationStatus[]) => conversations.filter((c) => s.includes(c.status)).length;
        return {
            active: by('ACTIVE'),
            waiting: by('AWAITING_HUMAN', 'SUSPENDED'),
            escalated: by('ESCALATED'),
        };
    }, [conversations]);

    const colunas = useMemo((): VxColumn[] => [
        colunaDupla(
            { field: 'id', title: t('conversations.table.id'), width: 'auto', minWidth: 220 },
            (r) => (r.title ? String(r.title) : String(r.id).slice(0, 8)),
            (r) => (r.title ? String(r.id).slice(0, 8) : (r.lastMessage as string | undefined)),
        ),
        colunaDupla(
            { field: 'userId', title: t('conversations.table.user'), width: 200 },
            (r) => String(r.userId ?? '—'),
            (r) => (r.channel as string | undefined) ?? undefined,
        ),
        { field: 'persona', title: t('conversations.table.persona'), width: 180,
          fieldFormat: (r) => String(r.persona ?? '—'),
          style: () => ({ color: selo('grape').tinta, fontWeight: 600 }) },
        colunaStatus(
            { field: 'status', title: t('conversations.table.status'), width: 190,
              fieldFormat: (r) => t(`conversations.statuses.${r.status}`, { defaultValue: String(r.status) }) },
            (r) => STATUS_COLOR[r.status as ConversationStatus] ?? 'gray',
        ),
        colunaApagada({ field: 'updatedAt', title: t('conversations.table.updated'), width: 140,
          fieldFormat: (r) => formatRelative(r.updatedAt as string, t, locale) }),
        colunaApagada({ field: 'messageCount', title: t('conversations.table.messages'), width: 120,
          fieldFormat: (r) => (r.messageCount === undefined || r.messageCount === null ? '—' : String(r.messageCount)),
          style: { textAlign: 'right' } }),
    ], [t, locale]);

    const filtering = search.trim() !== '' || status !== 'ALL';

    return (
        <Stack gap="lg" p="md">
            <PageHeader
                icon={IconMessages}
                accent="teal"
                title={t('conversations.title')}
                subtitle={t('conversations.subtitle')}
                actions={
                    <>
                        <Button
                            leftSection={<IconRefresh size={16} />}
                            variant="default"
                            onClick={() => load()}
                        >
                            {t('common.refresh')}
                        </Button>
                        <PermissionTooltip missing={canStart ? null : 'workflow:execute'}>
                            <Button
                                leftSection={<IconPlus size={16} />}
                                disabled={!canStart}
                                data-testid="conversation-new"
                                onClick={() => {
                                    void conversationApi
                                        .createConversation()
                                        .then(({ id }) => navigate(`/conversations/${id}`));
                                }}
                            >
                                {t('conversations.new', 'Nova conversa')}
                            </Button>
                        </PermissionTooltip>
                    </>
                }
            />

            <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="md">
                <StatCard
                    label={t('conversations.stats.total')}
                    value={loading && conversations.length === 0 ? '—' : conversations.length}
                    icon={IconMessageCircle}
                    accent="teal"
                    hint={t('conversations.stats.totalHint')}
                />
                <StatCard
                    label={t('conversations.stats.active')}
                    value={counts.active}
                    icon={IconBolt}
                    accent="green"
                    hint={t('conversations.stats.activeHint')}
                />
                <StatCard
                    label={t('conversations.stats.waiting')}
                    value={counts.waiting}
                    color={counts.waiting > 0 ? 'orange' : undefined}
                    icon={IconUserQuestion}
                    accent="amber"
                    hint={t('conversations.stats.waitingHint')}
                />
                <StatCard
                    label={t('conversations.stats.escalated')}
                    value={counts.escalated}
                    color={counts.escalated > 0 ? 'red' : undefined}
                    icon={IconAlertTriangle}
                    accent="rose"
                    hint={t('conversations.stats.escalatedHint')}
                />
            </SimpleGrid>

            <SectionCard
                flush
                icon={IconMessages}
                accent="teal"
                title={t('conversations.listTitle')}
                description={t('conversations.listHint')}
                actions={
                    <Group gap="xs" wrap="wrap">
                        <TextInput
                            size="sm"
                            placeholder={t('conversations.search')}
                            aria-label={t('conversations.search')}
                            leftSection={<IconSearch size={14} />}
                            value={search}
                            onChange={(e) => setSearch(e.currentTarget.value)}
                            w={300}
                        />
                        <Select
                            size="sm"
                            placeholder={t('conversations.status')}
                            aria-label={t('conversations.status')}
                            data={STATUS_VALUES.map((v) => ({ value: String(v), label: t(`conversations.statuses.${v}`) }))}
                            value={String(status)}
                            onChange={(v) => setStatus((v as ConversationListParams['status']) ?? 'ALL')}
                            w={210}
                        />
                    </Group>
                }
            >
                <VxTable
                    legenda={t('conversations.title')}
                    records={conversations as unknown as Record<string, unknown>[]}
                    columns={colunas}
                    rowHeight={58}
                    loading={loading}
                    error={error}
                    onRetry={() => load()}
                    onRowClick={(r) => navigate(`/conversations/${r.id}`)}
                    emptyMessage={t('conversations.empty')}
                    emptyDescription={filtering ? t('conversations.emptyFiltered') : t('conversations.emptyHint')}
                    emptyIcon={<IconMessages />}
                    emptyAccent="teal"
                    testid="conversations-table"
                    rowTestId={(r) => `conv-row-${r.id}`}
                />
            </SectionCard>
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
