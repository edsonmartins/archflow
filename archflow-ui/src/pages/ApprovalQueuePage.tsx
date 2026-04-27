import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
    Alert,
    Badge,
    Button,
    Group,
    Paper,
    Stack,
    Table,
    Text,
    TextInput,
    Title,
} from '@mantine/core';
import { IconAlertCircle, IconRefresh, IconSearch } from '@tabler/icons-react';
import { approvalApi, type ApprovalResponse } from '../services/approval-api';
import { useTenantStore } from '../stores/useTenantStore';

export default function ApprovalQueuePage() {
    const navigate = useNavigate();
    const { t } = useTranslation();
    const { impersonating, currentRole } = useTenantStore();
    const tenantId = impersonating ?? (currentRole === 'superadmin' ? 'all' : 'default');

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

    return (
        <Stack p="md" gap="md">
            <Group justify="space-between">
                <Stack gap={0}>
                    <Title order={2}>{t('approvals.title')}</Title>
                    <Text size="sm" c="dimmed">{t('approvals.subtitle')}</Text>
                </Stack>
                <Button
                    variant="default"
                    leftSection={<IconRefresh size={14} />}
                    onClick={load}
                    data-testid="approvals-refresh"
                >
                    {t('common.refresh')}
                </Button>
            </Group>

            <Group gap="sm" wrap="wrap">
                <TextInput
                    placeholder={t('approvals.search')}
                    leftSection={<IconSearch size={14} />}
                    value={search}
                    onChange={(e) => setSearch(e.currentTarget.value)}
                    style={{ flex: 1, minWidth: 260 }}
                    data-testid="approvals-search"
                />
                <Badge size="lg" variant="light" color="orange">
                    {t('approvals.pending', { count: items.length })}
                </Badge>
            </Group>

            {error && (
                <Alert color="red" icon={<IconAlertCircle size={16} />}>
                    {error}
                </Alert>
            )}

            <Paper withBorder radius="md">
                {filtered.length === 0 && !loading ? (
                    <Text size="sm" c="dimmed" ta="center" p="md">
                        {t('approvals.empty')}
                    </Text>
                ) : (
                    <Table striped highlightOnHover>
                        <Table.Thead>
                            <Table.Tr>
                                <Table.Th>{t('approvals.table.request')}</Table.Th>
                                <Table.Th>{t('approvals.table.flow')}</Table.Th>
                                <Table.Th>{t('approvals.table.step')}</Table.Th>
                                <Table.Th>{t('approvals.table.description')}</Table.Th>
                                <Table.Th>{t('approvals.table.waiting')}</Table.Th>
                                <Table.Th>{t('approvals.table.expires')}</Table.Th>
                            </Table.Tr>
                        </Table.Thead>
                        <Table.Tbody>
                            {filtered.map((row) => (
                                <Table.Tr
                                    key={row.requestId}
                                    style={{ cursor: 'pointer' }}
                                    onClick={() => navigate(`/approvals/${row.requestId}`)}
                                >
                                    <Table.Td>
                                        <Text size="xs" ff="DM Mono, monospace">
                                            {row.requestId.slice(0, 12)}
                                        </Text>
                                    </Table.Td>
                                    <Table.Td>{row.flowId}</Table.Td>
                                    <Table.Td>
                                        {row.stepId ? (
                                            <Text size="xs" ff="DM Mono, monospace">
                                                {row.stepId}
                                            </Text>
                                        ) : (
                                            '—'
                                        )}
                                    </Table.Td>
                                    <Table.Td>
                                        <Text size="sm">{row.description ?? '—'}</Text>
                                    </Table.Td>
                                    <Table.Td>{formatWait(row.createdAt, t)}</Table.Td>
                                    <Table.Td>
                                        {row.expiresAt ? (
                                            <Text size="xs" c={isUrgent(row.expiresAt) ? 'red' : 'dimmed'}>
                                                {formatRelative(row.expiresAt, t)}
                                            </Text>
                                        ) : (
                                            '—'
                                        )}
                                    </Table.Td>
                                </Table.Tr>
                            ))}
                        </Table.Tbody>
                    </Table>
                )}
            </Paper>
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
