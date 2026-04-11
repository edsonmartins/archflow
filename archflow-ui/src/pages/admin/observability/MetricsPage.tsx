import { useEffect, useState } from 'react';
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

const METRIC_OPTIONS = [
    { value: 'latency_ms', label: 'Latency (ms)' },
    { value: 'throughput', label: 'Throughput (count)' },
    { value: 'error_rate', label: 'Error rate' },
];

const BUCKET_OPTIONS = [
    { value: '60', label: '1 min' },
    { value: '300', label: '5 min' },
    { value: '900', label: '15 min' },
];

export default function MetricsPage() {
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
                if (!cancelled) setError(e instanceof Error ? e.message : 'Failed to load metrics');
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => {
            cancelled = true;
        };
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
                    label="Metric"
                    data={METRIC_OPTIONS}
                    value={metric}
                    onChange={(v) => v && setMetric(v)}
                    w={200}
                    data-testid="metric-select"
                />
                <Select
                    label="Bucket"
                    data={BUCKET_OPTIONS}
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
                            {METRIC_OPTIONS.find((m) => m.value === metric)?.label ?? metric}
                        </Title>
                    </Group>
                    {series && (
                        <Badge variant="light">
                            {series.buckets.length} buckets
                        </Badge>
                    )}
                </Group>
                {series && series.values.length > 0 ? (
                    <Sparkline values={series.values} width={720} height={120} color="#0D9488" strokeWidth={2} />
                ) : (
                    <Text size="sm" c="dimmed">
                        No samples in the selected window.
                    </Text>
                )}
            </Card>

            {snapshot && (
                <SimpleGrid cols={{ base: 1, md: 2 }} spacing="md">
                    <Paper withBorder p="md" radius="md">
                        <Title order={5} mb="xs">Counters</Title>
                        <KeyValueTable map={snapshot.counters} format={(v) => String(v)} />
                    </Paper>
                    <Paper withBorder p="md" radius="md">
                        <Title order={5} mb="xs">Gauges</Title>
                        <KeyValueTable map={snapshot.values} format={(v) => v.toFixed(2)} />
                    </Paper>
                    <Paper withBorder p="md" radius="md" style={{ gridColumn: '1 / -1' }}>
                        <Title order={5} mb="xs">Histogram stats</Title>
                        {Object.keys(snapshot.stats).length === 0 ? (
                            <Text size="sm" c="dimmed">No histogram data yet.</Text>
                        ) : (
                            <Table striped highlightOnHover>
                                <Table.Thead>
                                    <Table.Tr>
                                        <Table.Th>Metric</Table.Th>
                                        <Table.Th>Count</Table.Th>
                                        <Table.Th>Min</Table.Th>
                                        <Table.Th>Max</Table.Th>
                                        <Table.Th>Mean</Table.Th>
                                        <Table.Th>Median</Table.Th>
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

function KeyValueTable<T>({ map, format }: { map: Record<string, T>; format: (v: T) => string }) {
    const entries = Object.entries(map);
    if (entries.length === 0) {
        return <Text size="sm" c="dimmed">No data.</Text>;
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
