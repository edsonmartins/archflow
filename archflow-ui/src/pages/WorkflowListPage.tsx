import {
    Title, Button, Group, Text, TextInput, Stack, LoadingOverlay, Modal,
    Alert, SimpleGrid, Card, Badge, ActionIcon, ThemeIcon, Tooltip, Code, Center,
} from '@mantine/core';
import {
    IconPlus, IconSearch, IconPlayerPlay, IconPencil, IconTrash,
    IconAlertCircle, IconRobot, IconFileText, IconPlayerPause,
    IconAlertTriangle, IconTopologyRing, IconTemplate, IconSparkles,
} from '@tabler/icons-react';
import { useEffect, useState, useMemo } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
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

    // Creation offers three paths (modal): blank canvas, template gallery,
    // or AI generation (blank workflow + the editor's AI prompt pre-opened).
    const [createOpen, setCreateOpen] = useState(false);
    const createBlank = async (withAi: boolean) => {
        setCreateOpen(false);
        try {
            const created = await createWorkflow({
                metadata: { name: t('workflows.untitledName'), description: '', version: '1.0.0', category: '', tags: [] },
                steps: [],
                configuration: {},
            });
            navigate(withAi ? `/editor/${created.id}?ai=1` : `/editor/${created.id}`);
        } catch { /* surfaced via store error state */ }
    };
    const handleNew = () => setCreateOpen(true);

    // /workflows?new=1 opens the create modal; ?new=ai goes straight to the
    // AI path (both used by the dashboard's quick actions). Strip the param
    // so refresh/back don't re-trigger.
    const [searchParams, setSearchParams] = useSearchParams();
    useEffect(() => {
        const mode = searchParams.get('new');
        if (!mode) return;
        const next = new URLSearchParams(searchParams);
        next.delete('new');
        setSearchParams(next, { replace: true });
        if (mode === 'ai') createBlank(true);
        else setCreateOpen(true);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [searchParams]);

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
                            <Group mt="xs" gap="sm">
                                <Button leftSection={<IconPlus size={16} />} onClick={handleNew}>
                                    {t('workflows.createWorkflow')}
                                </Button>
                                <Button
                                    variant="light"
                                    leftSection={<IconTemplate size={16} />}
                                    onClick={() => navigate('/templates')}
                                >
                                    {t('workflows.browseTemplates')}
                                </Button>
                            </Group>
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

            <Modal
                opened={createOpen}
                onClose={() => setCreateOpen(false)}
                title={t('workflows.createModal.title')}
                centered
            >
                <Stack gap="sm">
                    <CreateOption
                        icon={<IconPlus size={20} />}
                        color="blue"
                        title={t('workflows.createModal.blank')}
                        description={t('workflows.createModal.blankHint')}
                        onClick={() => createBlank(false)}
                        testId="create-blank"
                    />
                    <CreateOption
                        icon={<IconSparkles size={20} />}
                        color="grape"
                        title={t('workflows.createModal.ai')}
                        description={t('workflows.createModal.aiHint')}
                        onClick={() => createBlank(true)}
                        testId="create-ai"
                    />
                    <CreateOption
                        icon={<IconTemplate size={20} />}
                        color="teal"
                        title={t('workflows.createModal.template')}
                        description={t('workflows.createModal.templateHint')}
                        onClick={() => { setCreateOpen(false); navigate('/templates'); }}
                        testId="create-template"
                    />
                </Stack>
            </Modal>
        </Stack>
    );
}

function CreateOption({
    icon, color, title, description, onClick, testId,
}: {
    icon: React.ReactNode;
    color: string;
    title: string;
    description: string;
    onClick: () => void;
    testId: string;
}) {
    return (
        <Card
            withBorder
            radius="md"
            padding="sm"
            component="button"
            type="button"
            onClick={onClick}
            data-testid={testId}
            style={{ cursor: 'pointer', textAlign: 'left', width: '100%' }}
        >
            <Group gap="sm" wrap="nowrap">
                <ThemeIcon size={40} radius="md" variant="light" color={color}>
                    {icon}
                </ThemeIcon>
                <Stack gap={0}>
                    <Text fw={600} size="sm">{title}</Text>
                    <Text size="xs" c="dimmed">{description}</Text>
                </Stack>
            </Group>
        </Card>
    );
}
