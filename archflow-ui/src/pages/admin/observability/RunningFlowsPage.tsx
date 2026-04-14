import { useCallback, useEffect, useRef, useState } from 'react';
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
function formatDuration(ms: number): string {
    if (ms < 1000) return '< 1s';
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
    const [flows, setFlows] = useState<RunningFlowDto[]>([]);
    const [loading, setLoading] = useState(true);
    const [tick, setTick] = useState(0);
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
                <Text fw={600} size="lg">Running flows</Text>
                <Group gap="xs">
                    <Badge variant="light" color={flows.length > 0 ? 'green' : 'gray'}>
                        {flows.length} active
                    </Badge>
                    <Tooltip label="Refresh now">
                        <ActionIcon variant="subtle" onClick={fetchFlows}>
                            <IconRefresh size={16} />
                        </ActionIcon>
                    </Tooltip>
                </Group>
            </Group>

            {loading && (
                <Center py="xl">
                    <Text c="dimmed" size="sm">Loading…</Text>
                </Center>
            )}

            {!loading && flows.length === 0 && (
                <Center py="xl">
                    <Stack align="center" gap="xs">
                        <Text c="dimmed" size="sm">No flows running right now</Text>
                        <Text c="dimmed" size="xs">The table will auto-refresh every 2 seconds</Text>
                    </Stack>
                </Center>
            )}

            {flows.length > 0 && (
                <Box style={{ overflowX: 'auto' }}>
                    <Table striped highlightOnHover withTableBorder withColumnBorders>
                        <Table.Thead>
                            <Table.Tr>
                                <Table.Th>Flow</Table.Th>
                                <Table.Th>Tenant</Table.Th>
                                <Table.Th>Started</Table.Th>
                                <Table.Th>Current step</Table.Th>
                                <Table.Th>Progress</Table.Th>
                                <Table.Th>Duration</Table.Th>
                                <Table.Th>Actions</Table.Th>
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
                                                {new Date(flow.startedAt).toLocaleTimeString()}
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
                                                {formatDuration(dur)}
                                            </Text>
                                        </Table.Td>
                                        <Table.Td>
                                            <Tooltip label="Cancel flow">
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
                title="Cancel flow?"
                centered
                size="sm"
            >
                <Stack gap="md">
                    <Text size="sm">
                        Are you sure you want to cancel{' '}
                        <Text component="span" fw={600} ff="monospace">
                            {confirmCancel}
                        </Text>
                        ? This action cannot be undone.
                    </Text>
                    <Group justify="flex-end" gap="xs">
                        <Button variant="default" onClick={() => setConfirmCancel(null)}>
                            Keep running
                        </Button>
                        <Button
                            color="red"
                            loading={cancelling}
                            onClick={handleCancelConfirm}
                        >
                            Cancel flow
                        </Button>
                    </Group>
                </Stack>
            </Modal>
        </Stack>
    );
}
