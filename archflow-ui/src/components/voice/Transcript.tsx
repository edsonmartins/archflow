import { useEffect, useRef } from 'react';
import { Badge, Group, Paper, ScrollArea, Stack, Text } from '@mantine/core';
import { IconMessages } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import EmptyState from '../EmptyState';

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
    const { t } = useTranslation();
    const scrollRef = useRef<HTMLDivElement>(null);

    const lastText = turns[turns.length - 1]?.text;
    useEffect(() => {
        const node = scrollRef.current;
        if (node) node.scrollTo({ top: node.scrollHeight, behavior: 'smooth' });
    }, [turns.length, lastText]);

    if (turns.length === 0) {
        return (
            <Paper withBorder radius="md" h={360} style={{ borderStyle: 'dashed' }}>
                <Stack justify="center" h="100%">
                    <EmptyState
                        compact
                        accent="violet"
                        icon={<IconMessages />}
                        title={placeholder ?? t('voicePlayground.emptyTranscript')}
                        description={t('voicePlayground.emptyTranscriptHint')}
                    />
                </Stack>
            </Paper>
        );
    }

    return (
        <Paper withBorder radius="md" h={360} style={{ overflow: 'hidden' }}>
            <ScrollArea h="100%" viewportRef={scrollRef} role="log" aria-live="polite" aria-relevant="additions" aria-label={t('voicePlayground.transcript')}>
                <Stack p="sm" gap="xs">
                    {turns.map((turn) => (
                        <Group
                            key={turn.id}
                            justify={turn.speaker === 'user' ? 'flex-end' : 'flex-start'}
                            align="flex-end"
                            wrap="nowrap"
                        >
                            <Stack gap={2} maw="80%">
                                <Group gap={4} justify={turn.speaker === 'user' ? 'flex-end' : 'flex-start'}>
                                    <Badge
                                        size="xs"
                                        variant="light"
                                        color={turn.speaker === 'user' ? 'blue' : 'grape'}
                                    >
                                        {t(`voicePlayground.speaker.${turn.speaker}`)}
                                    </Badge>
                                    {!turn.final && (
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
                                    style={{
                                        backgroundColor:
                                            turn.speaker === 'user'
                                                ? 'var(--mantine-color-blue-light)'
                                                : 'var(--mantine-color-grape-light)',
                                        borderColor: 'transparent',
                                    }}
                                >
                                    <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
                                        {turn.text}
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
