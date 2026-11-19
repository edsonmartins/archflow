import { useEffect, useMemo, useState } from 'react';
import { useDebouncedValue } from '@mantine/hooks';
import { useTranslation } from 'react-i18next';
import { Button, Group, Text, TextInput } from '@mantine/core';
import { IconDownload, IconHistory, IconSearch } from '@tabler/icons-react';
import {
    observabilityApi,
    type AuditEntryDto,
    type ObservabilityFilter,
} from '../../../services/observability-api';
import { formatInstant } from '../../../lib/format';
import { SectionCard } from '../../../components/SectionCard';
import { VxTable, colunaApagada, colunaDupla, colunaStatus, type Registro, type VxColumn } from '../../../components/VTable';

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

    const columns = useMemo((): VxColumn[] => [
        colunaApagada({ field: 'timestamp', title: t('admin.observability.audit.cols.when'), width: 200,
          fieldFormat: (r: Registro) => formatInstant(r.timestamp as string, locale) }),
        colunaDupla(
            { field: 'username', title: t('admin.observability.audit.cols.actor'), width: 190 },
            (r) => (r.username as string | null) ?? (r.userId as string | null) ?? '—',
            (r) => (r.ipAddress as string | null) ?? undefined,
        ),
        colunaStatus(
            { field: 'action', title: t('admin.observability.audit.cols.action'), width: 230 },
            (r) => (r.success ? 'blue' : 'red'),
        ),
        colunaApagada({ field: 'resourceId', title: t('admin.observability.audit.cols.resource'), width: 'auto',
          minWidth: 180,
          fieldFormat: (r: Registro) => (r.resourceId ? `${r.resourceType as string}:${r.resourceId as string}` : '—') }),
        colunaStatus(
            { field: 'success', title: t('admin.observability.audit.cols.result'), width: 130,
              fieldFormat: (r: Registro) => (r.success
                  ? t('admin.observability.audit.resultSuccess')
                  : t('admin.observability.audit.resultFailed')) },
            (r) => (r.success ? 'green' : 'red'),
        ),
        colunaApagada({ field: 'traceId', title: t('admin.observability.audit.cols.trace'), width: 150,
          fieldFormat: (r: Registro) => (r.traceId ? (r.traceId as string).slice(0, 12) : '—') }),
    ], [t, locale]);

    return (
        <SectionCard
            flush
            icon={IconHistory}
            accent="green"
            title={t('admin.observability.audit.listTitle')}
            description={t('admin.observability.audit.listHint')}
            actions={
                <>
                    <TextInput
                        size="xs"
                        placeholder={t('admin.observability.audit.filterActor')}
                        aria-label={t('admin.observability.audit.cols.actor')}
                        leftSection={<IconSearch size={14} />}
                        value={actor}
                        onChange={(e) => {
                            setPage(0);
                            setActor(e.currentTarget.value);
                        }}
                        w={300}
                        data-testid="audit-actor"
                    />
                    <TextInput
                        size="xs"
                        placeholder={t('admin.observability.audit.filterAction')}
                        aria-label={t('admin.observability.audit.cols.action')}
                        value={action}
                        onChange={(e) => {
                            setPage(0);
                            setAction(e.currentTarget.value);
                        }}
                        w={260}
                        data-testid="audit-action"
                    />
                    <Button
                        size="xs"
                        variant="default"
                        leftSection={<IconDownload size={14} />}
                        component="a"
                        href={observabilityApi.auditExportUrl(filter)}
                        download="audit-log.csv"
                        data-testid="audit-export"
                    >
                        {t('admin.observability.audit.exportCsv')}
                    </Button>
                </>
            }
        >
            <VxTable
                legenda={t('admin.observability.audit.listTitle')}
                records={entries as unknown as Registro[]}
                columns={columns}
                rowHeight={58}
                loading={loading}
                error={error}
                onRetry={load}
                emptyMessage={t('admin.observability.audit.empty')}
                emptyDescription={t('admin.observability.audit.emptyHint')}
                emptyIcon={<IconHistory />}
                emptyAccent="green"
                testid="audit-table"
                rowTestId={(r) => `audit-row-${r.id as string}`}
            />
            {total > 0 && (
                <Group justify="space-between" px="lg" py="sm" style={{ borderTop: '1px solid var(--surface-border)' }}>
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
            )}
        </SectionCard>
    );
}
