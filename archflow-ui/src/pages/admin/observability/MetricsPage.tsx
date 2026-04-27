import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
    Alert,
    Badge,
    Card,
    Center,
    Code,
    Group,
    Loader,
    Paper,
    Select,
    SimpleGrid,
    Stack,
    Table,
    Text,
    Title,
} from '@mantine/core';
import { IconAlertCircle, IconChartLine } from '@tabler/icons-react';
import Sparkline from '../../../components/charts/Sparkline';
import {
    observabilityApi,
    type MetricSeriesDto,
    type MetricsSnapshotDto,
} from '../../../services/observability-api';

const METRIC_VALUES = ['latency_ms', 'throughput', 'error_rate'] as const;
const BUCKET_VALUES = ['60', '300', '900'] as const;

export default function MetricsPage() {
    const { t } = useTranslation();
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

    if (loading) {
        return (
            <Center py="xl">
                <Loader size="sm" />
            </Center>
        );
    }

    if (error) {
        return (
            <Alert color="red" icon={<IconAlertCircle size={16} />}>
                {error}
            </Alert>
        );
    }

    return (
        <Stack gap="md">
            <Group wrap="wrap" gap="sm" align="flex-end">
                <Select
                    label={t('admin.observability.metrics.metric')}
                    data={METRIC_VALUES.map((v) => ({ value: v, label: t(`admin.observability.metrics.metricValues.${v}`) }))}
                    value={metric}
                    onChange={(v) => v && setMetric(v)}
                    w={200}
                    data-testid="metric-select"
                />
                <Select
                    label={t('admin.observability.metrics.bucket')}
                    data={BUCKET_VALUES.map((v) => ({ value: v, label: t(`admin.observability.metrics.bucketValues.${v}`) }))}
                    value={bucket}
                    onChange={(v) => v && setBucket(v)}
                    w={140}
                    data-testid="bucket-select"
                />
            </Group>

            <Card withBorder radius="md" padding="md">
                <Group justify="space-between" mb="sm">
                    <Group gap={6}>
                        <IconChartLine size={16} />
                        <Title order={5}>
                            {t(`admin.observability.metrics.metricValues.${metric}`, { defaultValue: metric })}
                        </Title>
                    </Group>
                    {series && (
                        <Badge variant="light">
                            {t('admin.observability.metrics.buckets', { count: series.buckets.length })}
                        </Badge>
                    )}
                </Group>
                {series && series.values.length > 0 ? (
                    <Sparkline values={series.values} width={720} height={120} color="#0D9488" strokeWidth={2} />
                ) : (
                    <Text size="sm" c="dimmed">
                        {t('admin.observability.metrics.noSamples')}
                    </Text>
                )}
            </Card>

            {snapshot && (
                <SimpleGrid cols={{ base: 1, md: 2 }} spacing="md">
                    <Paper withBorder p="md" radius="md">
                        <Title order={5} mb="xs">{t('admin.observability.metrics.counters')}</Title>
                        <KeyValueTable map={snapshot.counters} format={(v) => String(v)} emptyLabel={t('admin.observability.metrics.noData')} />
                    </Paper>
                    <Paper withBorder p="md" radius="md">
                        <Title order={5} mb="xs">{t('admin.observability.metrics.gauges')}</Title>
                        <KeyValueTable map={snapshot.values} format={(v) => v.toFixed(2)} emptyLabel={t('admin.observability.metrics.noData')} />
                    </Paper>
                    <Paper withBorder p="md" radius="md" style={{ gridColumn: '1 / -1' }}>
                        <Title order={5} mb="xs">{t('admin.observability.metrics.histograms')}</Title>
                        {Object.keys(snapshot.stats).length === 0 ? (
                            <Text size="sm" c="dimmed">{t('admin.observability.metrics.noHistograms')}</Text>
                        ) : (
                            <Table striped highlightOnHover>
                                <Table.Thead>
                                    <Table.Tr>
                                        <Table.Th>{t('admin.observability.metrics.histCols.metric')}</Table.Th>
                                        <Table.Th>{t('admin.observability.metrics.histCols.count')}</Table.Th>
                                        <Table.Th>{t('admin.observability.metrics.histCols.min')}</Table.Th>
                                        <Table.Th>{t('admin.observability.metrics.histCols.max')}</Table.Th>
                                        <Table.Th>{t('admin.observability.metrics.histCols.mean')}</Table.Th>
                                        <Table.Th>{t('admin.observability.metrics.histCols.median')}</Table.Th>
                                    </Table.Tr>
                                </Table.Thead>
                                <Table.Tbody>
                                    {Object.entries(snapshot.stats).map(([name, stats]) => (
                                        <Table.Tr key={name}>
                                            <Table.Td><Code>{name}</Code></Table.Td>
                                            <Table.Td>{stats.count}</Table.Td>
                                            <Table.Td>{stats.min.toFixed(2)}</Table.Td>
                                            <Table.Td>{stats.max.toFixed(2)}</Table.Td>
                                            <Table.Td>{stats.mean.toFixed(2)}</Table.Td>
                                            <Table.Td>{stats.median.toFixed(2)}</Table.Td>
                                        </Table.Tr>
                                    ))}
                                </Table.Tbody>
                            </Table>
                        )}
                    </Paper>
                </SimpleGrid>
            )}
        </Stack>
    );
}

function KeyValueTable<T>({ map, format, emptyLabel }: { map: Record<string, T>; format: (v: T) => string; emptyLabel: string }) {
    const entries = Object.entries(map);
    if (entries.length === 0) {
        return <Text size="sm" c="dimmed">{emptyLabel}</Text>;
    }
    return (
        <Stack gap={4}>
            {entries.map(([key, value]) => (
                <Group key={key} justify="space-between">
                    <Code style={{ fontSize: 11 }}>{key}</Code>
                    <Text size="sm" fw={500}>{format(value)}</Text>
                </Group>
            ))}
        </Stack>
    );
}
