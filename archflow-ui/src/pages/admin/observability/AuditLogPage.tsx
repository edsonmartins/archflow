import { useEffect, useMemo, useState } from 'react';
import { useDebouncedValue } from '@mantine/hooks';
import { useTranslation } from 'react-i18next';
import {
    Badge,
    Button,
    Group,
    Stack,
    Table,
    Text,
    TextInput,
} from '@mantine/core';
import { IconDownload, IconSearch } from '@tabler/icons-react';
import {
    observabilityApi,
    type AuditEntryDto,
    type ObservabilityFilter,
} from '../../../services/observability-api';
import { DataTable } from '../../../components/DataTable';

const PAGE_SIZE = 50;

export default function AuditLogPage() {
    const { t, i18n } = useTranslation();
    const locale = i18n.resolvedLanguage ?? i18n.language;
    const [entries, setEntries] = useState<AuditEntryDto[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(0);
    const [actor, setActor] = useState('');
    const [action, setAction] = useState('');
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    // Debounce the typed filters so each keystroke doesn't fire a paginated
    // audit query (and race slower earlier responses); inputs stay responsive.
    const [debActor] = useDebouncedValue(actor, 350);
    const [debAction] = useDebouncedValue(action, 350);

    const filter = useMemo<ObservabilityFilter>(
        () => ({
            page,
            pageSize: PAGE_SIZE,
            actor: debActor || undefined,
            action: debAction || undefined,
        }),
        [page, debActor, debAction],
    );

    const load = () => {
        setLoading(true);
        setError(null);
        observabilityApi
            .listAudit(filter)
            .then((res) => {
                setEntries(res.items);
                setTotal(res.total);
            })
            .catch((e) => setError(e instanceof Error ? e.message : t('admin.observability.audit.loadFailed')))
            .finally(() => setLoading(false));
    };

    useEffect(() => {
        load();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [filter]);

    return (
        <Stack gap="sm">
            <Group gap="sm" wrap="wrap" align="flex-end">
                <TextInput
                    placeholder={t('admin.observability.audit.filterActor')}
                    leftSection={<IconSearch size={14} />}
                    value={actor}
                    onChange={(e) => {
                        setPage(0);
                        setActor(e.currentTarget.value);
                    }}
                    style={{ flex: 1, minWidth: 240 }}
                    data-testid="audit-actor"
                />
                <TextInput
                    placeholder={t('admin.observability.audit.filterAction')}
                    value={action}
                    onChange={(e) => {
                        setPage(0);
                        setAction(e.currentTarget.value);
                    }}
                    w={220}
                    data-testid="audit-action"
                />
                <Button
                    variant="default"
                    leftSection={<IconDownload size={14} />}
                    component="a"
                    href={observabilityApi.auditExportUrl(filter)}
                    download="audit-log.csv"
                    data-testid="audit-export"
                >
                    {t('admin.observability.audit.exportCsv')}
                </Button>
            </Group>

            <DataTable
                columns={6}
                minWidth={820}
                striped
                highlightOnHover
                loading={loading}
                error={error}
                onRetry={load}
                isEmpty={entries.length === 0}
                emptyMessage={t('admin.observability.audit.empty')}
                head={
                    <Table.Tr>
                        <Table.Th>{t('admin.observability.audit.cols.when')}</Table.Th>
                        <Table.Th>{t('admin.observability.audit.cols.actor')}</Table.Th>
                        <Table.Th>{t('admin.observability.audit.cols.action')}</Table.Th>
                        <Table.Th>{t('admin.observability.audit.cols.resource')}</Table.Th>
                        <Table.Th>{t('admin.observability.audit.cols.result')}</Table.Th>
                        <Table.Th>{t('admin.observability.audit.cols.trace')}</Table.Th>
                    </Table.Tr>
                }
            >
                {entries.map((e) => (
                    <Table.Tr key={e.id}>
                        <Table.Td>
                            <Text size="xs" c="dimmed">
                                {formatTime(e.timestamp, locale)}
                            </Text>
                        </Table.Td>
                        <Table.Td>
                            <Text size="sm">{e.username ?? e.userId ?? '—'}</Text>
                        </Table.Td>
                        <Table.Td>
                            <Badge size="xs" variant="light" color="blue">
                                {e.action}
                            </Badge>
                        </Table.Td>
                        <Table.Td>
                            {e.resourceId ? (
                                <Text size="xs" ff="DM Mono, monospace">
                                    {e.resourceType}:{e.resourceId}
                                </Text>
                            ) : (
                                '—'
                            )}
                        </Table.Td>
                        <Table.Td>
                            <Badge size="xs" variant="light" color={e.success ? 'teal' : 'red'}>
                                {e.success ? t('admin.observability.audit.resultSuccess') : t('admin.observability.audit.resultFailed')}
                            </Badge>
                        </Table.Td>
                        <Table.Td>
                            {e.traceId ? (
                                <Text size="xs" ff="DM Mono, monospace">
                                    {e.traceId.slice(0, 12)}
                                </Text>
                            ) : (
                                '—'
                            )}
                        </Table.Td>
                    </Table.Tr>
                ))}
            </DataTable>

            <Group justify="space-between">
                <Text size="xs" c="dimmed">
                    {t('admin.observability.audit.showing', {
                        from: total === 0 ? 0 : page * PAGE_SIZE + 1,
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

function formatTime(iso: string, locale: string): string {
    try {
        return new Date(iso).toLocaleString(locale);
    } catch {
        return iso;
    }
}
