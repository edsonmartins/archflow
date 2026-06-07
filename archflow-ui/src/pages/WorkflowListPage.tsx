import {
    Title, Button, Group, Text, TextInput, Stack, LoadingOverlay, Modal,
    Alert, SimpleGrid, Card, Badge, ActionIcon, ThemeIcon, Tooltip, Code, Center,
} from '@mantine/core';
import {
    IconPlus, IconSearch, IconPlayerPlay, IconPencil, IconTrash,
    IconAlertCircle, IconRobot, IconFileText, IconPlayerPause,
    IconAlertTriangle, IconTopologyRing,
} from '@tabler/icons-react';
import { useEffect, useState, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useWorkflowStore } from '../stores/workflow-store';
import { clickableRow } from '../components/DataTable';

const STATUS_COLOR: Record<string, string> = {
    active: 'teal',
    draft:  'gray',
    paused: 'yellow',
    failed: 'red',
};

const STATUS_ICON: Record<string, typeof IconRobot> = {
    active: IconRobot,
    draft:  IconFileText,
    paused: IconPlayerPause,
    failed: IconAlertTriangle,
};

export default function WorkflowListPage() {
    const navigate = useNavigate();
    const { t, i18n } = useTranslation();
    const { workflows, loading, error, fetchWorkflows, deleteWorkflow, executeWorkflow, createWorkflow } = useWorkflowStore();
    const [search, setSearch] = useState('');
    const [deleteId, setDeleteId] = useState<string | null>(null);

    useEffect(() => { fetchWorkflows() }, [fetchWorkflows]);

    const filtered = useMemo(() => {
        const q = search.toLowerCase().trim();
        if (!q) return workflows;
        return workflows.filter(w => w.name.toLowerCase().includes(q) || w.description.toLowerCase().includes(q));
    }, [search, workflows]);

    const handleDelete = async () => { if (deleteId) { await deleteWorkflow(deleteId); setDeleteId(null); } };
    const handleExecute = async (id: string) => { try { const eid = await executeWorkflow(id); navigate(`/executions?id=${eid}`); } catch { /* surfaced via store error state */ } };
    const handleNew = async () => {
        try {
            const created = await createWorkflow({
                metadata: { name: t('workflows.untitledName'), description: '', version: '1.0.0', category: '', tags: [] },
                steps: [],
                configuration: {},
            });
            navigate(`/editor/${created.id}`);
        } catch { /* surfaced via store error state */ }
    };

    return (
        <Stack gap="md" p="md" pos="relative">
            <LoadingOverlay visible={loading} zIndex={5} />
            {error && <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />}>{error}</Alert>}

            <Group justify="space-between" wrap="wrap">
                <Title order={2}>{t('workflows.title')}</Title>
                <Button leftSection={<IconPlus size={16} />} onClick={handleNew} data-testid="workflow-new">
                    {t('workflows.newWorkflow')}
                </Button>
            </Group>

            <TextInput
                aria-label={t('workflows.search')}
                placeholder={t('workflows.search')}
                leftSection={<IconSearch size={14} />}
                value={search}
                onChange={e => setSearch(e.currentTarget.value)}
                maw={420}
            />

            {filtered.length === 0 && !loading ? (
                <Center mih={280}>
                    <Stack align="center" gap="xs" maw={320}>
                        <ThemeIcon size={56} radius="xl" variant="light" color="gray">
                            <IconTopologyRing size={30} />
                        </ThemeIcon>
                        <Text fw={600} size="lg" ta="center">
                            {workflows.length === 0 ? t('workflows.empty') : t('workflows.noMatches')}
                        </Text>
                        <Text size="sm" c="dimmed" ta="center">
                            {workflows.length === 0 ? t('workflows.emptyHint') : t('workflows.noMatchesHint')}
                        </Text>
                        {workflows.length === 0 && (
                            <Button mt="xs" leftSection={<IconPlus size={16} />} onClick={() => navigate('/editor')}>
                                {t('workflows.createWorkflow')}
                            </Button>
                        )}
                    </Stack>
                </Center>
            ) : (
                <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} spacing="md">
                    {filtered.map(w => {
                        const color = STATUS_COLOR[w.status] ?? 'gray';
                        const StatusIcon = STATUS_ICON[w.status] ?? IconFileText;
                        return (
                            <Card
                                key={w.id}
                                withBorder
                                radius="lg"
                                padding="md"
                                data-testid={`workflow-card-${w.id}`}
                                aria-label={w.name}
                                {...clickableRow(() => navigate(`/editor/${w.id}`))}
                            >
                                <Group align="flex-start" gap="sm" wrap="nowrap" mb="sm">
                                    <ThemeIcon size={38} radius="md" variant="light" color={color}>
                                        <StatusIcon size={20} />
                                    </ThemeIcon>
                                    <div style={{ minWidth: 0, flex: 1 }}>
                                        <Text fw={600} size="sm" lineClamp={1}>{w.name}</Text>
                                        <Text size="xs" c="dimmed" lineClamp={2}>{w.description}</Text>
                                    </div>
                                </Group>

                                <Group gap="xs" wrap="wrap" mb="sm">
                                    <Badge size="sm" variant="light" color={color} leftSection={<StatusIcon size={11} />}>
                                        {t(`workflows.status.${w.status}`, { defaultValue: w.status.charAt(0).toUpperCase() + w.status.slice(1) })}
                                    </Badge>
                                    <Code>{w.version}</Code>
                                    <Text size="xs" c="dimmed">{t('workflows.stepCount', { count: w.stepCount })}</Text>
                                </Group>

                                <Text size="xs" c="dimmed" mb="sm">
                                    {new Date(w.updatedAt).toLocaleDateString(i18n.resolvedLanguage ?? i18n.language)}
                                </Text>

                                <Group
                                    gap={4}
                                    pt="sm"
                                    style={{ borderTop: '1px solid var(--mantine-color-default-border)' }}
                                    onClick={e => e.stopPropagation()}
                                >
                                    <Tooltip label={t('workflows.actions.execute')}>
                                        <ActionIcon
                                            variant="subtle"
                                            color="gray"
                                            aria-label={t('workflows.actions.execute')}
                                            onClick={() => handleExecute(w.id)}
                                        >
                                            <IconPlayerPlay size={16} />
                                        </ActionIcon>
                                    </Tooltip>
                                    <Tooltip label={t('workflows.actions.edit')}>
                                        <ActionIcon
                                            variant="subtle"
                                            color="gray"
                                            aria-label={t('workflows.actions.edit')}
                                            onClick={() => navigate(`/editor/${w.id}`)}
                                        >
                                            <IconPencil size={16} />
                                        </ActionIcon>
                                    </Tooltip>
                                    <Tooltip label={t('workflows.actions.delete')}>
                                        <ActionIcon
                                            variant="subtle"
                                            color="red"
                                            aria-label={t('workflows.actions.delete')}
                                            onClick={() => setDeleteId(w.id)}
                                        >
                                            <IconTrash size={16} />
                                        </ActionIcon>
                                    </Tooltip>
                                </Group>
                            </Card>
                        );
                    })}
                </SimpleGrid>
            )}

            <Modal opened={!!deleteId} onClose={() => setDeleteId(null)} title={t('workflows.deleteModal.title')} centered>
                <Text size="sm">{t('workflows.deleteModal.message')}</Text>
                <Group justify="flex-end" mt="md">
                    <Button variant="light" onClick={() => setDeleteId(null)}>{t('common.cancel')}</Button>
                    <Button color="red" onClick={handleDelete}>{t('common.delete')}</Button>
                </Group>
            </Modal>
        </Stack>
    );
}
