import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
    Badge,
    Card,
    Center,
    Chip,
    Group,
    Loader,
    Select,
    SimpleGrid,
    Stack,
    Text,
    TextInput,
    Title,
    UnstyledButton,
} from '@mantine/core';
import { IconSearch } from '@tabler/icons-react';
import {
    TEMPLATE_CATEGORIES,
    type TemplateCategory,
    type WorkflowTemplate,
} from '../data/templates';
import { templateApi } from '../services/template-api';

const COMPLEXITY_COLOR: Record<WorkflowTemplate['complexity'], string> = {
    starter: 'teal',
    intermediate: 'yellow',
    advanced: 'red',
};

type Complexity = WorkflowTemplate['complexity'] | 'all';

const COMPLEXITIES: { value: Complexity; label: string }[] = [
    { value: 'all', label: 'Any complexity' },
    { value: 'starter', label: 'Starter' },
    { value: 'intermediate', label: 'Intermediate' },
    { value: 'advanced', label: 'Advanced' },
];

export default function TemplatesPage() {
    const navigate = useNavigate();
    const [search, setSearch] = useState('');
    const [category, setCategory] = useState<TemplateCategory | 'all'>('all');
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

    const filtered = useMemo(() => {
        const q = search.trim().toLowerCase();
        return all.filter((t) => {
            if (category !== 'all' && t.category !== category) return false;
            if (complexity !== 'all' && t.complexity !== complexity) return false;
            if (!q) return true;
            return (
                t.name.toLowerCase().includes(q) ||
                t.summary.toLowerCase().includes(q) ||
                t.tags.some((tag) => tag.toLowerCase().includes(q))
            );
        });
    }, [all, search, category, complexity]);

    return (
        <Stack p="md" gap="md">
            <Stack gap={4}>
                <Title order={2}>Templates</Title>
                <Text size="sm" c="dimmed">
                    Start from a pre-built workflow and adapt it to your needs. Click any
                    card to see the canvas preview, then clone it into your workspace.
                </Text>
            </Stack>

            <Group gap="sm" wrap="wrap" align="flex-end">
                <TextInput
                    placeholder="Search templates..."
                    leftSection={<IconSearch size={14} />}
                    value={search}
                    onChange={(e) => setSearch(e.currentTarget.value)}
                    style={{ flex: 1, minWidth: 240 }}
                    data-testid="templates-search"
                />
                <Select
                    label="Complexity"
                    data={COMPLEXITIES.map((c) => ({ value: c.value, label: c.label }))}
                    value={complexity}
                    onChange={(v) => setComplexity((v as Complexity) ?? 'all')}
                    w={180}
                    data-testid="templates-complexity"
                />
            </Group>

            <Chip.Group value={category} onChange={(v) => setCategory(v as TemplateCategory | 'all')}>
                <Group gap="xs" wrap="wrap">
                    <Chip value="all" size="xs" variant="light">
                        All
                    </Chip>
                    {(Object.entries(TEMPLATE_CATEGORIES) as [TemplateCategory, { label: string; color: string }][])
                        .map(([key, meta]) => (
                            <Chip key={key} value={key} size="xs" variant="light" color={meta.color}>
                                {meta.label}
                            </Chip>
                        ))}
                </Group>
            </Chip.Group>

            {loading && all.length === 0 ? (
                <Center py="xl">
                    <Loader size="sm" />
                </Center>
            ) : filtered.length === 0 ? (
                <Text size="sm" c="dimmed" ta="center" py="lg">
                    No templates match your search.
                </Text>
            ) : (
                <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} spacing="md">
                    {filtered.map((t) => (
                        <TemplateCard
                            key={t.id}
                            template={t}
                            onClick={() => navigate(`/templates/${t.id}`)}
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
    const cat = TEMPLATE_CATEGORIES[template.category];
    return (
        <UnstyledButton
            onClick={onClick}
            data-testid={`template-card-${template.id}`}
            style={{ display: 'block', height: '100%' }}
        >
            <Card
                withBorder
                radius="md"
                padding="md"
                h="100%"
                style={(theme) => ({
                    transition: 'transform 0.12s, box-shadow 0.12s',
                    cursor: 'pointer',
                })}
                styles={{
                    root: {
                        '&:hover': {
                            transform: 'translateY(-2px)',
                            boxShadow: '0 6px 16px rgba(0,0,0,0.08)',
                        },
                    },
                }}
            >
                <Stack gap="xs" h="100%">
                    <Group justify="space-between" wrap="nowrap" align="flex-start">
                        <Group gap={8} wrap="nowrap">
                            <Text fz={28} style={{ lineHeight: 1 }}>
                                {template.icon}
                            </Text>
                            <Stack gap={0}>
                                <Text fw={600} size="sm">
                                    {template.name}
                                </Text>
                                <Text size="xs" c="dimmed">
                                    {template.steps.length} steps
                                </Text>
                            </Stack>
                        </Group>
                        <Badge size="xs" color={COMPLEXITY_COLOR[template.complexity]} variant="light">
                            {template.complexity}
                        </Badge>
                    </Group>

                    <Text size="xs" c="dimmed" style={{ flex: 1 }}>
                        {template.summary}
                    </Text>

                    <Group gap={4}>
                        <Badge size="xs" variant="dot" color={cat.color}>
                            {cat.label}
                        </Badge>
                        {template.tags.slice(0, 2).map((tag) => (
                            <Badge key={tag} size="xs" variant="outline" color="gray">
                                {tag}
                            </Badge>
                        ))}
                    </Group>
                </Stack>
            </Card>
        </UnstyledButton>
    );
}
