import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Alert, Group, Progress, SimpleGrid, Skeleton, Stack, Text } from '@mantine/core';
import {
    IconAlertCircle,
    IconBolt,
    IconCheck,
    IconClock,
    IconRadar2,
    IconTrendingUp,
    IconUsersGroup,
} from '@tabler/icons-react';
import Sparkline from '../../../components/charts/Sparkline';
import EmptyState from '../../../components/EmptyState';
import { SectionCard } from '../../../components/SectionCard';
import { StatCard } from '../../../components/admin/StatCard';
import { observabilityApi, type OverviewDto } from '../../../services/observability-api';

/** Success-rate colour: the same thresholds the persona list always used. */
function rateColor(rate: number): string {
    if (rate >= 0.9) return 'green';
    if (rate >= 0.7) return 'orange';
    return 'red';
}

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
            <Stack gap="lg" aria-busy="true">
                <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="md">
                    {Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} height={128} radius="md" />)}
                </SimpleGrid>
                <Skeleton height={240} radius="md" />
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

    if (!data) return null;

    const errorPct = data.errorRate * 100;

    return (
        <Stack gap="lg">
            <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="md">
                <StatCard
                    label={t('admin.observability.overview.execToday')}
                    value={formatNumber(locale, data.totalExecutionsToday)}
                    icon={IconBolt}
                    accent="green"
                    hint={t('admin.observability.overview.execTodayHint')}
                />
                <StatCard
                    label={t('admin.observability.overview.successRate')}
                    value={`${(data.successRate * 100).toFixed(1)}%`}
                    icon={IconCheck}
                    accent={data.successRate < 0.7 ? 'rose' : data.successRate < 0.9 ? 'amber' : 'green'}
                    color={data.successRate < 0.9 ? rateColor(data.successRate) : undefined}
                    hint={t('admin.observability.overview.errorsPct', { pct: errorPct.toFixed(1) })}
                />
                <StatCard
                    label={t('admin.observability.overview.avgLatency')}
                    value={`${Math.round(data.avgLatencyMs)} ms`}
                    icon={IconClock}
                    accent="teal"
                    hint={t('admin.observability.overview.p95', { ms: Math.round(data.p95LatencyMs) })}
                />
                <StatCard
                    label={t('admin.observability.overview.activeStreams')}
                    value={String(data.activeStreams)}
                    icon={IconRadar2}
                    accent="blue"
                    hint={t('admin.observability.overview.auditEventsToday', {
                        count: formatNumber(locale, data.totalAuditEventsToday),
                    })}
                />
            </SimpleGrid>

            <SimpleGrid cols={{ base: 1, md: 2 }} spacing="md">
                <SectionCard
                    icon={IconTrendingUp}
                    accent="green"
                    title={t('admin.observability.overview.latencyTrend')}
                    description={t('admin.observability.overview.latencyTrendHint')}
                >
                    {data.latencySparkline.length > 0 ? (
                        <Stack gap="xs">
                            <Sparkline
                                values={data.latencySparkline}
                                width={560}
                                height={150}
                                strokeWidth={2}
                                color="var(--mantine-color-green-6)"
                                label={t('admin.observability.overview.latencyTrend')}
                            />
                            <Group justify="space-between">
                                <Text size="xs" c="dimmed">{t('admin.observability.overview.trendStart')}</Text>
                                <Text size="xs" c="dimmed">{t('admin.observability.overview.trendNow')}</Text>
                            </Group>
                        </Stack>
                    ) : (
                        <EmptyState
                            compact
                            accent="green"
                            icon={<IconTrendingUp />}
                            title={t('admin.observability.overview.noTrend')}
                        />
                    )}
                </SectionCard>

                <SectionCard
                    icon={IconUsersGroup}
                    accent="green"
                    title={t('admin.observability.overview.topPersonas')}
                    description={t('admin.observability.overview.topPersonasHint')}
                >
                    {data.topPersonas.length === 0 ? (
                        <EmptyState
                            compact
                            accent="green"
                            icon={<IconUsersGroup />}
                            title={t('admin.observability.overview.noPersonas')}
                        />
                    ) : (
                        <Stack gap="md">
                            {data.topPersonas.map((p) => (
                                <Stack key={p.personaId} gap={6}>
                                    <Group justify="space-between" wrap="nowrap" gap="sm">
                                        <Text size="sm" fw={600} truncate>
                                            {p.personaId}
                                        </Text>
                                        <Group gap="sm" wrap="nowrap">
                                            <Text size="xs" c="dimmed">
                                                {t('admin.observability.overview.exec', { count: p.executionCount })}
                                            </Text>
                                            <Text size="xs" fw={600} c={rateColor(p.successRate)}>
                                                {t('admin.observability.overview.okPct', {
                                                    pct: (p.successRate * 100).toFixed(1),
                                                })}
                                            </Text>
                                        </Group>
                                    </Group>
                                    <Progress
                                        value={p.successRate * 100}
                                        color={rateColor(p.successRate)}
                                        size="sm"
                                        radius="xl"
                                        aria-label={`${p.personaId}: ${(p.successRate * 100).toFixed(1)}%`}
                                    />
                                </Stack>
                            ))}
                        </Stack>
                    )}
                </SectionCard>
            </SimpleGrid>
        </Stack>
    );
}

function formatNumber(locale: string, n: number): string {
    return new Intl.NumberFormat(locale).format(n);
}
