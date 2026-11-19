import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
    ActionIcon, Alert, Anchor, Badge, Box, Button, Code, Collapse, Group, Loader,
    Paper, SimpleGrid, Skeleton, Stack, Text,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import {
    IconAlertCircle, IconAlertTriangle, IconBraces, IconChevronDown, IconChevronRight, IconCheck,
    IconCircleCheck, IconCircleDashed, IconClock, IconListDetails, IconLoader2, IconMinus,
    IconPlayerPause, IconPlayerPlay, IconPlayerStop, IconSitemap, IconTimeline, IconTopologyRing, IconX,
} from '@tabler/icons-react'
import { executionApi, workflowApi, ApiError } from '../services/api'
import { JsonViewer } from '../components/JsonViewer'
import { Meta } from '../components/Meta'
import { PageHeader, type Accent } from '../components/PageHeader'
import { SectionCard } from '../components/SectionCard'
import { StatCard } from '../components/admin/StatCard'
import EmptyState from '../components/EmptyState'
import { formatDuration, formatInstant } from '../lib/format'
import { EXECUTION_STATUS_COLOR } from '../lib/executionStatus'

/** A materialized ExecutionPath node (design-0005 step 4). */
interface PathNode {
    pathId: string
    status: string
    parallelBranches?: PathNode[] | null
}

/** Per-step record persisted by the backend StepRecordingListener. */
interface StepRecord {
    stepId: string
    type?: string
    stepIndex?: number
    stepCount?: number
    status: 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED' | string
    startedAt?: string
    finishedAt?: string
    durationMs?: number
    error?: string
}

const tabularNums = { fontVariantNumeric: 'tabular-nums' } as const

const STATUS_ACCENT: Record<string, Accent> = {
    RUNNING: 'blue', COMPLETED: 'teal', FAILED: 'rose', PAUSED: 'amber', CANCELLED: 'slate',
}

const STATUS_ICON: Record<string, typeof IconCircleCheck> = {
    RUNNING: IconLoader2, COMPLETED: IconCircleCheck, FAILED: IconAlertTriangle,
    PAUSED: IconPlayerPause, CANCELLED: IconPlayerStop,
}

// Reuse the shared status→color map; 'STARTED' is a path-node-only alias of RUNNING.
function pathColor(status: string): string {
    return EXECUTION_STATUS_COLOR[status] ?? (status === 'STARTED' ? 'blue' : 'gray')
}

function PathTree({ nodes, depth = 0 }: { nodes: PathNode[]; depth?: number }) {
    return (
        <Stack gap={4}>
            {nodes.map((n, i) => (
                <div key={`${n.pathId}-${i}`}>
                    <Group gap="xs" wrap="nowrap" py={4}
                           style={{ paddingLeft: depth * 20, borderLeft: depth > 0 ? '2px solid var(--surface-border)' : undefined,
                               marginLeft: depth > 0 ? 8 : 0 }}>
                        <Badge size="xs" variant="light" color={pathColor(n.status)}>{n.status}</Badge>
                        <Text size="sm" ff="monospace">{n.pathId}</Text>
                    </Group>
                    {n.parallelBranches && n.parallelBranches.length > 0 && (
                        <PathTree nodes={n.parallelBranches} depth={depth + 1} />
                    )}
                </div>
            ))}
        </Stack>
    )
}

/**
 * Collapsible per-step block in the ToolCallBlock visual language:
 * header with name + status + duration, expanding into timestamps and
 * error detail.
 */
function StepBlock({ step, locale }: { step: StepRecord; locale: string }) {
    const { t } = useTranslation()
    const [open, setOpen] = useState(step.status === 'FAILED')

    const statusColor = EXECUTION_STATUS_COLOR[step.status] ?? 'gray'
    const statusLabel =
        step.status === 'RUNNING' ? t('executionDetail.step.running')
        : step.status === 'COMPLETED' ? t('executionDetail.step.completed')
        : step.status === 'FAILED' ? t('executionDetail.step.failed')
        : step.status === 'SKIPPED' ? t('executionDetail.step.skipped')
        : step.status

    return (
        <Paper withBorder radius="md" px="sm" py="xs" data-testid={`execution-step-${step.stepId}`}
               style={{ borderLeft: `3px solid var(--mantine-color-${statusColor}-6)`, boxShadow: 'none' }}>
            <Group
                justify="space-between"
                wrap="nowrap"
                gap="xs"
                style={{ cursor: 'pointer' }}
                onClick={() => setOpen((o) => !o)}
            >
                <Group gap={6} wrap="nowrap" style={{ minWidth: 0, flex: 1 }}>
                    <ActionIcon size="xs" variant="subtle" aria-label={t('executionDetail.step.toggle')}>
                        {open ? <IconChevronDown size={14} /> : <IconChevronRight size={14} />}
                    </ActionIcon>
                    {typeof step.stepIndex === 'number' && (
                        <Text size="xs" c="dimmed" ff="monospace" style={tabularNums}>
                            {step.stepIndex + 1}{typeof step.stepCount === 'number' ? `/${step.stepCount}` : ''}
                        </Text>
                    )}
                    <Text size="sm" fw={500} truncate>
                        {step.stepId}
                    </Text>
                    {step.type && (
                        <Badge size="xs" variant="outline" color="gray">{step.type}</Badge>
                    )}
                    <Badge size="xs" variant="light" color={statusColor}>
                        <Group gap={4} wrap="nowrap">
                            {step.status === 'RUNNING' && <Loader size={8} color={statusColor} />}
                            {step.status === 'COMPLETED' && <IconCheck size={10} />}
                            {step.status === 'FAILED' && <IconX size={10} />}
                            {step.status === 'SKIPPED' && <IconMinus size={10} />}
                            {statusLabel}
                        </Group>
                    </Badge>
                </Group>

                {typeof step.durationMs === 'number' && (
                    <Text size="xs" c="dimmed" ff="monospace" style={tabularNums}>
                        {formatDuration(step.durationMs)}
                    </Text>
                )}
            </Group>

            <Collapse expanded={open}>
                <Stack gap={6} mt="xs" pl={28}>
                    <Group gap="xl">
                        <Meta label={t('executionDetail.step.startedAt')} value={formatInstant(step.startedAt, locale)} />
                        <Meta label={t('executionDetail.step.finishedAt')} value={formatInstant(step.finishedAt, locale)} />
                    </Group>
                    {step.error && (
                        <Stack gap={2}>
                            <Text size="xs" c="dimmed" tt="uppercase" style={{ letterSpacing: 0.5 }}>
                                {t('executionDetail.step.errorLabel')}
                            </Text>
                            <Code block c="red">{step.error}</Code>
                        </Stack>
                    )}
                </Stack>
            </Collapse>
        </Paper>
    )
}

/**
 * Details a single execution: summary, per-step drill-down (status,
 * timings, error per step), the dynamic-orchestration path tree when
 * present, and a raw-JSON escape hatch. Polls while the execution is
 * still RUNNING so live runs update in place.
 */
export default function ExecutionDetailPage() {
    const { t, i18n } = useTranslation()
    const locale = i18n.resolvedLanguage ?? i18n.language
    const { id = '' } = useParams()
    const navigate = useNavigate()
    const [data, setData] = useState<Record<string, unknown> | null>(null)
    const [err, setErr]   = useState<string | null>(null)
    const [rawOpen, setRawOpen] = useState(false)
    const [rerunning, setRerunning] = useState(false)

    const load = useCallback(async () => {
        try {
            const res = await executionApi.get(id)
            setData(res as unknown as Record<string, unknown>)
            setErr(null)
        } catch (e) {
            setErr(e instanceof ApiError ? e.message : String(e))
        }
    }, [id])

    useEffect(() => { load() }, [load])

    // Live refresh while running.
    const status = String(data?.status ?? '')
    useEffect(() => {
        if (status !== 'RUNNING') return
        const timer = setInterval(load, 2000)
        return () => clearInterval(timer)
    }, [status, load])

    const handleRerun = async () => {
        const workflowId = data?.workflowId as string | undefined
        if (!workflowId) return
        setRerunning(true)
        try {
            const res = await workflowApi.execute(workflowId)
            notifications.show({
                title: t('executionDetail.rerunStarted'),
                message: res.executionId,
                color: 'blue',
            })
            navigate(`/executions/${res.executionId}`)
        } catch (e) {
            notifications.show({
                title: t('executionDetail.rerunFailed'),
                message: e instanceof ApiError ? e.message : String(e),
                color: 'red',
            })
        } finally {
            setRerunning(false)
        }
    }

    const header = (extra?: { subtitle?: string; accent?: Accent }) => (
        <PageHeader
            icon={IconPlayerPlay}
            accent={extra?.accent ?? 'violet'}
            title={t('executionDetail.title', { id: id.slice(0, 12) })}
            subtitle={extra?.subtitle}
            breadcrumbs={[{ label: t('executions.title'), to: '/executions' }, { label: id.slice(0, 12) }]}
            actions={data ? (
                <>
                    {traceId && (
                        <Button component={Link} to={`/admin/observability/traces/${traceId}`} variant="default"
                                leftSection={<IconTimeline size={15} />}>
                            {t('executionDetail.viewTrace')}
                        </Button>
                    )}
                    <Button
                        leftSection={<IconPlayerPlay size={14} />}
                        loading={rerunning}
                        onClick={handleRerun}
                        disabled={!data.workflowId}
                        data-testid="execution-rerun"
                    >
                        {t('executionDetail.rerun')}
                    </Button>
                </>
            ) : undefined}
        />
    )

    const traceId = data?.traceId as string | undefined

    if (err) return (
        <Stack gap="lg" p="md" data-testid="execution-detail-error">
            {header({ accent: 'rose' })}
            <Paper withBorder>
                <EmptyState
                    icon={<IconAlertCircle />}
                    accent="rose"
                    title={t('executionDetail.loadFailed')}
                    description={err}
                    actionLabel={t('common.refresh')}
                    onAction={() => void load()}
                />
            </Paper>
        </Stack>
    )
    if (!data) return (
        <Stack gap="lg" p="md" aria-busy="true">
            {header()}
            <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="md">
                {Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} height={118} radius="md" />)}
            </SimpleGrid>
            <Skeleton height={220} radius="md" />
        </Stack>
    )

    const paths = (data.executionPaths as PathNode[] | undefined) ?? []
    const steps = ((data.steps as StepRecord[] | undefined) ?? [])
        .slice()
        .sort((a, b) => (a.stepIndex ?? 0) - (b.stepIndex ?? 0))
    const execError = data.error as string | null | undefined
    const duration = data.duration as number | null | undefined
    const statusColor = EXECUTION_STATUS_COLOR[status] ?? 'gray'
    const statusAccent: Accent = STATUS_ACCENT[status] ?? 'slate'
    const StatusIcon = STATUS_ICON[status] ?? IconCircleDashed
    const failedSteps = steps.filter((s) => s.status === 'FAILED').length
    const workflowName = String(data.workflowName ?? data.workflowId ?? '—')

    return (
        <Stack gap="lg" p="md" data-testid="execution-detail">
            {header({ subtitle: t('executionDetail.subtitle', { workflow: workflowName }), accent: status === 'FAILED' ? 'rose' : 'violet' })}

            <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="md">
                <StatCard
                    label={t('executionDetail.stats.status')}
                    value={<Text component="span" inherit fz={24} c={statusColor}>
                        {status ? t(`executions.statuses.${status}`, { defaultValue: status }) : '—'}
                    </Text>}
                    icon={StatusIcon}
                    accent={statusAccent}
                    hint={status === 'RUNNING' ? t('executionDetail.hints.live') : t('executionDetail.hints.finished', {
                        when: formatInstant(data.completedAt as string | undefined, locale) })}
                />
                <StatCard
                    label={t('executionDetail.summary.duration')}
                    value={typeof duration === 'number' ? formatDuration(duration) : '—'}
                    icon={IconClock}
                    accent="blue"
                    hint={t('executionDetail.hints.started', {
                        when: formatInstant(data.startedAt as string | undefined, locale) })}
                />
                <StatCard
                    label={t('executionDetail.steps')}
                    value={steps.length}
                    icon={IconListDetails}
                    accent="violet"
                    color={failedSteps > 0 ? 'red' : undefined}
                    hint={failedSteps > 0
                        ? t('executionDetail.hints.failedSteps', { count: failedSteps })
                        : t('executionDetail.hints.steps')}
                />
                <StatCard
                    label={t('executionDetail.summary.workflow')}
                    value={<Text component="span" inherit fz={18} lineClamp={1} title={workflowName}>{workflowName}</Text>}
                    icon={IconTopologyRing}
                    accent="teal"
                    hint={data.workflowId ? (
                        <Anchor component={Link} to={`/editor/${data.workflowId as string}`} size="xs">
                            {t('executionDetail.openWorkflow')}
                        </Anchor>
                    ) : undefined}
                />
            </SimpleGrid>

            {execError && (
                <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />}
                       title={t('executionDetail.errorTitle')}>
                    <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>{execError}</Text>
                </Alert>
            )}

            <SectionCard
                icon={IconListDetails}
                accent="violet"
                title={t('executionDetail.steps')}
                description={t('executionDetail.stepsHint')}
                data-testid="execution-steps"
            >
                {steps.length === 0 ? (
                    <EmptyState compact icon={<IconListDetails />} accent="violet"
                                title={t('executionDetail.noStepsTitle')}
                                description={t('executionDetail.noSteps')} />
                ) : (
                    <Stack gap="xs">
                        {steps.map((s) => (
                            <StepBlock key={s.stepId} step={s} locale={locale} />
                        ))}
                    </Stack>
                )}
            </SectionCard>

            {paths.length > 0 && (
                <SectionCard
                    icon={IconSitemap}
                    accent="teal"
                    title={t('executionDetail.dynamicTree')}
                    description={t('executionDetail.dynamicTreeHint')}
                    data-testid="execution-paths"
                >
                    <PathTree nodes={paths} />
                </SectionCard>
            )}

            <SectionCard
                icon={IconBraces}
                accent="slate"
                title={t('executionDetail.rawJson')}
                description={t('executionDetail.rawJsonHint')}
                actions={
                    <Button
                        variant="subtle"
                        size="xs"
                        leftSection={rawOpen ? <IconChevronDown size={14} /> : <IconChevronRight size={14} />}
                        onClick={() => setRawOpen((o) => !o)}
                    >
                        {rawOpen ? t('executionDetail.hideRaw') : t('executionDetail.showRaw')}
                    </Button>
                }
                flush
            >
                <Collapse expanded={rawOpen}>
                    <Box p="lg">
                        <JsonViewer value={data} autoExpandDepth={2} />
                    </Box>
                </Collapse>
            </SectionCard>
        </Stack>
    )
}
