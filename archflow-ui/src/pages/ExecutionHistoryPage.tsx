import {
    Title, Table, Badge, Text, Paper, Stack, LoadingOverlay, Group, Select, Alert,
} from '@mantine/core';
import { IconPlayerPlay, IconCheck, IconX, IconClock, IconAlertCircle } from '@tabler/icons-react';
import { useEffect, useState } from 'react';
import { useWorkflowStore } from '../stores/workflow-store';

const STATUS_CONFIG: Record<string, { color: string; icon: React.ReactNode }> = {
    RUNNING: { color: 'blue', icon: <IconPlayerPlay size={12} /> },
    COMPLETED: { color: 'green', icon: <IconCheck size={12} /> },
    FAILED: { color: 'red', icon: <IconX size={12} /> },
    PAUSED: { color: 'yellow', icon: <IconClock size={12} /> },
    CANCELLED: { color: 'gray', icon: <IconX size={12} /> },
};

function formatDuration(ms: number | null): string {
    if (ms == null) return '-';
    if (ms < 1000) return `${ms}ms`;
    if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
    return `${(ms / 60000).toFixed(1)}m`;
}

export default function ExecutionHistoryPage() {
    const { executions, workflows, loading, error, fetchExecutions, fetchWorkflows } = useWorkflowStore();
    const [filterWorkflow, setFilterWorkflow] = useState<string | null>(null);

    useEffect(() => {
        fetchWorkflows();
        fetchExecutions();
    }, [fetchWorkflows, fetchExecutions]);

    useEffect(() => {
        fetchExecutions(filterWorkflow || undefined);
    }, [filterWorkflow, fetchExecutions]);

    const workflowOptions = workflows.map((w) => ({ value: w.id, label: w.name }));

    return (
        <Stack gap="md" pos="relative">
            <LoadingOverlay visible={loading} />

            {error && (
                <Alert color="red" icon={<IconAlertCircle size={16} />}>
                    {error}
                </Alert>
            )}

            <Group justify="space-between">
                <Title order={3}>Execution History</Title>
                <Select
                    placeholder="All workflows"
                    data={workflowOptions}
                    value={filterWorkflow}
                    onChange={setFilterWorkflow}
                    clearable
                    w={250}
                    size="sm"
                />
            </Group>

            {executions.length === 0 ? (
                <Paper p="xl" withBorder ta="center">
                    <Text c="dimmed">No executions yet.</Text>
                </Paper>
            ) : (
                <Paper withBorder>
                    <Table striped highlightOnHover>
                        <Table.Thead>
                            <Table.Tr>
                                <Table.Th>Execution ID</Table.Th>
                                <Table.Th>Workflow</Table.Th>
                                <Table.Th>Status</Table.Th>
                                <Table.Th>Started</Table.Th>
                                <Table.Th>Duration</Table.Th>
                                <Table.Th>Error</Table.Th>
                            </Table.Tr>
                        </Table.Thead>
                        <Table.Tbody>
                            {executions.map((exec) => {
                                const statusCfg = STATUS_CONFIG[exec.status] || { color: 'gray', icon: null };
                                return (
                                    <Table.Tr key={exec.id}>
                                        <Table.Td>
                                            <Text size="xs" ff="monospace">{exec.id.slice(0, 12)}...</Text>
                                        </Table.Td>
                                        <Table.Td>
                                            <Text size="sm">{exec.workflowName}</Text>
                                        </Table.Td>
                                        <Table.Td>
                                            <Badge
                                                color={statusCfg.color}
                                                size="sm"
                                                leftSection={statusCfg.icon}
                                            >
                                                {exec.status}
                                            </Badge>
                                        </Table.Td>
                                        <Table.Td>
                                            <Text size="xs" c="dimmed">
                                                {new Date(exec.startedAt).toLocaleString()}
                                            </Text>
                                        </Table.Td>
                                        <Table.Td>
                                            <Text size="sm">{formatDuration(exec.duration)}</Text>
                                        </Table.Td>
                                        <Table.Td>
                                            {exec.error && (
                                                <Text size="xs" c="red" lineClamp={1}>{exec.error}</Text>
                                            )}
                                        </Table.Td>
                                    </Table.Tr>
                                );
                            })}
                        </Table.Tbody>
                    </Table>
                </Paper>
            )}
        </Stack>
    );
}
