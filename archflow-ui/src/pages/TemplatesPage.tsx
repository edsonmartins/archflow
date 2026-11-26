import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
    Badge,
    Card,
    Chip,
    Group,
    Paper,
    Select,
    SimpleGrid,
    Skeleton,
    Stack,
    Text,
    TextInput,
} from '@mantine/core';
import {
    IconAlertTriangle,
    IconArrowRight,
    IconFilter,
    IconSearch,
    IconStack2,
    IconTemplate,
} from '@tabler/icons-react';
import type { WorkflowTemplate } from '../data/templates';
import { templateApi } from '../services/template-api';
import { PageHeader } from '../components/PageHeader';
import { SectionCard } from '../components/SectionCard';
import EmptyState from '../components/EmptyState';
import {
    COMPLEXITY_COLOR,
    categoriaVisivel,
    categoriasDoCatalogo,
    chaveDaCategoria,
    rotuloDaComplexidade,
    templateCombinaComBusca,
} from '../components/templates/categoria';

type Complexity = WorkflowTemplate['complexity'] | 'all';

const COMPLEXITY_VALUES: Complexity[] = ['all', 'starter', 'intermediate', 'advanced'];

export default function TemplatesPage() {
    const navigate = useNavigate();
    const { t } = useTranslation();
    const complexityLabel = (v: Complexity) =>
        v === 'all' ? t('templates.complexityAny') : rotuloDaComplexidade(v, t);
    const [search, setSearch] = useState('');
    const [category, setCategory] = useState<string>('all');
    const [complexity, setComplexity] = useState<Complexity>('all');
    const [all, setAll] = useState<WorkflowTemplate[]>(() => templateApi.listSync());
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        let cancelled = false;
        templateApi
            .list()
            .then((templates) => {
                if (!cancelled) setAll(templates);
            })
            .catch((err) => console.error('Failed to load templates', err))
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => {
            cancelled = true;
        };
    }, []);

    const categories = useMemo(() => categoriasDoCatalogo(all), [all]);

    const filtered = useMemo(() => all.filter((tpl) => {
        if (category !== 'all' && chaveDaCategoria(tpl.category) !== category) return false;
        if (complexity !== 'all' && tpl.complexity !== complexity) return false;
        return templateCombinaComBusca(tpl, search, t);
    }), [all, search, category, complexity, t]);

    const clearFilters = () => { setSearch(''); setCategory('all'); setComplexity('all'); };

    return (
        <Stack p="md" gap="lg">
            <PageHeader
                icon={IconTemplate}
                accent="blue"
                title={t('templates.title')}
                subtitle={t('templates.subtitle')}
            />

            <SectionCard
                icon={IconFilter}
                accent="blue"
                title={t('templates.catalog')}
                description={t('templates.catalogHint', { shown: filtered.length, total: all.length })}
            >
                <Stack gap="md">
                    <Group gap="sm" wrap="wrap" align="flex-end">
                        <TextInput
                            aria-label={t('templates.search')}
                            placeholder={t('templates.search')}
                            leftSection={<IconSearch size={14} />}
                            value={search}
                            onChange={(e) => setSearch(e.currentTarget.value)}
                            style={{ flex: 1, minWidth: 240 }}
                            size="sm"
                            data-testid="templates-search"
                        />
                        <Select
                            label={t('templates.complexity')}
                            data={COMPLEXITY_VALUES.map((c) => ({ value: c, label: complexityLabel(c) }))}
                            value={complexity}
                            onChange={(v) => setComplexity((v as Complexity) ?? 'all')}
                            w={200}
                            size="sm"
                            allowDeselect={false}
                            data-testid="templates-complexity"
                        />
                    </Group>

                    <Chip.Group value={category} onChange={(v) => setCategory(v as string)}>
                        <Group gap="xs" wrap="wrap">
                            <Chip value="all" size="xs" variant="light">
                                {t('templates.all')}
                            </Chip>
                            {categories.map((key) => {
                                const meta = categoriaVisivel(key, t);
                                return (
                                    <Chip key={key} value={key} size="xs" variant="light" color={meta.cor}>
                                        {meta.rotulo}
                                    </Chip>
                                );
                            })}
                        </Group>
                    </Chip.Group>
                </Stack>
            </SectionCard>

            {loading && all.length === 0 ? (
                <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} spacing="md">
                    {Array.from({ length: 6 }).map((_, i) => (
                        <Skeleton key={i} height={196} radius="md" />
                    ))}
                </SimpleGrid>
            ) : filtered.length === 0 ? (
                <Paper withBorder>
                    <EmptyState
                        icon={<IconTemplate />}
                        accent="blue"
                        title={t('templates.noMatches')}
                        description={t('templates.noMatchesHint')}
                        actionLabel={t('templates.clearFilters')}
                        onAction={clearFilters}
                    />
                </Paper>
            ) : (
                <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} spacing="md">
                    {filtered.map((tpl) => (
                        <TemplateCard
                            key={tpl.id}
                            template={tpl}
                            onClick={() => navigate(`/templates/${tpl.id}`)}
                        />
                    ))}
                </SimpleGrid>
            )}
        </Stack>
    );
}

function TemplateCard({
    template,
    onClick,
}: {
    template: WorkflowTemplate;
    onClick: () => void;
}) {
    const { t } = useTranslation();
    const cat = categoriaVisivel(template.category, t);
    const stepCount = template.steps?.length ?? 0;
    const unsupported = template.unsupportedNodeTypes ?? [];
    // Ausente com passos = ninguém conferiu (backend fora ou sem o endpoint): sem selo pareceria "roda".
    const unverified = template.unsupportedNodeTypes === undefined && stepCount > 0;
    return (
        <Card
            withBorder
            padding="lg"
            component="button"
            type="button"
            className="af-hoverable"
            onClick={onClick}
            data-testid={`template-card-${template.id}`}
            style={{ cursor: 'pointer', textAlign: 'left', width: '100%', height: '100%',
                display: 'flex', flexDirection: 'column' }}
        >
            <Group justify="space-between" wrap="nowrap" align="flex-start" gap="sm" mb="sm">
                <Group gap="sm" wrap="nowrap" style={{ minWidth: 0 }}>
                    <span className="af-stat-icon" data-accent={cat.accent} aria-hidden
                          style={{ width: 42, height: 42, borderRadius: 12, fontSize: 21, lineHeight: 1 }}>
                        {template.icon || '📄'}
                    </span>
                    <Stack gap={2} style={{ minWidth: 0 }}>
                        <Text fw={600} size="sm" lineClamp={2}>
                            {template.name}
                        </Text>
                        <Group gap={4} wrap="nowrap">
                            <IconStack2 size={12} style={{ color: 'var(--mantine-color-dimmed)', flexShrink: 0 }} />
                            <Text size="xs" c="dimmed">
                                {stepCount > 0
                                    ? t('templates.stepsShort', { count: stepCount })
                                    : t('templates.stepsServer')}
                            </Text>
                        </Group>
                    </Stack>
                </Group>
                <Badge size="xs" color={COMPLEXITY_COLOR[template.complexity] ?? 'gray'} variant="light"
                       style={{ flexShrink: 0 }}>
                    {rotuloDaComplexidade(template.complexity, t)}
                </Badge>
            </Group>

            <Text size="xs" c="dimmed" lineClamp={3} style={{ flex: 1 }} mb="md">
                {template.summary}
            </Text>

            {unsupported.length > 0 && (
                // Marca em vez de esconder: o template abre e clona, mas não roda até trocar esses nós.
                <Stack gap={4} mb="sm" data-testid={`template-unsupported-${template.id}`}>
                    <Badge size="xs" color="orange" variant="light"
                           leftSection={<IconAlertTriangle size={11} />}
                           style={{ alignSelf: 'flex-start' }}>
                        {t('templates.unsupportedBadge')}
                    </Badge>
                    <Text size="xs" c="orange" lineClamp={2}>
                        {t('templates.unsupportedShort', { types: unsupported.join(', ') })}
                    </Text>
                </Stack>
            )}

            {unverified && (
                <Badge size="xs" color="gray" variant="light" mb="sm" style={{ alignSelf: 'flex-start' }}
                       leftSection={<IconAlertTriangle size={11} />}
                       data-testid={`template-unverified-${template.id}`}>
                    {t('templates.unverifiedBadge')}
                </Badge>
            )}

            <Group justify="space-between" wrap="nowrap" gap="xs" pt="sm"
                   style={{ borderTop: '1px solid var(--surface-border)' }}>
                <Group gap={4} wrap="nowrap" style={{ minWidth: 0, overflow: 'hidden' }}>
                    <Badge size="xs" variant="dot" color={cat.cor} style={{ flexShrink: 0 }}>
                        {cat.rotulo}
                    </Badge>
                    {(template.tags ?? []).slice(0, 2).map((tag) => (
                        <Badge key={tag} size="xs" variant="outline" color="gray">
                            {tag}
                        </Badge>
                    ))}
                </Group>
                <IconArrowRight size={15} style={{ color: 'var(--mantine-color-dimmed)', flexShrink: 0 }} />
            </Group>
        </Card>
    );
}
