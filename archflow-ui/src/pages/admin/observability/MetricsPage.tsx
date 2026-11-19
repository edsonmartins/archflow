import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Alert, Badge, Code, Group, Select, SimpleGrid, Skeleton, Stack, Text } from '@mantine/core';
import {
    IconAlertCircle,
    IconArrowDown,
    IconArrowUp,
    IconChartHistogram,
    IconChartLine,
    IconDatabaseOff,
    IconGauge,
    IconMathAvg,
    IconNumbers,
    IconPointFilled,
} from '@tabler/icons-react';
import { AreaChart } from '@mantine/charts';
import {
    observabilityApi,
    type MetricSeriesDto,
    type MetricsSnapshotDto,
} from '../../../services/observability-api';
import EmptyState from '../../../components/EmptyState';
import { SectionCard } from '../../../components/SectionCard';
import { StatCard } from '../../../components/admin/StatCard';
import { VxTable, colunaApagada, type Registro, type VxColumn } from '../../../components/VTable';

/** Format a bucket timestamp to a short HH:mm label; fall back to raw. */
function formatBucket(bucket: string, locale: string): string {
    const d = new Date(bucket);
    return Number.isNaN(d.getTime())
        ? bucket
        : d.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' });
}

const METRIC_VALUES = ['latency_ms', 'throughput', 'error_rate'] as const;
const BUCKET_VALUES = ['60', '300', '900'] as const;

export default function MetricsPage() {
    const { t, i18n } = useTranslation();
    const locale = i18n.resolvedLanguage ?? i18n.language;
    const [snapshot, setSnapshot] = useState<MetricsSnapshotDto | null>(null);
    const [series, setSeries] = useState<MetricSeriesDto | null>(null);
    const [metric, setMetric] = useState('latency_ms');
    const [bucket, setBucket] = useState('300');
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        Promise.all([
            observabilityApi.getMetricsSnapshot(),
            observabilityApi.getMetricSeries(metric, { bucketSeconds: Number(bucket), buckets: 12 }),
        ])
            .then(([snap, seriesList]) => {
                if (cancelled) return;
                setSnapshot(snap);
                setSeries(seriesList[0] ?? null);
            })
            .catch((e) => {
                if (!cancelled) setError(e instanceof Error ? e.message : t('admin.observability.metrics.loadFailed'));
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => {
            cancelled = true;
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [metric, bucket]);

    const fmt = (v: number) => v.toLocaleString(locale, { maximumFractionDigits: 2 });
    const metricLabel = t(`admin.observability.metrics.metricValues.${metric}`, { defaultValue: metric });

    const histogramColumns = useMemo((): VxColumn[] => {
        const num = (v: unknown) => (v as number).toFixed(2);
        return [
            { field: 'name', title: t('admin.observability.metrics.histCols.metric'), width: 'auto', minWidth: 220,
              style: { fontWeight: 600 } },
            { field: 'count', title: t('admin.observability.metrics.histCols.count'), width: 110 },
            colunaApagada({ field: 'min', title: t('admin.observability.metrics.histCols.min'), width: 120,
              fieldFormat: (r: Registro) => num(r.min) }),
            colunaApagada({ field: 'max', title: t('admin.observability.metrics.histCols.max'), width: 120,
              fieldFormat: (r: Registro) => num(r.max) }),
            { field: 'mean', title: t('admin.observability.metrics.histCols.mean'), width: 120,
              fieldFormat: (r: Registro) => num(r.mean) },
            colunaApagada({ field: 'median', title: t('admin.observability.metrics.histCols.median'), width: 120,
              fieldFormat: (r: Registro) => num(r.median) }),
        ];
    }, [t]);

    if (loading) {
        return (
            <Stack gap="lg" aria-busy="true">
                <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="md">
                    {Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} height={128} radius="md" />)}
                </SimpleGrid>
                <Skeleton height={320} radius="md" />
            </Stack>
        );
    }

    if (error) {
        return (
            <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />}>
                {error}
            </Alert>
        );
    }

    const values = series?.values ?? [];
    const hasSamples = !!series && series.buckets.length > 0;
    const last = values.length ? values[values.length - 1] : null;
    const avg = values.length ? values.reduce((a, b) => a + b, 0) / values.length : null;
    const peak = values.length ? Math.max(...values) : null;
    const low = values.length ? Math.min(...values) : null;
    const histogramRows = snapshot
        ? Object.entries(snapshot.stats).map(([name, stats]) => ({ name, ...stats }))
        : [];

    return (
        <Stack gap="lg">
            <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="md">
                <StatCard
                    label={t('admin.observability.metrics.stats.last')}
                    value={last === null ? '—' : fmt(last)}
                    icon={IconPointFilled}
                    accent="green"
                    hint={metricLabel}
                />
                <StatCard
                    label={t('admin.observability.metrics.stats.avg')}
                    value={avg === null ? '—' : fmt(avg)}
                    icon={IconMathAvg}
                    accent="teal"
                    hint={t('admin.observability.metrics.stats.window')}
                />
                <StatCard
                    label={t('admin.observability.metrics.stats.peak')}
                    value={peak === null ? '—' : fmt(peak)}
                    icon={IconArrowUp}
                    accent="amber"
                    hint={t('admin.observability.metrics.stats.window')}
                />
                <StatCard
                    label={t('admin.observability.metrics.stats.low')}
                    value={low === null ? '—' : fmt(low)}
                    icon={IconArrowDown}
                    accent="blue"
                    hint={t('admin.observability.metrics.stats.window')}
                />
            </SimpleGrid>

            <SectionCard
                icon={IconChartLine}
                accent="green"
                // A real heading for the chart: it names what the axes show, and screen readers jump by it.
                title={<span role="heading" aria-level={3}>{metricLabel}</span>}
                description={t('admin.observability.metrics.chartHint')}
                actions={
                    <>
                        {series && (
                            <Badge variant="light" color="green">
                                {t('admin.observability.metrics.buckets', { count: series.buckets.length })}
                            </Badge>
                        )}
                        <Select
                            size="xs"
                            aria-label={t('admin.observability.metrics.metric')}
                            data={METRIC_VALUES.map((v) => ({ value: v, label: t(`admin.observability.metrics.metricValues.${v}`) }))}
                            value={metric}
                            onChange={(v) => v && setMetric(v)}
                            allowDeselect={false}
                            w={190}
                            data-testid="metric-select"
                        />
                        <Select
                            size="xs"
                            aria-label={t('admin.observability.metrics.bucket')}
                            data={BUCKET_VALUES.map((v) => ({ value: v, label: t(`admin.observability.metrics.bucketValues.${v}`) }))}
                            value={bucket}
                            onChange={(v) => v && setBucket(v)}
                            allowDeselect={false}
                            w={110}
                            data-testid="bucket-select"
                        />
                    </>
                }
            >
                {hasSamples ? (
                    <AreaChart
                        h={260}
                        data={series.buckets.map((b, i) => ({
                            bucket: formatBucket(b, locale),
                            value: series.values[i] ?? 0,
                        }))}
                        dataKey="bucket"
                        series={[{ name: 'value', label: metricLabel, color: 'green.6' }]}
                        curveType="monotone"
                        withGradient
                        fillOpacity={0.22}
                        strokeWidth={2}
                        withDots={series.values.length <= 24}
                        tickLine="none"
                        gridAxis="y"
                        strokeDasharray="4 4"
                        valueFormatter={(v) => v.toLocaleString(locale)}
                    />
                ) : (
                    <EmptyState
                        compact
                        accent="green"
                        icon={<IconChartLine />}
                        title={t('admin.observability.metrics.noSamples')}
                    />
                )}
            </SectionCard>

            {snapshot && (
                <>
                    <SimpleGrid cols={{ base: 1, md: 2 }} spacing="md">
                        <SectionCard
                            icon={IconNumbers}
                            accent="green"
                            title={t('admin.observability.metrics.counters')}
                            description={t('admin.observability.metrics.countersHint')}
                        >
                            <KeyValueList map={snapshot.counters} format={(v) => fmt(v)} emptyLabel={t('admin.observability.metrics.noData')} />
                        </SectionCard>
                        <SectionCard
                            icon={IconGauge}
                            accent="teal"
                            title={t('admin.observability.metrics.gauges')}
                            description={t('admin.observability.metrics.gaugesHint')}
                        >
                            <KeyValueList map={snapshot.values} format={(v) => v.toFixed(2)} emptyLabel={t('admin.observability.metrics.noData')} />
                        </SectionCard>
                    </SimpleGrid>

                    <SectionCard
                        flush
                        icon={IconChartHistogram}
                        accent="green"
                        title={t('admin.observability.metrics.histograms')}
                        description={t('admin.observability.metrics.histogramsHint')}
                    >
                        <VxTable
                            legenda={t('admin.observability.metrics.histograms')}
                            records={histogramRows as unknown as Registro[]}
                            columns={histogramColumns}
                            emptyMessage={t('admin.observability.metrics.noHistograms')}
                            emptyIcon={<IconChartHistogram />}
                            emptyAccent="green"
                            testid="metrics-histograms"
                        />
                    </SectionCard>
                </>
            )}
        </Stack>
    );
}

function KeyValueList({ map, format, emptyLabel }: {
    map: Record<string, number>; format: (v: number) => string; emptyLabel: string;
}) {
    const entries = Object.entries(map);
    if (entries.length === 0) {
        return <EmptyState compact accent="slate" icon={<IconDatabaseOff />} title={emptyLabel} />;
    }
    return (
        <Stack gap={0}>
            {entries.map(([key, value], i) => (
                <Group
                    key={key}
                    justify="space-between"
                    wrap="nowrap"
                    py={8}
                    style={i > 0 ? { borderTop: '1px solid var(--surface-border)' } : undefined}
                >
                    <Code style={{ fontSize: 12 }}>{key}</Code>
                    <Text size="sm" fw={600} style={{ fontVariantNumeric: 'tabular-nums' }}>{format(value)}</Text>
                </Group>
            ))}
        </Stack>
    );
}
