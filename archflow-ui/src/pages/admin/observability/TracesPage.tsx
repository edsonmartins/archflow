import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
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

const STATUS_OPTIONS = [
    { value: 'all', label: 'All' },
    { value: 'OK', label: 'OK' },
    { value: 'ERROR', label: 'Error' },
];

const PAGE_SIZE = 50;

export default function TracesPage() {
    const navigate = useNavigate();
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
            .catch((e) => setError(e instanceof Error ? e.message : 'Failed to load traces'))
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
                    placeholder="Search by trace id, flow id, execution id..."
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
                    label="Status"
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
                    Refresh
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
                        No traces captured yet.
                    </Text>
                ) : (
                    <Table striped highlightOnHover>
                        <Table.Thead>
                            <Table.Tr>
                                <Table.Th>Trace ID</Table.Th>
                                <Table.Th>Tenant</Table.Th>
                                <Table.Th>Persona</Table.Th>
                                <Table.Th>Started</Table.Th>
                                <Table.Th>Duration</Table.Th>
                                <Table.Th>Spans</Table.Th>
                                <Table.Th>Status</Table.Th>
                            </Table.Tr>
                        </Table.Thead>
                        <Table.Tbody>
                            {traces.map((t) => (
                                <Table.Tr
                                    key={t.traceId}
                                    style={{ cursor: 'pointer' }}
                                    onClick={() => navigate(`/admin/observability/traces/${t.traceId}`)}
                                >
                                    <Table.Td>
                                        <Text size="xs" ff="DM Mono, monospace">
                                            {t.traceId.slice(0, 12)}
                                        </Text>
                                    </Table.Td>
                                    <Table.Td>{t.tenantId ?? '—'}</Table.Td>
                                    <Table.Td>
                                        {t.personaId ? (
                                            <Badge size="xs" variant="outline" color="grape">
                                                {t.personaId}
                                            </Badge>
                                        ) : (
                                            '—'
                                        )}
                                    </Table.Td>
                                    <Table.Td>{formatTime(t.startedAt)}</Table.Td>
                                    <Table.Td>{t.durationMs} ms</Table.Td>
                                    <Table.Td>{t.spanCount}</Table.Td>
                                    <Table.Td>
                                        <Badge
                                            size="xs"
                                            variant="light"
                                            color={statusColor(t.status)}
                                        >
                                            {t.status}
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
                    Showing {page * PAGE_SIZE + 1}–{Math.min((page + 1) * PAGE_SIZE, total)} of {total}
                </Text>
                <Group gap="xs">
                    <Button
                        variant="default"
                        size="xs"
                        disabled={page === 0}
                        onClick={() => setPage((p) => Math.max(0, p - 1))}
                    >
                        Previous
                    </Button>
                    <Button
                        variant="default"
                        size="xs"
                        disabled={(page + 1) * PAGE_SIZE >= total}
                        onClick={() => setPage((p) => p + 1)}
                    >
                        Next
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

function formatTime(iso: string): string {
    try {
        const d = new Date(iso);
        const diffMs = Date.now() - d.getTime();
        if (diffMs < 60_000) return 'just now';
        const mins = Math.floor(diffMs / 60_000);
        if (mins < 60) return `${mins}m ago`;
        const hours = Math.floor(mins / 60);
        if (hours < 24) return `${hours}h ago`;
        return d.toLocaleString();
    } catch {
        return iso;
    }
}
