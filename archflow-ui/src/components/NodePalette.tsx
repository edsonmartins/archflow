import { Stack, Text, TextInput, Paper, Group, Badge, ScrollArea, Accordion } from '@mantine/core';
import { IconSearch, IconRobot, IconUser, IconTool, IconGitBranch, IconArrowsShuffle } from '@tabler/icons-react';
import { useState, DragEvent } from 'react';

interface PaletteNode {
    type: string;
    label: string;
    description: string;
    category: string;
    icon: React.ReactNode;
    color: string;
}

const PALETTE_NODES: PaletteNode[] = [
    { type: 'AGENT', label: 'Agent', description: 'Autonomous AI agent', category: 'AI', icon: <IconRobot size={16} />, color: 'red' },
    { type: 'ASSISTANT', label: 'Assistant', description: 'Interactive AI assistant', category: 'AI', icon: <IconUser size={16} />, color: 'blue' },
    { type: 'LLM_CHAT', label: 'LLM Chat', description: 'Chat with language model', category: 'AI', icon: <IconUser size={16} />, color: 'violet' },
    { type: 'TOOL', label: 'Tool', description: 'Execute a tool or function', category: 'Tools', icon: <IconTool size={16} />, color: 'teal' },
    { type: 'FUNCTION', label: 'Function', description: 'Custom function', category: 'Tools', icon: <IconTool size={16} />, color: 'cyan' },
    { type: 'CONDITION', label: 'Condition', description: 'Conditional branching', category: 'Control', icon: <IconGitBranch size={16} />, color: 'orange' },
    { type: 'PARALLEL', label: 'Parallel', description: 'Execute in parallel', category: 'Control', icon: <IconArrowsShuffle size={16} />, color: 'grape' },
    { type: 'LOOP', label: 'Loop', description: 'Iterate over data', category: 'Control', icon: <IconArrowsShuffle size={16} />, color: 'yellow' },
];

export default function NodePalette() {
    const [search, setSearch] = useState('');

    const filtered = PALETTE_NODES.filter(
        (n) => n.label.toLowerCase().includes(search.toLowerCase()) ||
               n.description.toLowerCase().includes(search.toLowerCase())
    );

    const categories = [...new Set(filtered.map((n) => n.category))];

    const handleDragStart = (e: DragEvent, node: PaletteNode) => {
        e.dataTransfer.setData('application/archflow-node', JSON.stringify({
            type: node.type,
            label: node.label,
        }));
        e.dataTransfer.effectAllowed = 'copy';
    };

    return (
        <Stack gap="xs" h="100%">
            <Text fw={600} size="sm">Node Palette</Text>
            <TextInput
                placeholder="Search nodes..."
                leftSection={<IconSearch size={14} />}
                size="xs"
                value={search}
                onChange={(e) => setSearch(e.currentTarget.value)}
            />
            <ScrollArea style={{ flex: 1 }}>
                <Accordion multiple defaultValue={categories} variant="separated" styles={{ item: { border: 'none' } }}>
                    {categories.map((cat) => (
                        <Accordion.Item key={cat} value={cat}>
                            <Accordion.Control>
                                <Text size="xs" fw={600} tt="uppercase" c="dimmed">{cat}</Text>
                            </Accordion.Control>
                            <Accordion.Panel>
                                <Stack gap={4}>
                                    {filtered.filter((n) => n.category === cat).map((node) => (
                                        <Paper
                                            key={node.type}
                                            p="xs"
                                            withBorder
                                            shadow="xs"
                                            style={{ cursor: 'grab' }}
                                            draggable
                                            onDragStart={(e) => handleDragStart(e, node)}
                                        >
                                            <Group gap="xs" wrap="nowrap">
                                                <Badge variant="light" color={node.color} size="sm" p={4}>
                                                    {node.icon}
                                                </Badge>
                                                <div>
                                                    <Text size="xs" fw={500}>{node.label}</Text>
                                                    <Text size="xs" c="dimmed" lineClamp={1}>{node.description}</Text>
                                                </div>
                                            </Group>
                                        </Paper>
                                    ))}
                                </Stack>
                            </Accordion.Panel>
                        </Accordion.Item>
                    ))}
                </Accordion>
            </ScrollArea>
        </Stack>
    );
}
