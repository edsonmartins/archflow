import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
    Alert,
    Badge,
    Card,
    Center,
    Group,
    Loader,
    Paper,
    SimpleGrid,
    Stack,
    Text,
    Title,
} from '@mantine/core';
import { IconAlertCircle, IconBolt, IconCheck, IconClock, IconRadar2 } from '@tabler/icons-react';
import Sparkline from '../../../components/charts/Sparkline';
import { observabilityApi, type OverviewDto } from '../../../services/observability-api';

export default function ObservabilityOverview() {
    const { t, i18n } = useTranslation();
    const locale = i18n.resolvedLanguage ?? i18n.language;
    const [data, setData] = useState<OverviewDto | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        let cancelled = false;
        setLoading(true);
        observabilityApi
            .getOverview()
            .then((d) => {
                if (!cancelled) setData(d);
            })
            .catch((e) => {
                if (!cancelled) setError(e instanceof Error ? e.message : t('admin.observability.loadFailed'));
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => {
            cancelled = true;
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

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

    if (!data) return null;

    return (
        <Stack gap="md">
            <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }} spacing="md">
                <StatCard
                    label={t('admin.observability.overview.execToday')}
                    value={formatNumber(locale, data.totalExecutionsToday)}
                    icon={<IconBolt size={18} />}
                    sparkline={data.latencySparkline}
                />
                <StatCard
                    label={t('admin.observability.overview.successRate')}
                    value={`${(data.successRate * 100).toFixed(1)}%`}
                    icon={<IconCheck size={18} />}
                    hint={t('admin.observability.overview.errorsPct', { pct: (data.errorRate * 100).toFixed(1) })}
                />
                <StatCard
                    label={t('admin.observability.overview.avgLatency')}
                    value={`${Math.round(data.avgLatencyMs)} ms`}
                    icon={<IconClock size={18} />}
                    hint={t('admin.observability.overview.p95', { ms: Math.round(data.p95LatencyMs) })}
                />
                <StatCard
                    label={t('admin.observability.overview.activeStreams')}
                    value={String(data.activeStreams)}
                    icon={<IconRadar2 size={18} />}
                    hint={t('admin.observability.overview.auditEventsToday', { count: formatNumber(locale, data.totalAuditEventsToday) })}
                />
            </SimpleGrid>

            <Paper withBorder radius="md" p="md">
                <Title order={5} mb="xs">
                    {t('admin.observability.overview.topPersonas')}
                </Title>
                {data.topPersonas.length === 0 ? (
                    <Text size="sm" c="dimmed">
                        {t('admin.observability.overview.noPersonas')}
                    </Text>
                ) : (
                    <Stack gap={6}>
                        {data.topPersonas.map((p) => (
                            <Group key={p.personaId} justify="space-between">
                                <Group gap={6}>
                                    <Badge size="sm" variant="light" color="grape">
                                        {p.personaId}
                                    </Badge>
                                    <Text size="sm" c="dimmed">
                                        {t('admin.observability.overview.exec', { count: p.executionCount })}
                                    </Text>
                                </Group>
                                <Badge
                                    size="sm"
                                    variant="light"
                                    color={p.successRate >= 0.9 ? 'teal' : p.successRate >= 0.7 ? 'yellow' : 'red'}
                                >
                                    {t('admin.observability.overview.okPct', { pct: (p.successRate * 100).toFixed(1) })}
                                </Badge>
                            </Group>
                        ))}
                    </Stack>
                )}
            </Paper>
        </Stack>
    );
}

function StatCard({
    label,
    value,
    icon,
    hint,
    sparkline,
}: {
    label: string;
    value: string;
    icon: React.ReactNode;
    hint?: string;
    sparkline?: number[];
}) {
    return (
        <Card withBorder radius="md" padding="md">
            <Stack gap={4}>
                <Group justify="space-between">
                    <Text size="xs" c="dimmed" tt="uppercase" style={{ letterSpacing: 0.5 }}>
                        {label}
                    </Text>
                    {icon}
                </Group>
                <Text fz={26} fw={600}>
                    {value}
                </Text>
                {sparkline && sparkline.length > 0 && (
                    <Sparkline values={sparkline} width={180} height={28} />
                )}
                {hint && (
                    <Text size="xs" c="dimmed">
                        {hint}
                    </Text>
                )}
            </Stack>
        </Card>
    );
}

function formatNumber(locale: string, n: number): string {
    return new Intl.NumberFormat(locale).format(n);
}
