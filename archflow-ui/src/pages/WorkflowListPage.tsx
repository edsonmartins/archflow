import {
    Title, Table, Button, Group, Text, Badge, ActionIcon, Tooltip, TextInput, Paper, Stack, LoadingOverlay, Modal,
} from '@mantine/core';
import { IconPlus, IconSearch, IconPlayerPlay, IconPencil, IconTrash } from '@tabler/icons-react';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useWorkflowStore } from '../stores/workflow-store';

const STATUS_COLORS: Record<string, string> = {
    draft: 'gray',
    active: 'green',
    paused: 'yellow',
    failed: 'red',
};

export default function WorkflowListPage() {
    const navigate = useNavigate();
    const { workflows, loading, fetchWorkflows, deleteWorkflow, executeWorkflow } = useWorkflowStore();
    const [search, setSearch] = useState('');
    const [deleteId, setDeleteId] = useState<string | null>(null);

    useEffect(() => {
        fetchWorkflows();
    }, [fetchWorkflows]);

    const filtered = workflows.filter(
        (w) => w.name.toLowerCase().includes(search.toLowerCase()) ||
               w.description.toLowerCase().includes(search.toLowerCase())
    );

    const handleDelete = async () => {
        if (deleteId) {
            await deleteWorkflow(deleteId);
            setDeleteId(null);
        }
    };

    const handleExecute = async (id: string) => {
        try {
            const executionId = await executeWorkflow(id);
            navigate(`/executions?id=${executionId}`);
        } catch {
            // error shown via store
        }
    };

    return (
        <Stack gap="md" pos="relative">
            <LoadingOverlay visible={loading} />

            <Group justify="space-between">
                <Title order={3}>Workflows</Title>
                <Button
                    leftSection={<IconPlus size={16} />}
                    onClick={() => navigate('/editor')}
                >
                    New Workflow
                </Button>
            </Group>

            <TextInput
                placeholder="Search workflows..."
                leftSection={<IconSearch size={16} />}
                value={search}
                onChange={(e) => setSearch(e.currentTarget.value)}
            />

            {filtered.length === 0 ? (
                <Paper p="xl" withBorder ta="center">
                    <Text c="dimmed">
                        {workflows.length === 0 ? 'No workflows yet. Create your first one!' : 'No matching workflows.'}
                    </Text>
                </Paper>
            ) : (
                <Paper withBorder>
                    <Table striped highlightOnHover>
                        <Table.Thead>
                            <Table.Tr>
                                <Table.Th>Name</Table.Th>
                                <Table.Th>Version</Table.Th>
                                <Table.Th>Status</Table.Th>
                                <Table.Th>Steps</Table.Th>
                                <Table.Th>Updated</Table.Th>
                                <Table.Th w={140}>Actions</Table.Th>
                            </Table.Tr>
                        </Table.Thead>
                        <Table.Tbody>
                            {filtered.map((w) => (
                                <Table.Tr key={w.id}>
                                    <Table.Td>
                                        <div>
                                            <Text size="sm" fw={500}>{w.name}</Text>
                                            <Text size="xs" c="dimmed" lineClamp={1}>{w.description}</Text>
                                        </div>
                                    </Table.Td>
                                    <Table.Td><Badge variant="light" size="sm">{w.version}</Badge></Table.Td>
                                    <Table.Td>
                                        <Badge color={STATUS_COLORS[w.status] || 'gray'} size="sm">{w.status}</Badge>
                                    </Table.Td>
                                    <Table.Td><Text size="sm">{w.stepCount}</Text></Table.Td>
                                    <Table.Td>
                                        <Text size="xs" c="dimmed">
                                            {new Date(w.updatedAt).toLocaleDateString()}
                                        </Text>
                                    </Table.Td>
                                    <Table.Td>
                                        <Group gap={4}>
                                            <Tooltip label="Execute">
                                                <ActionIcon
                                                    variant="subtle"
                                                    color="green"
                                                    onClick={() => handleExecute(w.id)}
                                                >
                                                    <IconPlayerPlay size={16} />
                                                </ActionIcon>
                                            </Tooltip>
                                            <Tooltip label="Edit">
                                                <ActionIcon
                                                    variant="subtle"
                                                    onClick={() => navigate(`/editor/${w.id}`)}
                                                >
                                                    <IconPencil size={16} />
                                                </ActionIcon>
                                            </Tooltip>
                                            <Tooltip label="Delete">
                                                <ActionIcon
                                                    variant="subtle"
                                                    color="red"
                                                    onClick={() => setDeleteId(w.id)}
                                                >
                                                    <IconTrash size={16} />
                                                </ActionIcon>
                                            </Tooltip>
                                        </Group>
                                    </Table.Td>
                                </Table.Tr>
                            ))}
                        </Table.Tbody>
                    </Table>
                </Paper>
            )}

            <Modal opened={!!deleteId} onClose={() => setDeleteId(null)} title="Delete Workflow" centered>
                <Text size="sm">Are you sure you want to delete this workflow? This action cannot be undone.</Text>
                <Group justify="flex-end" mt="md">
                    <Button variant="light" onClick={() => setDeleteId(null)}>Cancel</Button>
                    <Button color="red" onClick={handleDelete}>Delete</Button>
                </Group>
            </Modal>
        </Stack>
    );
}
