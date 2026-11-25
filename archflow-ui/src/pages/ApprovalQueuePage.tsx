import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Badge, Button, Group, SimpleGrid, Stack, TextInput } from '@mantine/core';
import {
    IconAlarm,
    IconClockHour4,
    IconRefresh,
    IconSearch,
    IconShieldCheck,
    IconTopologyRing,
} from '@tabler/icons-react';
import { approvalApi, type ApprovalResponse } from '../services/approval-api';
import { useTenantStore } from '../stores/useTenantStore';
import { approvalTenantParam } from '../lib/approvalTenant';
import { PageHeader } from '../components/PageHeader';
import { SectionCard } from '../components/SectionCard';
import { StatCard } from '../components/admin/StatCard';
import { VxTable, colunaAcoes, colunaApagada, colunaDupla, colunaStatus, type VxColumn } from '../components/VTable';

export default function ApprovalQueuePage() {
    const navigate = useNavigate();
    const { t } = useTranslation();
    const { impersonating, currentRole } = useTenantStore();
    // impersonating é um TenantInfo — a API espera o ID (passar o objeto virava "[object Object]")
    const tenantId = approvalTenantParam(impersonating, currentRole);

    const [items, setItems] = useState<ApprovalResponse[]>([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [search, setSearch] = useState('');

    const load = async () => {
        setLoading(true);
        setError(null);
        try {
            const rows = await approvalApi.listPending(tenantId);
            setItems(rows);
        } catch (e) {
            setError(e instanceof Error ? e.message : t('approvals.loadError'));
            setItems([]);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        load();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [tenantId]);

    const filtered = useMemo(() => {
        const q = search.trim().toLowerCase();
        if (!q) return items;
        return items.filter(
            (it) =>
                it.requestId.toLowerCase().includes(q) ||
                it.flowId.toLowerCase().includes(q) ||
                (it.description ?? '').toLowerCase().includes(q),
        );
    }, [items, search]);

    const stats = useMemo(() => {
        const urgent = items.filter((it) => it.expiresAt && isUrgent(it.expiresAt)).length;
        const oldest = items.reduce<number | null>((acc, it) => {
            if (!it.createdAt) return acc;
            const ts = new Date(it.createdAt).getTime();
            return acc === null || ts < acc ? ts : acc;
        }, null);
        const flows = new Set(items.map((it) => it.flowId)).size;
        return { urgent, oldest, flows };
    }, [items]);

    const colunas = useMemo((): VxColumn[] => [
        colunaDupla(
            { field: 'description', title: t('approvals.table.description'), width: 'auto', minWidth: 300 },
            (r) => String(r.description ?? '—'),
            (r) => String(r.requestId).slice(0, 12),
        ),
        colunaDupla(
            { field: 'flowId', title: t('approvals.table.flow'), width: 230 },
            (r) => String(r.flowId),
            (r) => (r.stepId as string | null) ?? undefined,
        ),
        colunaApagada({ field: 'createdAt', title: t('approvals.table.waiting'), width: 140,
          fieldFormat: (r) => formatWait(r.createdAt as string | null, t) }),
        colunaStatus(
            { field: 'expiresAt', title: t('approvals.table.expires'), width: 160,
              fieldFormat: (r) => (r.expiresAt ? formatRelative(r.expiresAt as string, t) : '—') },
            (r) => (!r.expiresAt ? 'gray' : isUrgent(r.expiresAt as string) ? 'red' : 'orange'),
        ),
        colunaAcoes([{ nome: 'review', icone: 'eye', rotulo: t('approvals.review') }]),
    ], [t]);

    const open = (r: Record<string, unknown>) => navigate(`/approvals/${r.requestId}`);

    return (
        <Stack gap="lg" p="md">
            <PageHeader
                icon={IconShieldCheck}
                accent="amber"
                title={t('approvals.title')}
                subtitle={t('approvals.subtitle')}
                actions={
                    <Button
                        variant="default"
                        leftSection={<IconRefresh size={16} />}
                        onClick={load}
                        data-testid="approvals-refresh"
                    >
                        {t('common.refresh')}
                    </Button>
                }
            />

            <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="md">
                <StatCard
                    label={t('approvals.stats.pending')}
                    value={items.length}
                    color={items.length > 0 ? 'orange' : undefined}
                    icon={IconShieldCheck}
                    accent="amber"
                    hint={t('approvals.stats.pendingHint')}
                />
                <StatCard
                    label={t('approvals.stats.urgent')}
                    value={stats.urgent}
                    color={stats.urgent > 0 ? 'red' : undefined}
                    icon={IconAlarm}
                    accent="rose"
                    hint={t('approvals.stats.urgentHint')}
                />
                <StatCard
                    label={t('approvals.stats.oldest')}
                    value={stats.oldest === null ? '—' : formatDuration(Date.now() - stats.oldest)}
                    icon={IconClockHour4}
                    accent="violet"
                    hint={t('approvals.stats.oldestHint')}
                />
                <StatCard
                    label={t('approvals.stats.flows')}
                    value={stats.flows}
                    icon={IconTopologyRing}
                    accent="blue"
                    hint={t('approvals.stats.flowsHint')}
                />
            </SimpleGrid>

            <SectionCard
                flush
                icon={IconShieldCheck}
                accent="amber"
                title={t('approvals.listTitle')}
                description={t('approvals.listHint')}
                actions={
                    <Group gap="xs" wrap="wrap">
                        <Badge size="lg" variant="light" color="orange" radius="sm">
                            {t('approvals.pending', { count: items.length })}
                        </Badge>
                        <TextInput
                            size="sm"
                            placeholder={t('approvals.search')}
                            aria-label={t('approvals.search')}
                            leftSection={<IconSearch size={14} />}
                            value={search}
                            onChange={(e) => setSearch(e.currentTarget.value)}
                            w={320}
                            data-testid="approvals-search"
                        />
                    </Group>
                }
            >
                <VxTable
                    legenda={t('approvals.title')}
                    records={filtered as unknown as Record<string, unknown>[]}
                    columns={colunas}
                    rowHeight={58}
                    loading={loading}
                    error={error}
                    onRetry={load}
                    onRowClick={open}
                    onIconClick={(_nome, r) => open(r)}
                    emptyMessage={search.trim() ? t('approvals.emptyFiltered') : t('approvals.empty')}
                    emptyDescription={search.trim() ? undefined : t('approvals.emptyHint')}
                    emptyIcon={<IconShieldCheck />}
                    emptyAccent="amber"
                    testid="approvals-table"
                    rowTestId={(r) => `approval-row-${r.requestId}`}
                />
            </SectionCard>
        </Stack>
    );
}

type TFn = (key: string, opts?: Record<string, unknown>) => string;

function formatWait(createdAt: string | null, t: TFn): string {
    if (!createdAt) return '—';
    try {
        const diff = Date.now() - new Date(createdAt).getTime();
        return t('approvals.time.ago', { dur: formatDuration(diff) });
    } catch {
        return createdAt;
    }
}

function formatRelative(iso: string, t: TFn): string {
    try {
        const diff = new Date(iso).getTime() - Date.now();
        if (diff <= 0) return t('approvals.time.expired');
        return t('approvals.time.inFuture', { dur: formatDuration(diff) });
    } catch {
        return iso;
    }
}

function isUrgent(iso: string): boolean {
    try {
        return new Date(iso).getTime() - Date.now() < 60_000 * 5;
    } catch {
        return false;
    }
}

function formatDuration(ms: number): string {
    const abs = Math.abs(ms);
    if (abs < 60_000) return `${Math.floor(abs / 1000)}s`;
    if (abs < 3600_000) return `${Math.floor(abs / 60_000)}m`;
    return `${Math.floor(abs / 3600_000)}h`;
}
