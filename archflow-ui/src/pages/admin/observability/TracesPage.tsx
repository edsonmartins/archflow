import { useEffect, useMemo, useState } from 'react';
import { useDebouncedValue } from '@mantine/hooks';
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
} from '@mantine/core';
import { IconRefresh, IconSearch } from '@tabler/icons-react';
import {
    observabilityApi,
    type ObservabilityFilter,
    type TraceSummaryDto,
} from '../../../services/observability-api';
import { DataTable, clickableRow, tabularNums } from '../../../components/DataTable';
import { StatusBadge } from '../../../components/StatusBadge';

const PAGE_SIZE = 50;

export default function TracesPage() {
    const navigate = useNavigate();
    const { t, i18n } = useTranslation();
    const locale = i18n.resolvedLanguage ?? i18n.language;
    const STATUS_OPTIONS = [
        { value: 'all', label: t('admin.observability.traces.statusAll') },
        { value: 'OK', label: 'OK' },
        { value: 'ERROR', label: 'Error' },
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

    return (
        <Stack gap="sm">
            <Group gap="sm" wrap="wrap" align="flex-end">
                <TextInput
                    placeholder={t('admin.observability.traces.search')}
                    leftSection={<IconSearch size={14} />}
                    value={search}
                    onChange={(e) => {
                        setPage(0);
                        setSearch(e.currentTarget.value);
                    }}
                    style={{ flex: 1, minWidth: 260 }}
                    data-testid="traces-search"
                />
                <Select
                    label={t('admin.observability.traces.status')}
                    data={STATUS_OPTIONS}
                    value={statusFilter}
                    onChange={(v) => {
                        setPage(0);
                        setStatusFilter(v ?? 'all');
                    }}
                    w={140}
                    data-testid="traces-status"
                />
                <Button
                    variant="subtle"
                    leftSection={<IconRefresh size={14} />}
                    onClick={() => load()}
                >
                    {t('common.refresh')}
                </Button>
            </Group>

            <DataTable
                columns={7}
                minWidth={820}
                striped
                highlightOnHover
                loading={loading}
                error={error}
                onRetry={() => load()}
                isEmpty={traces.length === 0}
                emptyMessage={t('admin.observability.traces.empty')}
                head={
                    <Table.Tr>
                        <Table.Th>{t('admin.observability.traces.cols.traceId')}</Table.Th>
                        <Table.Th>{t('admin.observability.traces.cols.tenant')}</Table.Th>
                        <Table.Th>{t('admin.observability.traces.cols.persona')}</Table.Th>
                        <Table.Th>{t('admin.observability.traces.cols.started')}</Table.Th>
                        <Table.Th>{t('admin.observability.traces.cols.duration')}</Table.Th>
                        <Table.Th>{t('admin.observability.traces.cols.spans')}</Table.Th>
                        <Table.Th>{t('admin.observability.traces.cols.status')}</Table.Th>
                    </Table.Tr>
                }
            >
                {traces.map((row) => (
                    <Table.Tr key={row.traceId} {...clickableRow(() => navigate(`/admin/observability/traces/${row.traceId}`))}>
                        <Table.Td>
                            <Text size="xs" ff="DM Mono, monospace">
                                {row.traceId.slice(0, 12)}
                            </Text>
                        </Table.Td>
                        <Table.Td>{row.tenantId ?? '—'}</Table.Td>
                        <Table.Td>
                            {row.personaId ? (
                                <Badge size="xs" variant="outline" color="grape">
                                    {row.personaId}
                                </Badge>
                            ) : (
                                '—'
                            )}
                        </Table.Td>
                        <Table.Td>{formatTime(row.startedAt, t, locale)}</Table.Td>
                        <Table.Td style={tabularNums}>{row.durationMs} ms</Table.Td>
                        <Table.Td style={tabularNums}>{row.spanCount}</Table.Td>
                        <Table.Td>
                            <StatusBadge size="xs" status={row.status} color={statusColor(row.status)} label={row.status} />
                        </Table.Td>
                    </Table.Tr>
                ))}
            </DataTable>

            <Group justify="space-between">
                <Text size="xs" c="dimmed">
                    {t('admin.observability.audit.showing', {
                        from: page * PAGE_SIZE + 1,
                        to: Math.min((page + 1) * PAGE_SIZE, total),
                        total,
                    })}
                </Text>
                <Group gap="xs">
                    <Button
                        variant="default"
                        size="xs"
                        disabled={page === 0}
                        onClick={() => setPage((p) => Math.max(0, p - 1))}
                    >
                        {t('admin.observability.audit.previous')}
                    </Button>
                    <Button
                        variant="default"
                        size="xs"
                        disabled={(page + 1) * PAGE_SIZE >= total}
                        onClick={() => setPage((p) => p + 1)}
                    >
                        {t('admin.observability.audit.next')}
                    </Button>
                </Group>
            </Group>
        </Stack>
    );
}

function statusColor(status: string): string {
    if (status === 'OK') return 'teal';
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
