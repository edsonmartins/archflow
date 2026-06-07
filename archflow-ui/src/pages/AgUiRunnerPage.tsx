import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Badge, Button, Group, Paper, Stack, Text, TextInput } from '@mantine/core'
import { IconPlayerPlay, IconPlugConnected, IconPlayerStop } from '@tabler/icons-react'
import { PageHeader } from '../components/PageHeader'
import { runAgUiWorkflow, type AgUiEvent } from '../services/agui-client'

/** Colour per AG-UI event type for the live log. */
const EVENT_COLOR: Record<string, string> = {
    RUN_STARTED: 'blue', RUN_FINISHED: 'teal', RUN_ERROR: 'red',
    STEP_STARTED: 'blue', STEP_FINISHED: 'gray',
    TEXT_MESSAGE_START: 'grape', TEXT_MESSAGE_CONTENT: 'grape', TEXT_MESSAGE_CHUNK: 'grape', TEXT_MESSAGE_END: 'grape',
    TOOL_CALL_START: 'orange', TOOL_CALL_RESULT: 'orange',
    STATE_SNAPSHOT: 'cyan', STATE_DELTA: 'cyan', CUSTOM: 'gray',
}

function summarize(event: AgUiEvent): string {
    const rest: Record<string, unknown> = { ...event }
    delete rest.type
    return JSON.stringify(rest)
}

/**
 * Minimal AG-UI runner (ADR-0003 / design-0006): fires a workflow over the AG-UI
 * endpoint and shows the live event stream. This is the verifiable frontend half
 * of P0; a CopilotKit sidebar would consume the same {@link runAgUiWorkflow} seam.
 */
export default function AgUiRunnerPage() {
    const { t } = useTranslation()
    const [workflowId, setWorkflowId] = useState('wf-demo-001')
    const [events, setEvents] = useState<AgUiEvent[]>([])
    const [running, setRunning] = useState(false)
    const cancelRef = useRef<(() => void) | null>(null)

    const run = () => {
        setEvents([])
        setRunning(true)
        cancelRef.current = runAgUiWorkflow(
            workflowId.trim(),
            { messages: [{ role: 'user', content: 'run' }] },
            (event) => {
                setEvents((prev) => [...prev, event])
                if (event.type === 'RUN_FINISHED' || event.type === 'RUN_ERROR') {
                    setRunning(false)
                }
            },
        )
    }

    const stop = () => {
        cancelRef.current?.()
        setRunning(false)
    }

    return (
        <Stack p="md" gap="md" maw={820}>
            <PageHeader
                title={t('agui.title', { defaultValue: 'AG-UI Runner' })}
                subtitle={t('agui.subtitle', { defaultValue: 'Run a workflow over the AG-UI protocol and watch the event stream.' })}
            />

            <Paper withBorder radius="lg" p="lg">
                <Group align="flex-end">
                    <TextInput
                        label={t('agui.workflowId', { defaultValue: 'Workflow ID' })}
                        value={workflowId}
                        onChange={(e) => setWorkflowId(e.currentTarget.value)}
                        style={{ flex: 1 }}
                        data-testid="agui-workflow-id"
                    />
                    {running ? (
                        <Button color="red" leftSection={<IconPlayerStop size={16} />} onClick={stop}>
                            {t('agui.stop', { defaultValue: 'Stop' })}
                        </Button>
                    ) : (
                        <Button leftSection={<IconPlayerPlay size={16} />} onClick={run} disabled={!workflowId.trim()} data-testid="agui-run">
                            {t('agui.run', { defaultValue: 'Run' })}
                        </Button>
                    )}
                </Group>
            </Paper>

            <Paper withBorder radius="lg" p="lg" data-testid="agui-events">
                <Group gap="xs" mb="sm">
                    <IconPlugConnected size={18} />
                    <Text fw={600}>{t('agui.events', { defaultValue: 'AG-UI events' })}</Text>
                    <Badge variant="light">{events.length}</Badge>
                </Group>
                {events.length === 0 ? (
                    <Text size="sm" c="dimmed">{t('agui.empty', { defaultValue: 'No events yet — run a workflow.' })}</Text>
                ) : (
                    <Stack gap={4}>
                        {events.map((event, i) => (
                            <Group key={i} gap="xs" wrap="nowrap">
                                <Badge size="xs" variant="light" color={EVENT_COLOR[event.type] ?? 'gray'}
                                       w={160} style={{ flexShrink: 0 }}>
                                    {event.type}
                                </Badge>
                                <Text size="xs" c="dimmed" lineClamp={1}
                                      style={{ fontFamily: 'var(--mantine-font-family-monospace)' }}>
                                    {summarize(event)}
                                </Text>
                            </Group>
                        ))}
                    </Stack>
                )}
            </Paper>
        </Stack>
    )
}
