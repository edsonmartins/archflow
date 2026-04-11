import { Anchor, Badge, Group, Paper, Stack, Text } from '@mantine/core';
import { IconFileText, IconLink } from '@tabler/icons-react';

export interface Citation {
    /** Stable id (used as React key). */
    id: string;
    /** Display title of the source. */
    title: string;
    /** Optional URL — if present the title becomes a link. */
    url?: string;
    /** Optional snippet shown under the title. */
    snippet?: string;
    /** Optional similarity score (0..1) for RAG hits. */
    score?: number;
    /** Optional source type label (e.g. "doc", "web", "kb"). */
    sourceType?: string;
}

interface CitationListProps {
    citations: Citation[];
    /** Title above the list. Defaults to "Sources". */
    label?: string;
}

/**
 * Compact list of citations/sources rendered under an assistant message.
 *
 * Surfaces RAG hits the way Chainlit does in PraisonAI: a small numbered
 * list with title, optional URL, snippet, and a score badge for retrieval
 * quality. Empty arrays render nothing.
 */
export default function CitationList({ citations, label = 'Sources' }: CitationListProps) {
    if (!citations || citations.length === 0) return null;

    return (
        <Paper
            withBorder
            radius="md"
            p="xs"
            style={(theme) => ({ backgroundColor: theme.colors.gray[0] })}
        >
            <Text size="xs" c="dimmed" tt="uppercase" style={{ letterSpacing: 0.5 }} mb={4}>
                {label} ({citations.length})
            </Text>
            <Stack gap={6}>
                {citations.map((c, idx) => (
                    <Group key={c.id} gap={6} align="flex-start" wrap="nowrap">
                        <Badge size="xs" variant="light" color="blue">
                            {idx + 1}
                        </Badge>
                        <Stack gap={2} style={{ flex: 1, minWidth: 0 }}>
                            <Group gap={4} wrap="nowrap">
                                {c.url ? (
                                    <Anchor
                                        href={c.url}
                                        target="_blank"
                                        rel="noopener noreferrer"
                                        size="sm"
                                        style={{ display: 'flex', alignItems: 'center', gap: 4 }}
                                    >
                                        <IconLink size={12} />
                                        {c.title}
                                    </Anchor>
                                ) : (
                                    <Group gap={4} wrap="nowrap">
                                        <IconFileText size={12} />
                                        <Text size="sm" fw={500} truncate>
                                            {c.title}
                                        </Text>
                                    </Group>
                                )}
                                {c.sourceType && (
                                    <Badge size="xs" variant="outline" color="gray">
                                        {c.sourceType}
                                    </Badge>
                                )}
                                {typeof c.score === 'number' && (
                                    <Badge size="xs" variant="light" color="teal">
                                        {(c.score * 100).toFixed(0)}%
                                    </Badge>
                                )}
                            </Group>
                            {c.snippet && (
                                <Text
                                    size="xs"
                                    c="dimmed"
                                    style={{
                                        display: '-webkit-box',
                                        WebkitLineClamp: 2,
                                        WebkitBoxOrient: 'vertical',
                                        overflow: 'hidden',
                                    }}
                                >
                                    {c.snippet}
                                </Text>
                            )}
                        </Stack>
                    </Group>
                ))}
            </Stack>
        </Paper>
    );
}
