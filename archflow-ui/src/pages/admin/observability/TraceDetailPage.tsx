import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
    ActionIcon,
    Alert,
    Badge,
    Center,
    Code,
    Group,
    Loader,
    Paper,
    ScrollArea,
    Stack,
    Table,
    Text,
    Title,
    Tooltip,
} from '@mantine/core';
import { IconAlertCircle, IconArrowLeft } from '@tabler/icons-react';
import { observabilityApi, type TraceDetailDto } from '../../../services/observability-api';

export default function TraceDetailPage() {
    const { id } = useParams<{ id: string }>();
    const navigate = useNavigate();
    const [trace, setTrace] = useState<TraceDetailDto | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (!id) return;
        let cancelled = false;
        setLoading(true);
        observabilityApi
            .getTrace(id)
            .then((t) => {
                if (!cancelled) setTrace(t);
            })
            .catch((e) => {
                if (!cancelled) setError(e instanceof Error ? e.message : 'Failed to load trace');
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => {
            cancelled = true;
        };
    }, [id]);

    if (loading) {
        return (
            <Center py="xl">
                <Loader size="sm" />
            </Center>
        );
    }

    if (error || !trace) {
        return (
            <Alert color="red" icon={<IconAlertCircle size={16} />}>
                {error ?? 'Trace not found.'}
            </Alert>
        );
    }

    const totalMs = trace.durationMs || 1;
    const traceStart = new Date(trace.startedAt).getTime();

    return (
        <Stack gap="md">
            <Group gap="sm">
                <Tooltip label="Back to traces">
                    <ActionIcon variant="subtle" onClick={() => navigate('/admin/observability/traces')}>
                        <IconArrowLeft size={18} />
                    </ActionIcon>
                </Tooltip>
                <Stack gap={0} style={{ flex: 1 }}>
                    <Title order={3}>Trace {trace.traceId.slice(0, 12)}</Title>
                    <Group gap={6}>
                        <Text size="xs" c="dimmed" ff="DM Mono, monospace">
                            {trace.traceId}
                        </Text>
                        <Badge size="xs" variant="light" color={trace.status === 'OK' ? 'teal' : 'red'}>
                            {trace.status}
                        </Badge>
                        {trace.personaId && (
                            <Badge size="xs" variant="outline" color="grape">
                                {trace.personaId}
                            </Badge>
                        )}
                    </Group>
                </Stack>
            </Group>

            <Paper withBorder radius="md" p="md">
                <Group gap="xl">
                    <Meta label="Tenant" value={trace.tenantId ?? '—'} />
                    <Meta label="Flow" value={trace.flowId ?? '—'} />
                    <Meta label="Execution" value={trace.executionId ?? '—'} />
                    <Meta label="Duration" value={`${trace.durationMs} ms`} />
                    <Meta label="Spans" value={String(trace.spans.length)} />
                </Group>
                {trace.error && (
                    <Alert mt="xs" color="red" icon={<IconAlertCircle size={14} />} variant="light">
                        {trace.error}
                    </Alert>
                )}
            </Paper>

            <Paper withBorder radius="md" p="md">
                <Title order={5} mb="xs">
                    Span timeline
                </Title>
                {trace.spans.length === 0 ? (
                    <Text size="sm" c="dimmed">
                        No spans captured for this trace.
                    </Text>
                ) : (
                    <Stack gap={6}>
                        {trace.spans.map((span) => {
                            const spanStart = new Date(span.startedAt).getTime();
                            const offsetPct = Math.max(0, Math.min(100, ((spanStart - traceStart) / totalMs) * 100));
                            const widthPct = Math.max(0.5, Math.min(100 - offsetPct, (span.durationMs / totalMs) * 100));
                            return (
                                <div key={span.spanId}>
                                    <Group justify="space-between" mb={2}>
                                        <Group gap={6}>
                                            <Text size="xs" ff="DM Mono, monospace">
                                                {span.name}
                                            </Text>
                                            <Badge size="xs" variant="outline" color="gray">
                                                {span.kind}
                                            </Badge>
                                            {span.status !== 'OK' && (
                                                <Badge size="xs" variant="light" color="red">
                                                    {span.status}
                                                </Badge>
                                            )}
                                        </Group>
                                        <Text size="xs" c="dimmed">
                                            {span.durationMs} ms
                                        </Text>
                                    </Group>
                                    <div
                                        style={{
                                            position: 'relative',
                                            height: 16,
                                            background: 'var(--mantine-color-gray-1)',
                                            borderRadius: 4,
                                            overflow: 'hidden',
                                        }}
                                    >
                                        <div
                                            style={{
                                                position: 'absolute',
                                                left: `${offsetPct}%`,
                                                width: `${widthPct}%`,
                                                top: 0,
                                                bottom: 0,
                                                background:
                                                    span.status === 'OK'
                                                        ? 'var(--mantine-color-teal-5)'
                                                        : 'var(--mantine-color-red-5)',
                                                borderRadius: 4,
                                            }}
                                            title={`${span.name} — ${span.durationMs}ms`}
                                        />
                                    </div>
                                </div>
                            );
                        })}
                    </Stack>
                )}
            </Paper>

            <Paper withBorder radius="md" p="md">
                <Title order={5} mb="xs">
                    Span attributes
                </Title>
                <ScrollArea h={240}>
                    <Table striped>
                        <Table.Thead>
                            <Table.Tr>
                                <Table.Th>Span</Table.Th>
                                <Table.Th>Attribute</Table.Th>
                                <Table.Th>Value</Table.Th>
                            </Table.Tr>
                        </Table.Thead>
                        <Table.Tbody>
                            {trace.spans.flatMap((span) =>
                                Object.entries(span.attributes).map(([key, value]) => (
                                    <Table.Tr key={`${span.spanId}-${key}`}>
                                        <Table.Td>
                                            <Text size="xs" ff="DM Mono, monospace">
                                                {span.name}
                                            </Text>
                                        </Table.Td>
                                        <Table.Td>
                                            <Code>{key}</Code>
                                        </Table.Td>
                                        <Table.Td>{value}</Table.Td>
                                    </Table.Tr>
                                )),
                            )}
                        </Table.Tbody>
                    </Table>
                </ScrollArea>
            </Paper>
        </Stack>
    );
}

function Meta({ label, value }: { label: string; value: string }) {
    return (
        <Stack gap={2}>
            <Text size="xs" c="dimmed" tt="uppercase" style={{ letterSpacing: 0.5 }}>
                {label}
            </Text>
            <Text size="sm" ff="DM Mono, monospace">
                {value}
            </Text>
        </Stack>
    );
}
