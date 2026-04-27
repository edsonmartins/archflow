import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
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
import { workflowConfigApi } from '../services/workflow-config-api';

/**
 * Static fallback personas used while the backend call resolves or in
 * offline dev. Kept small and local to avoid a blank dropdown when
 * {@code /workflow/personas} is unreachable.
 */
const FALLBACK_PERSONAS = [
    { value: 'order_tracking', label: '🚚 Order tracking' },
    { value: 'complaint',      label: '🛡️ Complaint handler' },
    { value: 'sales',          label: '💼 Sales assistant' },
    { value: 'general',        label: '🤖 General assistant' },
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
    const { t } = useTranslation();
    const [personas, setPersonas] = useState(FALLBACK_PERSONAS);
    const [persona, setPersona]   = useState<string>(FALLBACK_PERSONAS[0].value);
    const [status, setStatus]     = useState<RealtimeStatus>('idle');
    const [turns, setTurns]       = useState<TranscriptTurn[]>([]);
    const [error, setError]       = useState<string | null>(null);
    const clientRef = useRef<RealtimeClient | null>(null);

    // Pull the real persona list from the backend so this page doesn't
    // drift from `/workflow/personas`. Falls back silently on failure.
    useEffect(() => {
        let cancelled = false;
        workflowConfigApi.getPersonas()
            .then(list => {
                if (cancelled || list.length === 0) return;
                const mapped = list.map(p => ({ value: p.id, label: p.label }));
                setPersonas(mapped);
                // Preserve current selection if still valid; else switch to first.
                setPersona(prev => mapped.some(p => p.value === prev) ? prev : mapped[0].value);
            })
            .catch(() => { /* keep fallback */ });
        return () => { cancelled = true };
    }, []);

    /**
     * Stores the last {@code offStatus + offMessage} combo registered
     * against {@code clientRef}. Kept as a {@link useRef} so we never
     * need to attach the unsubscribe functions to the client instance
     * itself (which required unsafe casts to add an {@code __off}
     * property). Cleanup always goes through {@link disposeListeners}.
     */
    const listenerOffRef = useRef<(() => void) | null>(null);

    const disposeListeners = useCallback(() => {
        const off = listenerOffRef.current;
        if (off) {
            try { off(); } catch { /* ignore — listeners are best-effort */ }
            listenerOffRef.current = null;
        }
    }, []);

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
        listenerOffRef.current = () => {
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
            setError(e instanceof Error ? e.message : t('voicePlayground.startFailed'));
        }
    };

    const handleStop = async () => {
        const client = clientRef.current;
        if (!client) return;
        await client.stop();
    };

    const handleReset = useCallback(() => {
        const client = clientRef.current;
        if (client) {
            client.stop().catch(() => {});
        }
        disposeListeners();
        clientRef.current = null;
        setTurns([]);
        setStatus('idle');
        setError(null);
    }, [disposeListeners]);

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
            }
            disposeListeners();
        };
    }, [disposeListeners]);

    return (
        <Stack p="md" gap="md">
            <Stack gap={4}>
                <Title order={2}>{t('voicePlayground.title')}</Title>
                <Text size="sm" c="dimmed">
                    {t('voicePlayground.subtitle')}
                </Text>
            </Stack>

            <Group gap="sm" wrap="nowrap" align="flex-end">
                <Select
                    label={t('voicePlayground.persona')}
                    description={t('voicePlayground.personaHint')}
                    data={personas}
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
                    {t('voicePlayground.reset')}
                </Button>
                <Badge
                    size="lg"
                    variant="dot"
                    color={statusColor(status)}
                    data-testid="voice-status"
                >
                    {t(`voicePlayground.status.${status}`, { defaultValue: status })}
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
                            {t('voicePlayground.micHint')}
                        </Text>
                    </Stack>
                </Paper>
                <Stack style={{ flex: 1, minWidth: 320 }} gap="xs">
                    <Text size="xs" tt="uppercase" c="dimmed" style={{ letterSpacing: 0.5 }}>
                        {t('voicePlayground.transcript')}
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
