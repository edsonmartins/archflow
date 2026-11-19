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
    Paper,
    SimpleGrid,
    Stack,
    Text,
    Tooltip,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
    IconAlertCircle,
    IconArrowRight,
    IconBraces,
    IconCategory,
    IconCheck,
    IconCopy,
    IconGauge,
    IconInfoCircle,
    IconListNumbers,
    IconRocket,
    IconStack2,
    IconTemplate,
    IconVariable,
} from '@tabler/icons-react';
import { templateApi } from '../services/template-api';
import type { WorkflowTemplate } from '../data/templates';
import { PageHeader } from '../components/PageHeader';
import { SectionCard } from '../components/SectionCard';
import { StatCard } from '../components/admin/StatCard';
import EmptyState from '../components/EmptyState';
import { categoriaVisivel, rotuloDaComplexidade } from '../components/templates/categoria';
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
            <Stack p="md" gap="lg">
                <PageHeader
                    icon={IconTemplate}
                    accent="blue"
                    title={t('templates.title')}
                    backTo="/templates"
                    backLabel={t('templates.detail.back')}
                />
                <Paper withBorder>
                    <EmptyState
                        icon={<IconAlertCircle />}
                        accent="rose"
                        title={t('templates.detail.notFound')}
                        description={t('templates.detail.notFoundHint')}
                        actionLabel={t('templates.detail.back')}
                        onAction={() => navigate('/templates')}
                    />
                </Paper>
            </Stack>
        );
    }

    const cat = categoriaVisivel(template.category, t);
    const steps = template.steps ?? [];
    const variables = Object.entries(template.variables ?? {});
    const workflowJson = JSON.stringify({ steps, connections: template.connections ?? [] }, null, 2);

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
        <Stack p="md" gap="lg">
            <PageHeader
                icon={IconTemplate}
                accent={cat.accent}
                title={template.name}
                subtitle={template.summary}
                breadcrumbs={[{ label: t('templates.title'), to: '/templates' }, { label: template.name }]}
                actions={
                    <Button
                        leftSection={<IconRocket size={16} />}
                        onClick={handleClone}
                        loading={cloning}
                        data-testid="template-clone"
                    >
                        {t('templates.detail.use')}
                    </Button>
                }
            />

            {error && (
                <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />}>
                    {error}
                </Alert>
            )}

            <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="md">
                <StatCard label={t('templates.detail.stats.steps')} value={steps.length} icon={IconStack2}
                          accent="blue"
                          hint={steps.length > 0 ? t('templates.detail.hints.steps') : t('templates.stepsServer')} />
                <StatCard label={t('templates.detail.stats.category')} value={<Text component="span" fz={20} fw={700}>{cat.rotulo}</Text>}
                          icon={IconCategory} accent={cat.accent} hint={t('templates.detail.hints.category')} />
                <StatCard label={t('templates.detail.stats.complexity')}
                          value={<Text component="span" fz={20} fw={700}>{rotuloDaComplexidade(template.complexity, t)}</Text>}
                          icon={IconGauge} accent="amber" hint={t('templates.detail.hints.complexity')} />
                <StatCard label={t('templates.detail.stats.variables')} value={variables.length} icon={IconVariable}
                          accent="violet" hint={t('templates.detail.hints.variables')} />
            </SimpleGrid>

            <SimpleGrid cols={{ base: 1, lg: 2 }} spacing="md">
                <SectionCard icon={IconInfoCircle} accent="blue" title={t('templates.detail.about')}>
                    <Stack gap="md">
                        <Text size="sm" style={{ whiteSpace: 'pre-wrap' }} lh={1.6}>
                            {template.description}
                        </Text>
                        {(template.tags ?? []).length > 0 && (
                            <Group gap={6}>
                                <Badge size="sm" variant="dot" color={cat.cor}>{cat.rotulo}</Badge>
                                {(template.tags ?? []).map((tag) => (
                                    <Badge key={tag} size="sm" variant="outline" color="gray">
                                        {tag}
                                    </Badge>
                                ))}
                            </Group>
                        )}
                    </Stack>
                </SectionCard>

                <SectionCard
                    icon={IconListNumbers}
                    accent="violet"
                    title={t('templates.detail.pipeline', { count: steps.length })}
                    description={t('templates.detail.pipelineHint')}
                >
                    {steps.length === 0 ? (
                        <EmptyState compact icon={<IconStack2 />} accent="violet"
                                    title={t('templates.detail.noSteps')}
                                    description={t('templates.detail.noStepsHint')} />
                    ) : (
                        <Stack gap={6}>
                            {steps.map((s, idx) => (
                                <Group key={s.id} gap="sm" wrap="nowrap" px="sm" py={8}
                                       style={{ borderRadius: 10, background: 'var(--table-head-bg)',
                                           border: '1px solid var(--surface-border)' }}>
                                    <Badge size="md" variant="light" color="gray" radius="sm" w={30} px={0}
                                           style={{ flexShrink: 0 }}>
                                        {idx + 1}
                                    </Badge>
                                    <Text size="sm" fw={500} style={{ flex: 1, minWidth: 0 }} truncate>
                                        {s.label}
                                    </Text>
                                    <Badge size="xs" variant="dot" color={categoryColor(s.category)}
                                           style={{ flexShrink: 0 }}>
                                        {t(`categories.${s.category}`, { defaultValue: NODE_CATEGORIES[s.category]?.label ?? s.category })}
                                    </Badge>
                                </Group>
                            ))}
                        </Stack>
                    )}
                </SectionCard>

                {variables.length > 0 && (
                    <SectionCard icon={IconVariable} accent="amber" title={t('templates.detail.variables')}
                                 description={t('templates.detail.variablesHint')}>
                        <Stack gap={6}>
                            {variables.map(([k, v]) => (
                                <Group key={k} gap="sm" wrap="nowrap">
                                    <Code>{`{{${k}}}`}</Code>
                                    <IconArrowRight size={13} style={{ color: 'var(--mantine-color-dimmed)', flexShrink: 0 }} />
                                    <Text size="sm">{v}</Text>
                                </Group>
                            ))}
                        </Stack>
                    </SectionCard>
                )}

                <SectionCard
                    icon={IconBraces}
                    accent="slate"
                    title={t('templates.detail.workflowJson')}
                    description={t('templates.detail.workflowJsonHint')}
                    actions={
                        <Tooltip label={t('templates.detail.copy')}>
                            <ActionIcon
                                variant="subtle"
                                color="gray"
                                aria-label={t('templates.detail.copy')}
                                onClick={() => {
                                    navigator.clipboard.writeText(workflowJson);
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
                    }
                >
                    <Code block style={{ maxHeight: 280, overflow: 'auto', fontSize: 11 }}>
                        {workflowJson}
                    </Code>
                </SectionCard>
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
