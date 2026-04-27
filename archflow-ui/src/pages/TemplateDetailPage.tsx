import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
    ActionIcon,
    Alert,
    Badge,
    Button,
    Code,
    Group,
    List,
    Paper,
    SimpleGrid,
    Stack,
    Text,
    Title,
    Tooltip,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
    IconAlertCircle,
    IconArrowLeft,
    IconCheck,
    IconCopy,
    IconRocket,
} from '@tabler/icons-react';
import { templateApi } from '../services/template-api';
import { TEMPLATE_CATEGORIES, type WorkflowTemplate } from '../data/templates';
import { NODE_CATEGORIES } from '../components/FlowCanvas/constants';

export default function TemplateDetailPage() {
    const { id } = useParams<{ id: string }>();
    const navigate = useNavigate();
    const { t } = useTranslation();
    const [template, setTemplate] = useState<WorkflowTemplate | undefined>(() =>
        id ? templateApi.get(id) : undefined,
    );
    const [cloning, setCloning] = useState(false);
    const [error, setError] = useState<string | null>(null);

    // If the user navigated directly to the detail url without hitting the
    // list first the cache may be cold — kick off a preload and update.
    useEffect(() => {
        if (!id || template) return;
        let cancelled = false;
        templateApi
            .list()
            .then(() => {
                if (!cancelled) setTemplate(templateApi.get(id));
            })
            .catch(() => {
                /* fallback already used */
            });
        return () => {
            cancelled = true;
        };
    }, [id, template]);

    if (!template) {
        return (
            <Stack p="md" gap="md">
                <Alert color="red" icon={<IconAlertCircle size={16} />}>
                    {t('templates.detail.notFound')}
                </Alert>
                <Button variant="default" onClick={() => navigate('/templates')}>
                    {t('templates.detail.back')}
                </Button>
            </Stack>
        );
    }

    const cat = TEMPLATE_CATEGORIES[template.category];

    const handleClone = async () => {
        setCloning(true);
        setError(null);
        try {
            const created = await templateApi.clone(template.id);
            notifications.show({
                title: t('templates.detail.created'),
                message: t('templates.detail.createdMsg', { name: template.name }),
                color: 'teal',
                icon: <IconCheck size={16} />,
            });
            navigate(`/editor/${created.id}`);
        } catch (e) {
            setError(e instanceof Error ? e.message : t('templates.detail.cloneFailed'));
        } finally {
            setCloning(false);
        }
    };

    return (
        <Stack p="md" gap="md">
            <Group gap="sm">
                <Tooltip label={t('templates.detail.back')}>
                    <ActionIcon variant="subtle" onClick={() => navigate('/templates')}>
                        <IconArrowLeft size={18} />
                    </ActionIcon>
                </Tooltip>
                <Text fz={42} style={{ lineHeight: 1 }}>
                    {template.icon}
                </Text>
                <Stack gap={0} style={{ flex: 1 }}>
                    <Title order={2}>{template.name}</Title>
                    <Text size="sm" c="dimmed">
                        {template.summary}
                    </Text>
                </Stack>
                <Button
                    leftSection={<IconRocket size={16} />}
                    onClick={handleClone}
                    loading={cloning}
                    data-testid="template-clone"
                >
                    {t('templates.detail.use')}
                </Button>
            </Group>

            <Group gap={4}>
                <Badge size="sm" variant="light" color={cat.color}>
                    {cat.label}
                </Badge>
                <Badge size="sm" variant="light" color="grape">
                    {template.complexity}
                </Badge>
                {template.tags.map((t) => (
                    <Badge key={t} size="sm" variant="outline" color="gray">
                        {t}
                    </Badge>
                ))}
            </Group>

            {error && (
                <Alert color="red" icon={<IconAlertCircle size={16} />}>
                    {error}
                </Alert>
            )}

            <SimpleGrid cols={{ base: 1, lg: 2 }} spacing="md">
                <Paper withBorder p="md" radius="md">
                    <Title order={5} mb="xs">
                        {t('templates.detail.about')}
                    </Title>
                    <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
                        {template.description}
                    </Text>
                </Paper>

                <Paper withBorder p="md" radius="md">
                    <Title order={5} mb="xs">
                        {t('templates.detail.pipeline', { count: template.steps.length })}
                    </Title>
                    <List size="sm" spacing={4}>
                        {template.steps.map((s, idx) => (
                            <List.Item key={s.id}>
                                <Group gap={6} wrap="nowrap">
                                    <Badge size="xs" variant="filled" color="dark">
                                        {idx + 1}
                                    </Badge>
                                    <Text size="sm" fw={500}>
                                        {s.label}
                                    </Text>
                                    <Badge
                                        size="xs"
                                        variant="dot"
                                        color={categoryColor(s.category)}
                                    >
                                        {t(`categories.${s.category}`, { defaultValue: NODE_CATEGORIES[s.category].label })}
                                    </Badge>
                                </Group>
                            </List.Item>
                        ))}
                    </List>
                </Paper>

                {template.variables && Object.keys(template.variables).length > 0 && (
                    <Paper withBorder p="md" radius="md">
                        <Title order={5} mb="xs">
                            {t('templates.detail.variables')}
                        </Title>
                        <Stack gap={4}>
                            {Object.entries(template.variables).map(([k, v]) => (
                                <Group key={k} gap={6} wrap="nowrap">
                                    <Code>{`{{${k}}}`}</Code>
                                    <Text size="xs" c="dimmed">
                                        →
                                    </Text>
                                    <Text size="sm">{v}</Text>
                                </Group>
                            ))}
                        </Stack>
                    </Paper>
                )}

                <Paper withBorder p="md" radius="md">
                    <Group justify="space-between" mb="xs">
                        <Title order={5}>{t('templates.detail.workflowJson')}</Title>
                        <Tooltip label={t('templates.detail.copy')}>
                            <ActionIcon
                                variant="subtle"
                                onClick={() => {
                                    navigator.clipboard.writeText(
                                        JSON.stringify(
                                            { steps: template.steps, connections: template.connections },
                                            null,
                                            2,
                                        ),
                                    );
                                    notifications.show({
                                        title: t('templates.detail.copied'),
                                        message: t('templates.detail.copiedMsg'),
                                        color: 'teal',
                                    });
                                }}
                            >
                                <IconCopy size={16} />
                            </ActionIcon>
                        </Tooltip>
                    </Group>
                    <Code
                        block
                        style={{
                            maxHeight: 280,
                            overflow: 'auto',
                            fontSize: 11,
                        }}
                    >
                        {JSON.stringify(
                            { steps: template.steps, connections: template.connections },
                            null,
                            2,
                        )}
                    </Code>
                </Paper>
            </SimpleGrid>
        </Stack>
    );
}

function categoryColor(category: keyof typeof NODE_CATEGORIES): string {
    switch (category) {
        case 'agent':
            return 'blue';
        case 'control':
            return 'grape';
        case 'data':
            return 'teal';
        case 'tool':
            return 'red';
        case 'vector':
            return 'orange';
        case 'io':
            return 'gray';
        default:
            return 'gray';
    }
}
