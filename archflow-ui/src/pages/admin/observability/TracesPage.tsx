import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
    Alert,
    Badge,
    Button,
    Group,
    LoadingOverlay,
    Paper,
    Select,
    Stack,
    Table,
    Text,
    TextInput,
} from '@mantine/core';
import { IconAlertCircle, IconRefresh, IconSearch } from '@tabler/icons-react';
import {
    observabilityApi,
    type ObservabilityFilter,
    type TraceSummaryDto,
} from '../../../services/observability-api';

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

    const filter = useMemo<ObservabilityFilter>(
        () => ({
            page,
            pageSize: PAGE_SIZE,
            search: search || undefined,
            status: statusFilter === 'all' ? undefined : statusFilter,
        }),
        [page, search, statusFilter],
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
    }, [page, search, statusFilter]);

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

            {error && (
                <Alert color="red" icon={<IconAlertCircle size={16} />}>
                    {error}
                </Alert>
            )}

            <Paper withBorder pos="relative" radius="md">
                <LoadingOverlay visible={loading} zIndex={5} />
                {traces.length === 0 && !loading ? (
                    <Text size="sm" c="dimmed" ta="center" p="md">
                        {t('admin.observability.traces.empty')}
                    </Text>
                ) : (
                    <Table striped highlightOnHover>
                        <Table.Thead>
                            <Table.Tr>
                                <Table.Th>{t('admin.observability.traces.cols.traceId')}</Table.Th>
                                <Table.Th>{t('admin.observability.traces.cols.tenant')}</Table.Th>
                                <Table.Th>{t('admin.observability.traces.cols.persona')}</Table.Th>
                                <Table.Th>{t('admin.observability.traces.cols.started')}</Table.Th>
                                <Table.Th>{t('admin.observability.traces.cols.duration')}</Table.Th>
                                <Table.Th>{t('admin.observability.traces.cols.spans')}</Table.Th>
                                <Table.Th>{t('admin.observability.traces.cols.status')}</Table.Th>
                            </Table.Tr>
                        </Table.Thead>
                        <Table.Tbody>
                            {traces.map((row) => (
                                <Table.Tr
                                    key={row.traceId}
                                    style={{ cursor: 'pointer' }}
                                    onClick={() => navigate(`/admin/observability/traces/${row.traceId}`)}
                                >
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
                                    <Table.Td>{row.durationMs} ms</Table.Td>
                                    <Table.Td>{row.spanCount}</Table.Td>
                                    <Table.Td>
                                        <Badge
                                            size="xs"
                                            variant="light"
                                            color={statusColor(row.status)}
                                        >
                                            {row.status}
                                        </Badge>
                                    </Table.Td>
                                </Table.Tr>
                            ))}
                        </Table.Tbody>
                    </Table>
                )}
            </Paper>

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
