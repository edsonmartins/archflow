import { useEffect, useMemo, useRef, useState } from 'react';
import {
    Alert,
    Badge,
    Button,
    Code,
    Group,
    Paper,
    ScrollArea,
    Stack,
    Table,
    Text,
    TextInput,
    Title,
} from '@mantine/core';
import { IconAlertCircle, IconPlayerPause, IconPlayerPlay, IconTrash } from '@tabler/icons-react';
import { ArchflowEventStream, type ArchflowEvent, type StreamStatus } from '../../../services/event-stream';

/**
 * Live SSE event viewer for debugging. Reuses {@link ArchflowEventStream}
 * from P0 — the user types a (tenantId, sessionId) and sees every frame
 * arriving in real time. Scrollback is capped at 500 entries.
 */
export default function LiveEventsPage() {
    const [tenantId, setTenantId] = useState('');
    const [sessionId, setSessionId] = useState('');
    const [paused, setPaused] = useState(false);
    const [status, setStatus] = useState<StreamStatus>('idle');
    const [events, setEvents] = useState<ArchflowEvent[]>([]);
    const [error, setError] = useState<string | null>(null);
    const [attached, setAttached] = useState<{ tenantId: string; sessionId: string } | null>(null);
    const streamRef = useRef<ArchflowEventStream | null>(null);

    const canAttach = tenantId.trim().length > 0 && sessionId.trim().length > 0;

    useEffect(() => {
        if (!attached) return;
        const stream = new ArchflowEventStream({
            tenantId: attached.tenantId,
            sessionId: attached.sessionId,
            autoReconnect: true,
        });
        streamRef.current = stream;
        const offStatus = stream.onStatus(setStatus);
        const offEvent = stream.onEvent((evt) => {
            if (paused) return;
            setEvents((prev) => {
                const next = [...prev, evt];
                return next.length > 500 ? next.slice(next.length - 500) : next;
            });
        });
        try {
            stream.open();
        } catch (e) {
            setError(e instanceof Error ? e.message : 'Failed to open stream');
        }
        return () => {
            offStatus();
            offEvent();
            stream.close();
            streamRef.current = null;
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [attached]);

    const handleAttach = () => {
        if (!canAttach) return;
        setError(null);
        setEvents([]);
        setAttached({ tenantId: tenantId.trim(), sessionId: sessionId.trim() });
    };

    const handleDetach = () => {
        streamRef.current?.close();
        setAttached(null);
        setStatus('closed');
    };

    const domainCounts = useMemo(() => {
        const counts: Record<string, number> = {};
        for (const e of events) {
            counts[e.envelope.domain] = (counts[e.envelope.domain] ?? 0) + 1;
        }
        return counts;
    }, [events]);

    return (
        <Stack gap="sm">
            <Paper withBorder p="md" radius="md">
                <Group gap="sm" wrap="wrap" align="flex-end">
                    <TextInput
                        label="Tenant id"
                        value={tenantId}
                        onChange={(e) => setTenantId(e.currentTarget.value)}
                        w={200}
                        placeholder="acme"
                        disabled={!!attached}
                        data-testid="live-tenant"
                    />
                    <TextInput
                        label="Session id"
                        value={sessionId}
                        onChange={(e) => setSessionId(e.currentTarget.value)}
                        w={260}
                        placeholder="conversation-123"
                        disabled={!!attached}
                        data-testid="live-session"
                    />
                    {attached ? (
                        <>
                            <Button
                                variant="default"
                                leftSection={paused ? <IconPlayerPlay size={14} /> : <IconPlayerPause size={14} />}
                                onClick={() => setPaused((p) => !p)}
                                data-testid="live-toggle"
                            >
                                {paused ? 'Resume' : 'Pause'}
                            </Button>
                            <Button
                                variant="default"
                                leftSection={<IconTrash size={14} />}
                                onClick={() => setEvents([])}
                            >
                                Clear
                            </Button>
                            <Button color="red" variant="light" onClick={handleDetach}>
                                Detach
                            </Button>
                        </>
                    ) : (
                        <Button disabled={!canAttach} onClick={handleAttach} data-testid="live-attach">
                            Attach
                        </Button>
                    )}
                    <Badge size="lg" variant="dot" color={statusColor(status)} data-testid="live-status">
                        {status}
                    </Badge>
                </Group>
            </Paper>

            {error && (
                <Alert color="red" icon={<IconAlertCircle size={16} />}>
                    {error}
                </Alert>
            )}

            <Paper withBorder p="md" radius="md">
                <Group justify="space-between" mb="xs">
                    <Title order={5}>Event feed</Title>
                    <Group gap={6}>
                        {Object.entries(domainCounts).map(([domain, count]) => (
                            <Badge key={domain} size="xs" variant="light">
                                {domain}: {count}
                            </Badge>
                        ))}
                    </Group>
                </Group>
                <ScrollArea h={420}>
                    <Table striped highlightOnHover>
                        <Table.Thead>
                            <Table.Tr>
                                <Table.Th>Time</Table.Th>
                                <Table.Th>Domain</Table.Th>
                                <Table.Th>Type</Table.Th>
                                <Table.Th>Payload</Table.Th>
                            </Table.Tr>
                        </Table.Thead>
                        <Table.Tbody>
                            {events.map((e) => (
                                <Table.Tr key={e.envelope.id}>
                                    <Table.Td>
                                        <Text size="xs" c="dimmed">
                                            {formatTime(e.envelope.timestamp)}
                                        </Text>
                                    </Table.Td>
                                    <Table.Td>
                                        <Badge size="xs" variant="outline">
                                            {e.envelope.domain}
                                        </Badge>
                                    </Table.Td>
                                    <Table.Td>
                                        <Code style={{ fontSize: 11 }}>{e.envelope.type}</Code>
                                    </Table.Td>
                                    <Table.Td>
                                        <Text
                                            size="xs"
                                            ff="DM Mono, monospace"
                                            style={{
                                                maxWidth: 440,
                                                overflow: 'hidden',
                                                textOverflow: 'ellipsis',
                                                whiteSpace: 'nowrap',
                                            }}
                                        >
                                            {JSON.stringify(e.data)}
                                        </Text>
                                    </Table.Td>
                                </Table.Tr>
                            ))}
                            {events.length === 0 && (
                                <Table.Tr>
                                    <Table.Td colSpan={4}>
                                        <Text size="sm" c="dimmed" ta="center" py="md">
                                            {attached
                                                ? 'Waiting for events…'
                                                : 'Attach to a tenant/session to start streaming.'}
                                        </Text>
                                    </Table.Td>
                                </Table.Tr>
                            )}
                        </Table.Tbody>
                    </Table>
                </ScrollArea>
            </Paper>
        </Stack>
    );
}

function statusColor(status: StreamStatus): string {
    switch (status) {
        case 'open':
            return 'teal';
        case 'connecting':
        case 'reconnecting':
            return 'yellow';
        case 'error':
            return 'red';
        default:
            return 'gray';
    }
}

function formatTime(iso: string): string {
    try {
        return new Date(iso).toLocaleTimeString();
    } catch {
        return iso;
    }
}
