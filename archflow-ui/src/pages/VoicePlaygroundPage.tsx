import { useCallback, useEffect, useRef, useState } from 'react';
import {
    Alert,
    Badge,
    Button,
    Group,
    Paper,
    Select,
    Stack,
    Text,
    Title,
} from '@mantine/core';
import { IconAlertCircle, IconRefresh } from '@tabler/icons-react';
import MicButton from '../components/voice/MicButton';
import Transcript, { type TranscriptTurn } from '../components/voice/Transcript';
import {
    RealtimeClient,
    type RealtimeMessage,
    type RealtimeStatus,
} from '../services/realtime-client';

const PERSONAS = [
    { value: 'order_tracking', label: '🚚 Order tracking' },
    { value: 'complaint', label: '🛡️ Complaint handler' },
    { value: 'sales', label: '💼 Sales assistant' },
    { value: 'general', label: '🤖 General assistant' },
];

/**
 * Voice / Realtime playground.
 *
 * Inspired by PraisonAI's `realtimeclient` example: pick a persona,
 * tap the mic, talk to the agent and watch the transcript update live.
 * The actual realtime adapter is plug-and-play — backend can wire
 * OpenAI Realtime, Gemini Live or any equivalent provider behind the
 * `/realtime/{tenantId}/{personaId}` WebSocket endpoint.
 */
export default function VoicePlaygroundPage() {
    const [persona, setPersona] = useState<string>(PERSONAS[0].value);
    const [status, setStatus] = useState<RealtimeStatus>('idle');
    const [turns, setTurns] = useState<TranscriptTurn[]>([]);
    const [error, setError] = useState<string | null>(null);
    const clientRef = useRef<RealtimeClient | null>(null);

    const ensureClient = useCallback(() => {
        if (clientRef.current) return clientRef.current;
        const tenantId =
            sessionStorage.getItem('archflow_impersonate_tenant') ?? 'default';
        const client = new RealtimeClient({ tenantId, personaId: persona });

        const offStatus = client.onStatus(setStatus);
        const offMessage = client.onMessage((m: RealtimeMessage) => {
            if (m.type === 'transcript') {
                setTurns((prev) => mergeTurn(prev, m.data));
            } else if (m.type === 'error') {
                setError(m.data.message);
            }
        });

        // Track cleanups on the client itself so we can dispose later.
        (client as unknown as { __off: () => void }).__off = () => {
            offStatus();
            offMessage();
        };

        clientRef.current = client;
        return client;
    }, [persona]);

    const handleStart = async () => {
        setError(null);
        const client = ensureClient();
        try {
            await client.start();
        } catch (e) {
            setError(e instanceof Error ? e.message : 'Failed to start session');
        }
    };

    const handleStop = async () => {
        const client = clientRef.current;
        if (!client) return;
        await client.stop();
    };

    const handleReset = () => {
        const client = clientRef.current;
        if (client) {
            client.stop().catch(() => {});
            (client as unknown as { __off?: () => void }).__off?.();
        }
        clientRef.current = null;
        setTurns([]);
        setStatus('idle');
        setError(null);
    };

    // Reset client when persona changes mid-session
    useEffect(() => {
        if (status === 'idle' || status === 'closed') return;
        handleReset();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [persona]);

    // Cleanup on unmount
    useEffect(() => {
        return () => {
            const client = clientRef.current;
            if (client) {
                client.stop().catch(() => {});
                (client as unknown as { __off?: () => void }).__off?.();
            }
        };
    }, []);

    return (
        <Stack p="md" gap="md">
            <Stack gap={4}>
                <Title order={2}>Voice playground</Title>
                <Text size="sm" c="dimmed">
                    Talk to an ArchFlow agent in real time. Pick a persona, tap the mic
                    and speak — your audio is streamed to the realtime adapter and the
                    transcript appears below as the agent responds.
                </Text>
            </Stack>

            <Group gap="sm" wrap="nowrap" align="flex-end">
                <Select
                    label="Persona"
                    description="The agent role used for this session"
                    data={PERSONAS}
                    value={persona}
                    onChange={(v) => v && setPersona(v)}
                    w={260}
                    data-testid="voice-persona"
                />
                <Button
                    variant="default"
                    leftSection={<IconRefresh size={16} />}
                    onClick={handleReset}
                    data-testid="voice-reset"
                >
                    Reset
                </Button>
                <Badge
                    size="lg"
                    variant="dot"
                    color={statusColor(status)}
                    data-testid="voice-status"
                >
                    {status}
                </Badge>
            </Group>

            {error && (
                <Alert color="red" icon={<IconAlertCircle size={16} />}>
                    {error}
                </Alert>
            )}

            <Group align="flex-start" gap="md" wrap="wrap">
                <Paper withBorder radius="md" p="lg" miw={280}>
                    <Stack align="center" gap="md">
                        <MicButton status={status} onStart={handleStart} onStop={handleStop} />
                        <Text size="xs" c="dimmed" ta="center" maw={220}>
                            Audio is streamed as PCM16 24kHz frames over a WebSocket scoped
                            to your tenant. Nothing is recorded server-side beyond the
                            transcript.
                        </Text>
                    </Stack>
                </Paper>
                <Stack style={{ flex: 1, minWidth: 320 }} gap="xs">
                    <Text size="xs" tt="uppercase" c="dimmed" style={{ letterSpacing: 0.5 }}>
                        Transcript
                    </Text>
                    <Transcript turns={turns} />
                </Stack>
            </Group>
        </Stack>
    );
}

function statusColor(status: RealtimeStatus): string {
    switch (status) {
        case 'idle':
        case 'closed':
            return 'gray';
        case 'requesting_mic':
        case 'connecting':
            return 'yellow';
        case 'recording':
            return 'red';
        case 'speaking':
            return 'teal';
        case 'error':
            return 'red';
    }
}

/**
 * Merge a new transcript fragment into the running turn list.
 *
 * Partial fragments (final=false) keep updating the same speaker bubble
 * until a final fragment of the same speaker arrives. Once final, the
 * next non-final fragment of the same speaker starts a brand new turn.
 */
function mergeTurn(
    prev: TranscriptTurn[],
    incoming: { speaker: 'user' | 'agent'; text: string; final: boolean },
): TranscriptTurn[] {
    if (prev.length === 0) {
        return [{ id: makeId(), speaker: incoming.speaker, text: incoming.text, final: incoming.final }];
    }
    const last = prev[prev.length - 1];
    if (last.speaker === incoming.speaker && !last.final) {
        const next = [...prev];
        next[next.length - 1] = {
            ...last,
            text: incoming.text,
            final: incoming.final,
        };
        return next;
    }
    return [
        ...prev,
        { id: makeId(), speaker: incoming.speaker, text: incoming.text, final: incoming.final },
    ];
}

function makeId(): string {
    return `t_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
}
