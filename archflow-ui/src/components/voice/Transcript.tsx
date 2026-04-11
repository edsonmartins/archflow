import { useEffect, useRef } from 'react';
import { Badge, Group, Paper, ScrollArea, Stack, Text } from '@mantine/core';

export interface TranscriptTurn {
    id: string;
    speaker: 'user' | 'agent';
    text: string;
    /** When false the turn is still being assembled (live partial). */
    final: boolean;
}

interface TranscriptProps {
    turns: TranscriptTurn[];
    /** Optional empty-state placeholder. */
    placeholder?: string;
}

/**
 * Scrollable transcript of a voice session. Each turn renders as a chip
 * + bubble keyed by speaker. Partial (non-final) turns show an italic
 * "…" hint so the user knows the agent is still transcribing.
 */
export default function Transcript({ turns, placeholder }: TranscriptProps) {
    const scrollRef = useRef<HTMLDivElement>(null);

    useEffect(() => {
        const node = scrollRef.current;
        if (node) node.scrollTo({ top: node.scrollHeight, behavior: 'smooth' });
    }, [turns.length, turns[turns.length - 1]?.text]);

    if (turns.length === 0) {
        return (
            <Paper withBorder radius="md" p="md" h={320}>
                <Stack justify="center" align="center" h="100%">
                    <Text size="sm" c="dimmed">
                        {placeholder ?? 'No transcript yet — start the session to begin.'}
                    </Text>
                </Stack>
            </Paper>
        );
    }

    return (
        <Paper withBorder radius="md" h={320} style={{ overflow: 'hidden' }}>
            <ScrollArea h="100%" viewportRef={scrollRef}>
                <Stack p="sm" gap="xs">
                    {turns.map((t) => (
                        <Group
                            key={t.id}
                            justify={t.speaker === 'user' ? 'flex-end' : 'flex-start'}
                            align="flex-end"
                            wrap="nowrap"
                        >
                            <Stack gap={2} maw="80%">
                                <Group gap={4} justify={t.speaker === 'user' ? 'flex-end' : 'flex-start'}>
                                    <Badge
                                        size="xs"
                                        variant="light"
                                        color={t.speaker === 'user' ? 'blue' : 'grape'}
                                    >
                                        {t.speaker}
                                    </Badge>
                                    {!t.final && (
                                        <Text size="xs" c="dimmed" fs="italic">
                                            …
                                        </Text>
                                    )}
                                </Group>
                                <Paper
                                    px="sm"
                                    py="xs"
                                    radius="md"
                                    withBorder
                                    style={(theme) => ({
                                        backgroundColor:
                                            t.speaker === 'user'
                                                ? theme.colors.blue[0]
                                                : theme.colors.grape[0],
                                    })}
                                >
                                    <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
                                        {t.text}
                                    </Text>
                                </Paper>
                            </Stack>
                        </Group>
                    ))}
                </Stack>
            </ScrollArea>
        </Paper>
    );
}
