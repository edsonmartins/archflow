import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
    ActionIcon,
    Badge,
    Box,
    Button,
    Center,
    Group,
    Modal,
    Progress,
    Stack,
    Table,
    Text,
    Tooltip,
} from '@mantine/core';
import { IconPlayerStop, IconRefresh } from '@tabler/icons-react';
import { RunningFlowDto, observabilityApi } from '../../../services/observability-api';

const POLL_INTERVAL_MS = 2_000;

/**
 * Formats millisecond duration as "1m 23s" or "45s" or "< 1s".
 */
function formatDuration(ms: number, shortLabel: string): string {
    if (ms < 1000) return shortLabel;
    const s = Math.floor(ms / 1000);
    const m = Math.floor(s / 60);
    const sec = s % 60;
    return m > 0 ? `${m}m ${sec}s` : `${sec}s`;
}

/**
 * Running Flows Dashboard — shows a live table of currently-executing flows.
 *
 * Update strategy (hybrid):
 * 1. Fetch initial list on mount.
 * 2. Poll every 2 seconds for reconciliation.
 * 3. Duration ticks every second client-side via a separate interval.
 * 4. Cancel button with confirmation modal.
 */
export default function RunningFlowsPage() {
    const { t, i18n } = useTranslation();
    const locale = i18n.resolvedLanguage ?? i18n.language;
    const [flows, setFlows] = useState<RunningFlowDto[]>([]);
    const [loading, setLoading] = useState(true);
    const [, setTick] = useState(0);
    const [confirmCancel, setConfirmCancel] = useState<string | null>(null);
    const [cancelling, setCancelling] = useState(false);

    // Fetch from backend
    const fetchFlows = useCallback(async () => {
        try {
            const data = await observabilityApi.listRunningFlows();
            setFlows(data);
        } catch {
            // Gracefully handle unreachable backend
        } finally {
            setLoading(false);
        }
    }, []);

    // Initial fetch + poll
    useEffect(() => {
        fetchFlows();
        const poll = setInterval(fetchFlows, POLL_INTERVAL_MS);
        return () => clearInterval(poll);
    }, [fetchFlows]);

    // Duration tick — increments every second so displayed durations stay live
    useEffect(() => {
        const timer = setInterval(() => setTick((t) => t + 1), 1000);
        return () => clearInterval(timer);
    }, []);

    const handleCancelConfirm = async () => {
        if (!confirmCancel) return;
        setCancelling(true);
        try {
            await observabilityApi.cancelRunningFlow(confirmCancel);
            setFlows((prev) => prev.filter((f) => f.flowId !== confirmCancel));
        } catch {
            // Ignore — next poll will reconcile
        } finally {
            setCancelling(false);
            setConfirmCancel(null);
        }
    };

    // Compute live duration using tick as a signal to re-evaluate
    const liveDuration = (flow: RunningFlowDto) => {
        const started = new Date(flow.startedAt).getTime();
        return Date.now() - started;
    };

    const progressPercent = (flow: RunningFlowDto) => {
        if (flow.stepCount <= 0) return 0;
        if (flow.stepIndex < 0) return 0;
        return Math.round(((flow.stepIndex + 1) / flow.stepCount) * 100);
    };

    return (
        <Stack gap="md">
            <Group justify="space-between">
                <Text fw={600} size="lg">{t('admin.observability.running.title')}</Text>
                <Group gap="xs">
                    <Badge variant="light" color={flows.length > 0 ? 'green' : 'gray'}>
                        {t('admin.observability.running.activeCount', { count: flows.length })}
                    </Badge>
                    <Tooltip label={t('admin.observability.running.refreshNow')}>
                        <ActionIcon variant="subtle" onClick={fetchFlows}>
                            <IconRefresh size={16} />
                        </ActionIcon>
                    </Tooltip>
                </Group>
            </Group>

            {loading && (
                <Center py="xl">
                    <Text c="dimmed" size="sm">{t('admin.observability.running.loading')}</Text>
                </Center>
            )}

            {!loading && flows.length === 0 && (
                <Center py="xl">
                    <Stack align="center" gap="xs">
                        <Text c="dimmed" size="sm">{t('admin.observability.running.empty')}</Text>
                        <Text c="dimmed" size="xs">{t('admin.observability.running.emptyHint')}</Text>
                    </Stack>
                </Center>
            )}

            {flows.length > 0 && (
                <Box style={{ overflowX: 'auto' }}>
                    <Table striped highlightOnHover withTableBorder withColumnBorders>
                        <Table.Thead>
                            <Table.Tr>
                                <Table.Th>{t('admin.observability.running.cols.flow')}</Table.Th>
                                <Table.Th>{t('admin.observability.running.cols.tenant')}</Table.Th>
                                <Table.Th>{t('admin.observability.running.cols.started')}</Table.Th>
                                <Table.Th>{t('admin.observability.running.cols.currentStep')}</Table.Th>
                                <Table.Th>{t('admin.observability.running.cols.progress')}</Table.Th>
                                <Table.Th>{t('admin.observability.running.cols.duration')}</Table.Th>
                                <Table.Th>{t('admin.observability.running.cols.actions')}</Table.Th>
                            </Table.Tr>
                        </Table.Thead>
                        <Table.Tbody>
                            {flows.map((flow) => {
                                const pct = progressPercent(flow);
                                const dur = liveDuration(flow);
                                return (
                                    <Table.Tr key={flow.flowId}>
                                        <Table.Td>
                                            <Text size="sm" fw={500} ff="monospace">
                                                {flow.flowId}
                                            </Text>
                                        </Table.Td>
                                        <Table.Td>
                                            <Text size="sm" c="dimmed">
                                                {flow.tenantId ?? '—'}
                                            </Text>
                                        </Table.Td>
                                        <Table.Td>
                                            <Text size="sm" c="dimmed">
                                                {new Date(flow.startedAt).toLocaleTimeString(locale)}
                                            </Text>
                                        </Table.Td>
                                        <Table.Td>
                                            {flow.currentStepId ? (
                                                <Badge variant="dot" color="blue" size="sm">
                                                    {flow.currentStepId}
                                                </Badge>
                                            ) : (
                                                <Text size="sm" c="dimmed">—</Text>
                                            )}
                                        </Table.Td>
                                        <Table.Td style={{ minWidth: 140 }}>
                                            <Stack gap={4}>
                                                <Progress
                                                    value={pct}
                                                    size="sm"
                                                    color={pct === 100 ? 'green' : 'blue'}
                                                    animated={pct < 100}
                                                />
                                                <Text size="xs" c="dimmed">
                                                    {flow.stepIndex >= 0
                                                        ? `${flow.stepIndex + 1} / ${flow.stepCount}`
                                                        : `0 / ${flow.stepCount}`}
                                                </Text>
                                            </Stack>
                                        </Table.Td>
                                        <Table.Td>
                                            <Text size="sm" ff="monospace">
                                                {formatDuration(dur, t('admin.observability.running.durShort'))}
                                            </Text>
                                        </Table.Td>
                                        <Table.Td>
                                            <Tooltip label={t('admin.observability.running.cancelTooltip')}>
                                                <ActionIcon
                                                    color="red"
                                                    variant="subtle"
                                                    onClick={() => setConfirmCancel(flow.flowId)}
                                                >
                                                    <IconPlayerStop size={16} />
                                                </ActionIcon>
                                            </Tooltip>
                                        </Table.Td>
                                    </Table.Tr>
                                );
                            })}
                        </Table.Tbody>
                    </Table>
                </Box>
            )}

            {/* Cancel confirmation modal */}
            <Modal
                opened={confirmCancel !== null}
                onClose={() => setConfirmCancel(null)}
                title={t('admin.observability.running.cancelTitle')}
                centered
                size="sm"
            >
                <Stack gap="md">
                    <Text size="sm">
                        {t('admin.observability.running.cancelPrompt')}{' '}
                        <Text component="span" fw={600} ff="monospace">
                            {confirmCancel}
                        </Text>
                        {t('admin.observability.running.cancelWarn')}
                    </Text>
                    <Group justify="flex-end" gap="xs">
                        <Button variant="default" onClick={() => setConfirmCancel(null)}>
                            {t('admin.observability.running.keepRunning')}
                        </Button>
                        <Button
                            color="red"
                            loading={cancelling}
                            onClick={handleCancelConfirm}
                        >
                            {t('admin.observability.running.cancelBtn')}
                        </Button>
                    </Group>
                </Stack>
            </Modal>
        </Stack>
    );
}
