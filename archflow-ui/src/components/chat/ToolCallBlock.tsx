import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActionIcon, Badge, Code, Collapse, Group, Loader, Paper, Stack, Text } from '@mantine/core';
import { IconCheck, IconChevronDown, IconChevronRight, IconTool, IconX } from '@tabler/icons-react';

export type ToolCallStatus = 'running' | 'success' | 'error';

export interface ToolCallView {
    /** Stable id for the tool call (toolCallId from the backend, or a synthetic). */
    id: string;
    name: string;
    status: ToolCallStatus;
    input?: Record<string, unknown> | null;
    output?: unknown;
    error?: string | null;
    durationMs?: number | null;
    /** Optional progress message (set by tool `progress` events). */
    progressMessage?: string | null;
    progressPercent?: number | null;
}

interface ToolCallBlockProps {
    call: ToolCallView;
    /** When true the block starts expanded. Defaults to false. */
    defaultOpen?: boolean;
}

/**
 * Collapsible block that visualizes one tool invocation lifecycle.
 *
 * Mirrors the way Chainlit (PraisonAI's chat layer) renders tool calls:
 * a header with name + status + duration, expanding to show the JSON input
 * and output (or error). The status changes from `running` to `success` or
 * `error` as the matching `tool_start` / `result` / `tool_error` events
 * arrive on the SSE stream.
 */
export default function ToolCallBlock({ call, defaultOpen = false }: ToolCallBlockProps) {
    const { t } = useTranslation();
    const [open, setOpen] = useState(defaultOpen);

    const statusColor =
        call.status === 'running' ? 'yellow' : call.status === 'success' ? 'teal' : 'red';

    return (
        <Paper
            withBorder
            radius="md"
            p="xs"
            style={(theme) => ({
                backgroundColor: theme.colors.dark[0],
                borderColor: theme.colors[statusColor][2],
            })}
        >
            <Group
                justify="space-between"
                wrap="nowrap"
                gap="xs"
                style={{ cursor: 'pointer' }}
                onClick={() => setOpen((o) => !o)}
            >
                <Group gap={6} wrap="nowrap" style={{ minWidth: 0, flex: 1 }}>
                    <ActionIcon size="xs" variant="subtle" aria-label={t('chat.toolCall.toggle')}>
                        {open ? <IconChevronDown size={14} /> : <IconChevronRight size={14} />}
                    </ActionIcon>
                    <IconTool size={14} />
                    <Text size="sm" fw={500} truncate>
                        {call.name}
                    </Text>
                    <Badge size="xs" variant="light" color={statusColor}>
                        {call.status === 'running' && (
                            <Group gap={4} wrap="nowrap">
                                <Loader size={8} color={statusColor} />
                                {t('chat.toolCall.running')}
                            </Group>
                        )}
                        {call.status === 'success' && (
                            <Group gap={4} wrap="nowrap">
                                <IconCheck size={10} />
                                {t('chat.toolCall.success')}
                            </Group>
                        )}
                        {call.status === 'error' && (
                            <Group gap={4} wrap="nowrap">
                                <IconX size={10} />
                                {t('chat.toolCall.error')}
                            </Group>
                        )}
                    </Badge>
                </Group>

                {typeof call.durationMs === 'number' && call.durationMs >= 0 && (
                    <Text size="xs" c="dimmed" style={{ fontFamily: 'DM Mono, monospace' }}>
                        {formatDuration(call.durationMs)}
                    </Text>
                )}
            </Group>

            {call.status === 'running' && call.progressMessage && (
                <Text size="xs" c="dimmed" mt={4}>
                    {call.progressMessage}
                    {typeof call.progressPercent === 'number' && ` (${call.progressPercent}%)`}
                </Text>
            )}

            <Collapse in={open}>
                <Stack gap={6} mt="xs">
                    {call.input && Object.keys(call.input).length > 0 && (
                        <Section label={t('chat.toolCall.input')}>
                            <Code block>{prettyJson(call.input)}</Code>
                        </Section>
                    )}

                    {call.status === 'success' && call.output !== undefined && (
                        <Section label={t('chat.toolCall.output')}>
                            <Code block>{prettyJson(call.output)}</Code>
                        </Section>
                    )}

                    {call.status === 'error' && call.error && (
                        <Section label={t('chat.toolCall.errorLabel')}>
                            <Code block c="red">
                                {call.error}
                            </Code>
                        </Section>
                    )}
                </Stack>
            </Collapse>
        </Paper>
    );
}

function Section({ label, children }: { label: string; children: React.ReactNode }) {
    return (
        <Stack gap={2}>
            <Text size="xs" c="dimmed" tt="uppercase" style={{ letterSpacing: 0.5 }}>
                {label}
            </Text>
            {children}
        </Stack>
    );
}

function prettyJson(value: unknown): string {
    if (value === null || value === undefined) return '';
    if (typeof value === 'string') return value;
    try {
        return JSON.stringify(value, null, 2);
    } catch {
        return String(value);
    }
}

function formatDuration(ms: number): string {
    if (ms < 1000) return `${Math.round(ms)}ms`;
    const seconds = ms / 1000;
    if (seconds < 60) return `${seconds.toFixed(seconds >= 10 ? 0 : 1)}s`;
    const min = Math.floor(seconds / 60);
    const rem = Math.round(seconds % 60);
    return `${min}m ${rem}s`;
}
