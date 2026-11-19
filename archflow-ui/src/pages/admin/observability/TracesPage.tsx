import { useEffect, useMemo, useState } from 'react';
import { useDebouncedValue } from '@mantine/hooks';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ActionIcon, Button, Group, Select, Text, TextInput, Tooltip } from '@mantine/core';
import { IconRefresh, IconSearch, IconTimeline } from '@tabler/icons-react';
import {
    observabilityApi,
    type ObservabilityFilter,
    type TraceSummaryDto,
} from '../../../services/observability-api';
import { SectionCard } from '../../../components/SectionCard';
import { VxTable, colunaApagada, colunaStatus, type Registro, type VxColumn } from '../../../components/VTable';

const PAGE_SIZE = 50;

export default function TracesPage() {
    const navigate = useNavigate();
    const { t, i18n } = useTranslation();
    const locale = i18n.resolvedLanguage ?? i18n.language;
    const STATUS_OPTIONS = [
        { value: 'all', label: t('admin.observability.traces.statusAll') },
        { value: 'OK', label: 'OK' },
        { value: 'ERROR', label: t('admin.observability.traces.statusError') },
    ];
    const [traces, setTraces] = useState<TraceSummaryDto[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(0);
    const [search, setSearch] = useState('');
    const [statusFilter, setStatusFilter] = useState<string>('all');
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    // Debounce the search box so typing doesn't fire a request per keystroke
    // (and race slower earlier responses); the input stays responsive.
    const [debSearch] = useDebouncedValue(search, 350);

    const filter = useMemo<ObservabilityFilter>(
        () => ({
            page,
            pageSize: PAGE_SIZE,
            search: debSearch || undefined,
            status: statusFilter === 'all' ? undefined : statusFilter,
        }),
        [page, debSearch, statusFilter],
    );

    const load = () => {
        setLoading(true);
        setError(null);
        observabilityApi
            .listTraces(filter)
            .then((res) => {
                setTraces(res.items);
                setTotal(res.total);
            })
            .catch((e) => setError(e instanceof Error ? e.message : t('admin.observability.traces.loadFailed')))
            .finally(() => setLoading(false));
    };

    useEffect(() => {
        load();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [page, debSearch, statusFilter]);

    const columns = useMemo((): VxColumn[] => [
        { field: 'traceId', title: t('admin.observability.traces.cols.traceId'), width: 170,
          style: { fontWeight: 600 },
          fieldFormat: (r: Registro) => (r.traceId as string).slice(0, 12) },
        { field: 'flowId', title: t('admin.observability.traces.cols.flow'), width: 'auto', minWidth: 140,
          fieldFormat: (r: Registro) => (r.flowId as string | null) ?? '—' },
        colunaApagada({ field: 'tenantId', title: t('admin.observability.traces.cols.tenant'), width: 130,
          fieldFormat: (r: Registro) => (r.tenantId as string | null) ?? '—' }),
        colunaStatus(
            { field: 'personaId', title: t('admin.observability.traces.cols.persona'), width: 190,
              fieldFormat: (r: Registro) => (r.personaId as string | null) ?? '' },
            () => 'violet',
        ),
        colunaApagada({ field: 'startedAt', title: t('admin.observability.traces.cols.started'), width: 220,
          fieldFormat: (r: Registro) => formatTime(r.startedAt as string, t, locale) }),
        colunaApagada({ field: 'durationMs', title: t('admin.observability.traces.cols.duration'), width: 120,
          fieldFormat: (r: Registro) => `${r.durationMs as number} ms` }),
        colunaApagada({ field: 'spanCount', title: t('admin.observability.traces.cols.spans'), width: 90 }),
        colunaStatus(
            { field: 'status', title: t('admin.observability.traces.cols.status'), width: 120 },
            (r) => statusColor(r.status as string),
        ),
    ], [t, locale]);

    return (
        <SectionCard
            flush
            icon={IconTimeline}
            accent="green"
            title={t('admin.observability.traces.listTitle')}
            description={t('admin.observability.traces.listHint')}
            actions={
                <>
                    <TextInput
                        size="xs"
                        placeholder={t('admin.observability.traces.search')}
                        aria-label={t('admin.observability.traces.search')}
                        leftSection={<IconSearch size={14} />}
                        value={search}
                        onChange={(e) => {
                            setPage(0);
                            setSearch(e.currentTarget.value);
                        }}
                        w={300}
                        data-testid="traces-search"
                    />
                    <Select
                        size="xs"
                        aria-label={t('admin.observability.traces.status')}
                        data={STATUS_OPTIONS}
                        value={statusFilter}
                        onChange={(v) => {
                            setPage(0);
                            setStatusFilter(v ?? 'all');
                        }}
                        allowDeselect={false}
                        w={130}
                        data-testid="traces-status"
                    />
                    <Tooltip label={t('common.refresh')}>
                        <ActionIcon variant="subtle" color="gray" onClick={() => load()} aria-label={t('common.refresh')}>
                            <IconRefresh size={16} />
                        </ActionIcon>
                    </Tooltip>
                </>
            }
        >
            <VxTable
                legenda={t('admin.observability.traces.listTitle')}
                records={traces as unknown as Registro[]}
                columns={columns}
                loading={loading}
                error={error}
                onRetry={() => load()}
                onRowClick={(r) => navigate(`/admin/observability/traces/${r.traceId as string}`)}
                emptyMessage={t('admin.observability.traces.empty')}
                emptyDescription={t('admin.observability.traces.emptyHint')}
                emptyIcon={<IconTimeline />}
                emptyAccent="green"
                testid="traces-table"
                rowTestId={(r) => `trace-row-${r.traceId as string}`}
            />
            {total > 0 && (
                <Pager page={page} pageSize={PAGE_SIZE} total={total} onPage={setPage} />
            )}
        </SectionCard>
    );
}

/** Pagination footer under the table: "showing a–b of n" plus previous / next. */
function Pager({ page, pageSize, total, onPage }: {
    page: number; pageSize: number; total: number; onPage: (p: number) => void;
}) {
    const { t } = useTranslation();
    return (
        <Group justify="space-between" px="lg" py="sm" style={{ borderTop: '1px solid var(--surface-border)' }}>
            <Text size="xs" c="dimmed">
                {t('admin.observability.audit.showing', {
                    from: page * pageSize + 1,
                    to: Math.min((page + 1) * pageSize, total),
                    total,
                })}
            </Text>
            <Group gap="xs">
                <Button variant="default" size="xs" disabled={page === 0} onClick={() => onPage(Math.max(0, page - 1))}>
                    {t('admin.observability.audit.previous')}
                </Button>
                <Button variant="default" size="xs" disabled={(page + 1) * pageSize >= total} onClick={() => onPage(page + 1)}>
                    {t('admin.observability.audit.next')}
                </Button>
            </Group>
        </Group>
    );
}

function statusColor(status: string): string {
    if (status === 'OK') return 'green';
    if (status === 'ERROR') return 'red';
    return 'gray';
}

type TFn = (key: string, opts?: Record<string, unknown>) => string;

function formatTime(iso: string, t: TFn, locale: string): string {
    try {
        const d = new Date(iso);
        const diffMs = Date.now() - d.getTime();
        if (diffMs < 60_000) return t('admin.observability.traces.time.justNow');
        const mins = Math.floor(diffMs / 60_000);
        if (mins < 60) return t('admin.observability.traces.time.minutesAgo', { n: mins });
        const hours = Math.floor(mins / 60);
        if (hours < 24) return t('admin.observability.traces.time.hoursAgo', { n: hours });
        return d.toLocaleString(locale);
    } catch {
        return iso;
    }
}
