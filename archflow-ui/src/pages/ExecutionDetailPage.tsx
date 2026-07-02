import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
    ActionIcon, Alert, Badge, Button, Card, Code, Collapse, Group, Loader,
    Paper, Stack, Text, Title,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import {
    IconAlertCircle, IconChevronDown, IconChevronRight, IconCheck,
    IconMinus, IconPlayerPlay, IconSitemap, IconX,
} from '@tabler/icons-react'
import { executionApi, workflowApi, ApiError } from '../services/api'
import { tabularNums } from '../components/DataTable'
import { JsonViewer } from '../components/JsonViewer'
import { Meta } from '../components/Meta'
import { formatDuration, formatInstant } from '../lib/format'

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

function pathColor(status: string): string {
    switch (status) {
        case 'COMPLETED': return 'teal'
        case 'FAILED':    return 'red'
        case 'RUNNING':
        case 'STARTED':   return 'blue'
        default:          return 'gray'
    }
}

function PathTree({ nodes, depth = 0 }: { nodes: PathNode[]; depth?: number }) {
    return (
        <Stack gap={4}>
            {nodes.map((n, i) => (
                <div key={`${n.pathId}-${i}`}>
                    <Group gap="xs" wrap="nowrap" style={{ paddingLeft: depth * 16 }}>
                        <Badge size="xs" variant="light" color={pathColor(n.status)}>{n.status}</Badge>
                        <Text size="sm">{n.pathId}</Text>
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

    const statusColor =
        step.status === 'RUNNING' ? 'blue'
        : step.status === 'COMPLETED' ? 'teal'
        : step.status === 'FAILED' ? 'red'
        : 'gray'
    const statusLabel =
        step.status === 'RUNNING' ? t('executionDetail.step.running')
        : step.status === 'COMPLETED' ? t('executionDetail.step.completed')
        : step.status === 'FAILED' ? t('executionDetail.step.failed')
        : step.status === 'SKIPPED' ? t('executionDetail.step.skipped')
        : step.status

    return (
        <Paper withBorder radius="md" p="xs" data-testid={`execution-step-${step.stepId}`}>
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
                        <Text size="xs" c="dimmed" ff="DM Mono, monospace" style={tabularNums}>
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
                    <Text size="xs" c="dimmed" ff="DM Mono, monospace" style={tabularNums}>
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

    if (err) return (
        <Stack gap="md" p="md" data-testid="execution-detail-error">
            <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />}>{err}</Alert>
            <Group>
                <Link to="/executions"><Button variant="default">{t('executionDetail.back')}</Button></Link>
            </Group>
        </Stack>
    )
    if (!data) return (
        <Group p="md"><Loader size="sm" /><Text size="sm" c="dimmed">{t('triggers.loading')}</Text></Group>
    )

    const traceId = data.traceId as string | undefined
    const paths = (data.executionPaths as PathNode[] | undefined) ?? []
    const steps = ((data.steps as StepRecord[] | undefined) ?? [])
        .slice()
        .sort((a, b) => (a.stepIndex ?? 0) - (b.stepIndex ?? 0))
    const execError = data.error as string | null | undefined
    const duration = data.duration as number | null | undefined

    return (
        <Stack gap="md" p="md" data-testid="execution-detail">
            <Group justify="space-between">
                <Group>
                    <Title order={2}>{t('executionDetail.title', { id: id.slice(0, 12) })}</Title>
                    {status && (
                        <Badge color={status === 'COMPLETED' ? 'teal' : status === 'FAILED' ? 'red' : status === 'RUNNING' ? 'blue' : 'yellow'}>
                            {t(`executions.statuses.${status}`, { defaultValue: status })}
                        </Badge>
                    )}
                </Group>
                <Group>
                    <Button
                        variant="light"
                        leftSection={<IconPlayerPlay size={14} />}
                        loading={rerunning}
                        onClick={handleRerun}
                        data-testid="execution-rerun"
                    >
                        {t('executionDetail.rerun')}
                    </Button>
                    {traceId && (
                        <Link to={`/admin/observability/traces/${traceId}`}>
                            <Button variant="light">{t('executionDetail.viewTrace')}</Button>
                        </Link>
                    )}
                    <Link to="/executions"><Button variant="default">{t('executionDetail.back')}</Button></Link>
                </Group>
            </Group>

            <Card withBorder>
                <Group gap="xl" wrap="wrap">
                    <Meta label={t('executionDetail.summary.workflow')} value={String(data.workflowName ?? data.workflowId ?? '—')} />
                    <Meta label={t('executionDetail.summary.started')} value={formatInstant(data.startedAt as string | undefined, locale)} />
                    <Meta label={t('executionDetail.summary.completed')} value={formatInstant(data.completedAt as string | undefined, locale)} />
                    <Meta label={t('executionDetail.summary.duration')} value={typeof duration === 'number' ? formatDuration(duration) : '—'} />
                </Group>
                {execError && (
                    <Alert mt="sm" color="red" variant="light" icon={<IconAlertCircle size={16} />}>
                        {execError}
                    </Alert>
                )}
            </Card>

            <Card withBorder data-testid="execution-steps">
                <Text fw={600} mb="sm">{t('executionDetail.steps')}</Text>
                {steps.length === 0 ? (
                    <Text size="sm" c="dimmed">{t('executionDetail.noSteps')}</Text>
                ) : (
                    <Stack gap="xs">
                        {steps.map((s) => (
                            <StepBlock key={s.stepId} step={s} locale={locale} />
                        ))}
                    </Stack>
                )}
            </Card>

            {paths.length > 0 && (
                <Card withBorder data-testid="execution-paths">
                    <Group gap="xs" mb="sm">
                        <IconSitemap size={18} />
                        <Text fw={600}>{t('executionDetail.dynamicTree')}</Text>
                    </Group>
                    <PathTree nodes={paths} />
                </Card>
            )}

            <div>
                <Button
                    variant="subtle"
                    size="xs"
                    leftSection={rawOpen ? <IconChevronDown size={14} /> : <IconChevronRight size={14} />}
                    onClick={() => setRawOpen((o) => !o)}
                >
                    {rawOpen ? t('executionDetail.hideRaw') : t('executionDetail.showRaw')}
                </Button>
                <Collapse expanded={rawOpen}>
                    <Card withBorder mt="xs">
                        <JsonViewer value={data} autoExpandDepth={2} />
                    </Card>
                </Collapse>
            </div>
        </Stack>
    )
}
