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
    IconCircleCheck, IconCircleDashed, IconClock, IconGavel, IconHourglass, IconListDetails, IconLoader2,
    IconMinus, IconPlayerPause, IconPlayerPlay, IconPlayerStop, IconSitemap, IconTimeline, IconTopologyRing,
    IconX,
} from '@tabler/icons-react'
import { executionApi, workflowApi, ApiError } from '../services/api'
import { JsonViewer } from '../components/JsonViewer'
import { RunInputModal } from '../components/RunInputModal'
import { toExecuteBody } from '../lib/runInput'
import { Meta } from '../components/Meta'
import { PageHeader, type Accent } from '../components/PageHeader'
import { SectionCard } from '../components/SectionCard'
import { StatCard } from '../components/admin/StatCard'
import EmptyState from '../components/EmptyState'
import { formatDuration, formatInstant } from '../lib/format'
import { EXECUTION_STATUS_COLOR, executionPollIntervalMs, isTerminalExecutionStatus } from '../lib/executionStatus'
import { buildStepRows, parseStepOutput, type StepRecord, type StepRow } from '../lib/stepOutput'

/** A materialized ExecutionPath node (design-0005 step 4). */
interface PathNode {
    pathId: string
    status: string
    parallelBranches?: PathNode[] | null
}

const tabularNums = { fontVariantNumeric: 'tabular-nums' } as const

const STATUS_ACCENT: Record<string, Accent> = {
    RUNNING: 'blue', COMPLETED: 'teal', FAILED: 'rose', PAUSED: 'amber', CANCELLED: 'slate',
    STOPPED: 'amber',
}

const STATUS_ICON: Record<string, typeof IconCircleCheck> = {
    RUNNING: IconLoader2, COMPLETED: IconCircleCheck, FAILED: IconAlertTriangle,
    PAUSED: IconPlayerPause, CANCELLED: IconPlayerStop, STOPPED: IconPlayerStop,
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
 * Cor do status de um PASSO. Difere da do run em dois pontos: o portão de aprovação à espera (PAUSED)
 * é violeta — "aguardando aprovação", o mesmo do canvas, não a pausa amarela do run — e o portão
 * recusado (REJECTED) é vermelho.
 */
const STEP_STATUS_COLOR: Record<string, string> = {
    ...EXECUTION_STATUS_COLOR,
    PAUSED: 'violet',
    REJECTED: 'red',
}

/** Uma decisão do histórico durável do motor (`__archflow.approvalHistory`). */
interface ApprovalDecision {
    stepId?: string
    requestId?: string
    decision?: string
    decidedBy?: string
    comment?: string
    decidedAt?: string
}

/** Quem o motor grava quando o prazo vence (ApprovalQueueService.TIMEOUT_DECIDER). */
const TIMEOUT_DECIDER = 'system:timeout'

/**
 * Quem decidiu cada portão desta execução. Vem do estado durável que o motor gravou sob o lock da
 * decisão (`approvals` no GET da execução) — sem isto nenhuma tela respondia "quem autorizou isto"
 * (RCHFLW-38).
 */
function ApprovalDecisions({ decisions, labels, locale }: {
    decisions: ApprovalDecision[]
    labels: Map<string, string>
    locale: string
}) {
    const { t } = useTranslation()
    return (
        <Stack gap="xs">
            {decisions.map((d, i) => {
                const timedOut = d.decidedBy === TIMEOUT_DECIDER
                const approved = d.decision === 'APPROVED'
                const label = timedOut ? t('executionDetail.approvals.timeout')
                    : approved ? t('executionDetail.approvals.approved')
                    : t('executionDetail.approvals.rejected')
                const stepName = d.stepId ? (labels.get(d.stepId) ?? d.stepId) : '—'
                return (
                    <Paper key={`${d.requestId ?? i}`} withBorder radius="md" px="sm" py="xs"
                           data-testid={`execution-approval-${d.requestId ?? i}`}
                           style={{ boxShadow: 'none',
                               borderLeft: `3px solid var(--mantine-color-${approved ? 'teal' : 'red'}-6)` }}>
                        <Group gap="xl" wrap="wrap" align="flex-start">
                            <Meta label={t('executionDetail.approvals.step')} value={stepName} />
                            <Stack gap={2}>
                                <Text size="xs" c="dimmed" tt="uppercase" style={{ letterSpacing: 0.5 }}>
                                    {t('executionDetail.approvals.decidedBy')}
                                </Text>
                                <Group gap={6} wrap="nowrap">
                                    <Badge size="xs" variant="light" color={approved ? 'teal' : 'red'}>{label}</Badge>
                                    {!timedOut && (
                                        <Text size="sm" fw={500} data-testid="execution-approval-decided-by">
                                            {d.decidedBy || '—'}
                                        </Text>
                                    )}
                                </Group>
                            </Stack>
                            <Meta label={t('executionDetail.approvals.decidedAt')} value={formatInstant(d.decidedAt, locale)} />
                            {d.comment && (
                                <Meta label={t('executionDetail.approvals.comment')} value={d.comment} />
                            )}
                        </Group>
                    </Paper>
                )
            })}
        </Stack>
    )
}

/** Saída gravada do passo: árvore JSON recolhível quando é JSON, texto cru quando não. */
function StepOutput({ raw }: { raw: string }) {
    const { t } = useTranslation()
    const value = parseStepOutput(raw)
    return (
        <Stack gap={2} data-testid="execution-step-output">
            <Text size="xs" c="dimmed" tt="uppercase" style={{ letterSpacing: 0.5 }}>
                {t('executionDetail.step.outputLabel')}
            </Text>
            {value !== null && typeof value === 'object'
                ? <JsonViewer value={value} autoExpandDepth={1} />
                : <Code block style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{String(raw)}</Code>}
        </Stack>
    )
}

/**
 * Collapsible per-step block in the ToolCallBlock visual language:
 * header with name + status + duration, expanding into timestamps,
 * output and error detail. Rows for workflow steps that never ran
 * ("Não executado") render grayed out and do not expand.
 */
function StepBlock({ step, locale }: { step: StepRow; locale: string }) {
    const { t } = useTranslation()
    const [open, setOpen] = useState(step.status === 'FAILED')
    const notExecuted = step.notExecuted === true

    const statusColor = notExecuted ? 'gray' : (STEP_STATUS_COLOR[step.status] ?? 'gray')
    const statusLabel =
        notExecuted ? t('executionDetail.step.notExecuted')
        : step.status === 'RUNNING' ? t('executionDetail.step.running')
        : step.status === 'COMPLETED' ? t('executionDetail.step.completed')
        : step.status === 'FAILED' ? t('executionDetail.step.failed')
        : step.status === 'SKIPPED' ? t('executionDetail.step.skipped')
        : step.status === 'PAUSED' ? t('executionDetail.step.awaiting')
        : step.status === 'REJECTED' ? t('executionDetail.step.rejected')
        : step.status

    return (
        <Paper withBorder radius="md" px="sm" py="xs" data-testid={`execution-step-${step.stepId}`}
               data-not-executed={notExecuted ? 'true' : undefined}
               style={{ borderLeft: `3px solid var(--mantine-color-${statusColor}-${notExecuted ? 3 : 6})`,
                   boxShadow: 'none', opacity: notExecuted ? 0.6 : undefined }}>
            <Group
                justify="space-between"
                wrap="nowrap"
                gap="xs"
                style={{ cursor: notExecuted ? 'default' : 'pointer' }}
                onClick={notExecuted ? undefined : () => setOpen((o) => !o)}
            >
                <Group gap={6} wrap="nowrap" style={{ minWidth: 0, flex: 1 }}>
                    {!notExecuted && (
                        <ActionIcon size="xs" variant="subtle" aria-label={t('executionDetail.step.toggle')}>
                            {open ? <IconChevronDown size={14} /> : <IconChevronRight size={14} />}
                        </ActionIcon>
                    )}
                    {typeof step.stepIndex === 'number' && (
                        <Text size="xs" c="dimmed" ff="monospace" style={tabularNums}>
                            {step.stepIndex + 1}{typeof step.stepCount === 'number' ? `/${step.stepCount}` : ''}
                        </Text>
                    )}
                    <Text size="sm" fw={500} truncate c={notExecuted ? 'dimmed' : undefined}
                          title={step.stepId} data-testid="execution-step-name">
                        {step.label ? `${step.label} (${step.stepId})` : step.stepId}
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
                            {step.status === 'PAUSED' && <IconHourglass size={10} />}
                            {step.status === 'REJECTED' && <IconX size={10} />}
                            {statusLabel}
                        </Group>
                    </Badge>
                </Group>

                {/* Portão à espera não terminou: sem duração, mesmo que algum registro antigo a tenha. */}
                {typeof step.durationMs === 'number' && step.status !== 'PAUSED' && (
                    <Text size="xs" c="dimmed" ff="monospace" style={tabularNums}>
                        {formatDuration(step.durationMs)}
                    </Text>
                )}
            </Group>

            <Collapse expanded={open && !notExecuted}>
                <Stack gap={6} mt="xs" pl={28}>
                    <Group gap="xl">
                        <Meta label={t('executionDetail.step.startedAt')} value={formatInstant(step.startedAt, locale)} />
                        <Meta label={t('executionDetail.step.finishedAt')} value={formatInstant(step.finishedAt, locale)} />
                        {step.decidedBy && (
                            <Meta label={t('executionDetail.step.decidedBy')} value={step.decidedBy} />
                        )}
                    </Group>
                    {step.output != null && step.output !== '' && <StepOutput raw={step.output} />}
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

    // Recarrega enquanto roda — e enquanto está pausada: a fila de Aprovações decide em outra tela e
    // a execução retoma e termina sem esta página fazer nada (antes, só um F5 mostrava o desfecho).
    const status = String(data?.status ?? '')
    useEffect(() => {
        const interval = executionPollIntervalMs(status)
        if (interval == null) return
        const timer = setInterval(load, interval)
        return () => clearInterval(timer)
    }, [status, load])

    // Executar novamente pede a entrada antes (RunInputModal): sem ela o fluxo rodava sem input.
    const [rerunInputOpen, setRerunInputOpen] = useState(false)
    const rerunWorkflowId = data?.workflowId as string | undefined

    // O documento do fluxo dá o nome de cada nó (o registro só tem o id "node-…") e a lista dos passos
    // que não rodaram. É enfeite: se não carregar (fluxo apagado, sem permissão), a página segue com ids.
    const [workflowSteps, setWorkflowSteps] = useState<unknown[] | null>(null)
    useEffect(() => {
        if (!rerunWorkflowId) return
        let cancelled = false
        void (async () => {
            try {
                const doc = await workflowApi.get(rerunWorkflowId)
                if (!cancelled) setWorkflowSteps(Array.isArray(doc?.steps) ? doc.steps : null)
            } catch {
                if (!cancelled) setWorkflowSteps(null)
            }
        })()
        return () => { cancelled = true }
    }, [rerunWorkflowId])

    const handleRerun = async (inputText: string) => {
        const workflowId = rerunWorkflowId
        setRerunInputOpen(false)
        if (!workflowId) return
        setRerunning(true)
        try {
            const res = await workflowApi.execute(workflowId, toExecuteBody(inputText))
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
                        onClick={() => setRerunInputOpen(true)}
                        disabled={!data.workflowId}
                        data-testid="execution-rerun"
                    >
                        {t('executionDetail.rerun')}
                    </Button>
                    {rerunWorkflowId && (
                        <RunInputModal
                            workflowId={rerunWorkflowId}
                            opened={rerunInputOpen}
                            onClose={() => setRerunInputOpen(false)}
                            onConfirm={(text) => { void handleRerun(text) }}
                            runs="production"
                        />
                    )}
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
    const steps = (data.steps as StepRecord[] | undefined) ?? []
    // Enquanto não acabou, "sem registro" quer dizer "ainda não chegou lá" — inclusive numa pausa para
    // aprovação humana (PAUSED/AWAITING_APPROVAL), em que os passos depois do portão ainda vão rodar.
    // Por isso a lista é de status terminais, não "tudo menos RUNNING".
    const stepRows = buildStepRows(steps, workflowSteps, isTerminalExecutionStatus(status))
    const execError = data.error as string | null | undefined
    const duration = data.duration as number | null | undefined
    const statusColor = EXECUTION_STATUS_COLOR[status] ?? 'gray'
    const statusAccent: Accent = STATUS_ACCENT[status] ?? 'slate'
    const StatusIcon = STATUS_ICON[status] ?? IconCircleDashed
    const failedSteps = steps.filter((s) => s.status === 'FAILED').length
    const workflowName = String(data.workflowName ?? data.workflowId ?? '—')
    const decisions = Array.isArray(data.approvals) ? (data.approvals as ApprovalDecision[]) : []
    const pendingApproval = data.pendingApproval as { requestId?: string; stepId?: string } | undefined
    // O que rodou, como o backend gravou na criação (RCHFLW-89). Um registro anterior a essa gravação não
    // diz nada, e a tela também não: deduzir pela versão em produção de hoje poderia nomear a errada.
    const ranVersion = data.workflowDraft === true
        ? t('executionDetail.version.draft')
        : data.workflowDraft === false && typeof data.workflowVersionNumber === 'number'
            ? t('executionDetail.version.published', { number: data.workflowVersionNumber })
            : null
    const stepLabels = new Map(stepRows.filter((r) => r.label).map((r) => [r.stepId, r.label as string]))

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
                    hint={status === 'RUNNING' ? t('executionDetail.hints.live')
                        : status === 'PAUSED' ? t('executionDetail.hints.paused')
                        : t('executionDetail.hints.finished', {
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
                    hint={ranVersion || data.workflowId ? (
                        <>
                            {ranVersion && <span data-testid="execution-version">{ranVersion}</span>}
                            {ranVersion && data.workflowId ? ' · ' : null}
                            {data.workflowId ? (
                                <Anchor component={Link} to={`/editor/${data.workflowId as string}`} size="xs">
                                    {t('executionDetail.openWorkflow')}
                                </Anchor>
                            ) : null}
                        </>
                    ) : undefined}
                />
            </SimpleGrid>

            {/* STOPPED é a recusa no portão de aprovação (por alguém ou pelo prazo): o texto diz quem
                recusou e por quê, e não é uma falha — não pode vir sob "A execução falhou". */}
            {execError && (
                <Alert color={status === 'STOPPED' ? 'orange' : 'red'} variant="light"
                       icon={<IconAlertCircle size={16} />} data-testid="execution-error"
                       title={t(status === 'STOPPED' ? 'executionDetail.stoppedTitle' : 'executionDetail.errorTitle')}>
                    <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>{execError}</Text>
                </Alert>
            )}

            {/* O pedido ainda aberto: leva direto à fila, onde a decisão é tomada. */}
            {pendingApproval?.requestId && (
                <Alert color="violet" variant="light" icon={<IconHourglass size={16} />}
                       title={t('executionDetail.pendingApproval.title')} data-testid="execution-pending-approval">
                    <Group justify="space-between" gap="sm" wrap="wrap">
                        <Text size="sm">
                            {t('executionDetail.pendingApproval.text', {
                                step: pendingApproval.stepId
                                    ? (stepLabels.get(pendingApproval.stepId) ?? pendingApproval.stepId) : '—' })}
                        </Text>
                        <Button component={Link} to={`/approvals/${pendingApproval.requestId}?flowId=${encodeURIComponent(id)}`} size="xs"
                                variant="light" color="violet" leftSection={<IconGavel size={14} />}>
                            {t('executionDetail.pendingApproval.open')}
                        </Button>
                    </Group>
                </Alert>
            )}

            {decisions.length > 0 && (
                <SectionCard
                    icon={IconGavel}
                    accent="amber"
                    title={t('executionDetail.approvals.title')}
                    description={t('executionDetail.approvals.hint')}
                    data-testid="execution-approvals"
                >
                    <ApprovalDecisions decisions={decisions} labels={stepLabels} locale={locale} />
                </SectionCard>
            )}

            <SectionCard
                icon={IconListDetails}
                accent="violet"
                title={t('executionDetail.steps')}
                description={t('executionDetail.stepsHint')}
                data-testid="execution-steps"
            >
                {stepRows.length === 0 ? (
                    <EmptyState compact icon={<IconListDetails />} accent="violet"
                                title={t('executionDetail.noStepsTitle')}
                                description={t('executionDetail.noSteps')} />
                ) : (
                    <Stack gap="xs">
                        {stepRows.map((s) => (
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
