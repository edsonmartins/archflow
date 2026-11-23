import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Badge, Button, Group, Loader, ScrollArea, Stack, Text, TextInput } from '@mantine/core'
import {
    IconActivity, IconPlayerPlay, IconPlayerStop, IconPlugConnected, IconTrash,
} from '@tabler/icons-react'
import { PageHeader } from '../components/PageHeader'
import { SectionCard } from '../components/SectionCard'
import EmptyState from '../components/EmptyState'
import { runAgUiWorkflow, type AgUiEvent } from '../services/agui-client'
import { runOutcomeOf, type RunOutcome } from '../lib/runOutcome'

/** Chave i18n (agui.outcome.*) e cor do badge para cada desfecho. */
const RUNNER_OUTCOME: Record<RunOutcome, 'finished' | 'failed' | 'cancelled' | 'paused' | 'unknown'> = {
    completed: 'finished', failed: 'failed', cancelled: 'cancelled', paused: 'paused', unknown: 'unknown',
}
const OUTCOME_COLOR: Record<string, string> = {
    finished: 'teal', failed: 'red', error: 'red', cancelled: 'gray', paused: 'yellow', unknown: 'gray',
}

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
    const viewportRef = useRef<HTMLDivElement>(null)

    // Console behaviour: keep the newest event in view while the stream grows.
    useEffect(() => {
        const node = viewportRef.current
        if (node) node.scrollTo({ top: node.scrollHeight })
    }, [events.length])

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

    // O desfecho vem do result.status do RUN_FINISHED, não só do tipo: um fluxo FAILED (passo sem
    // chave de API) ou PAUSED (aprovação humana) também termina com RUN_FINISHED.
    const lastEvent = events[events.length - 1]
    const outcome = !lastEvent ? null
        : lastEvent.type === 'RUN_ERROR' ? 'error'
        : lastEvent.type === 'RUN_FINISHED' ? RUNNER_OUTCOME[runOutcomeOf(lastEvent)]
        : null

    return (
        <Stack p="md" gap="lg">
            <PageHeader
                icon={IconPlugConnected}
                accent="violet"
                title={t('agui.title')}
                subtitle={t('agui.subtitle')}
            />

            <SectionCard
                icon={IconPlayerPlay}
                accent="violet"
                title={t('agui.requestTitle')}
                description={t('agui.requestHint')}
            >
                <Group align="flex-end" gap="sm">
                    <TextInput
                        size="sm"
                        label={t('agui.workflowId')}
                        value={workflowId}
                        onChange={(e) => setWorkflowId(e.currentTarget.value)}
                        style={{ flex: 1, minWidth: 220 }}
                        styles={{ input: { fontFamily: 'var(--font-mono)' } }}
                        data-testid="agui-workflow-id"
                    />
                    {running ? (
                        <Button color="red" leftSection={<IconPlayerStop size={16} />} onClick={stop}>
                            {t('agui.stop')}
                        </Button>
                    ) : (
                        <Button leftSection={<IconPlayerPlay size={16} />} onClick={run} disabled={!workflowId.trim()} data-testid="agui-run">
                            {t('agui.run')}
                        </Button>
                    )}
                </Group>
            </SectionCard>

            <SectionCard
                flush
                icon={IconActivity}
                accent="violet"
                title={t('agui.events')}
                description={t('agui.eventsHint')}
                data-testid="agui-events"
                actions={
                    <>
                        {running && (
                            <Badge variant="light" color="violet" leftSection={<Loader size={10} color="violet" />}>
                                {t('agui.streaming')}
                            </Badge>
                        )}
                        {!running && outcome && (
                            <Badge variant="light" color={OUTCOME_COLOR[outcome]} data-testid="agui-outcome">
                                {t(`agui.outcome.${outcome}`)}
                            </Badge>
                        )}
                        <Badge variant="default">{t('agui.eventCount', { count: events.length })}</Badge>
                        <Button
                            variant="subtle"
                            color="gray"
                            size="xs"
                            leftSection={<IconTrash size={14} />}
                            onClick={() => setEvents([])}
                            disabled={running || events.length === 0}
                        >
                            {t('agui.clear')}
                        </Button>
                    </>
                }
            >
                {events.length === 0 ? (
                    running ? (
                        <Stack align="center" justify="center" gap="sm" mih={240}>
                            <Loader color="violet" />
                            <Text size="sm" c="dimmed">{t('agui.waiting')}</Text>
                        </Stack>
                    ) : (
                        <EmptyState
                            accent="violet"
                            icon={<IconActivity />}
                            title={t('agui.emptyTitle')}
                            description={t('agui.empty')}
                        />
                    )
                ) : (
                    <ScrollArea.Autosize mah={520} viewportRef={viewportRef} type="auto">
                        <Stack
                            gap={0}
                            role="log"
                            aria-live="polite"
                            aria-label={t('agui.events')}
                            style={{ fontFamily: 'var(--font-mono)', background: 'var(--color-background-secondary)' }}
                        >
                            {events.map((event, i) => (
                                <Group
                                    key={i}
                                    gap="sm"
                                    wrap="nowrap"
                                    px="lg"
                                    py={6}
                                    style={{ borderTop: '1px solid var(--color-border-tertiary)' }}
                                >
                                    <Text size="xs" c="dimmed" w={32} ta="right" style={{ flexShrink: 0 }}>
                                        {i + 1}
                                    </Text>
                                    <Badge size="sm" variant="light"
                                           color={event.type === 'RUN_FINISHED'
                                               ? OUTCOME_COLOR[RUNNER_OUTCOME[runOutcomeOf(event)]]
                                               : EVENT_COLOR[event.type] ?? 'gray'}
                                           w={190} style={{ flexShrink: 0 }}>
                                        {event.type}
                                    </Badge>
                                    <Text size="xs" c="dimmed" lineClamp={1} style={{ minWidth: 0 }} title={summarize(event)}>
                                        {summarize(event)}
                                    </Text>
                                </Group>
                            ))}
                        </Stack>
                    </ScrollArea.Autosize>
                )}
            </SectionCard>
        </Stack>
    )
}
