import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
    Alert, Button, Code, Grid, Group, List, Loader, NumberInput, SimpleGrid, Stack, Text, Textarea,
} from '@mantine/core'
import {
    IconAdjustmentsHorizontal, IconAlertCircle, IconAlertTriangle, IconCircleCheck, IconCoins,
    IconListDetails, IconPlayerPlay, IconRepeat, IconSitemap, IconTarget,
} from '@tabler/icons-react'
import { PageHeader } from '../components/PageHeader'
import { SectionCard } from '../components/SectionCard'
import { StatCard } from '../components/admin/StatCard'
import EmptyState from '../components/EmptyState'
import { VxTable, colunaApagada, colunaStatus, type VxColumn } from '../components/VTable'
import {
    orchestrationApi, type DynamicWorkflowResponse, type SubtaskFailure,
} from '../services/orchestration-api'
import { mensagemDoErro } from '../lib/errorMessage'

/**
 * Os motivos distintos de falha, com quantas subtarefas falharam por cada um, na ordem em que
 * apareceram. Oito subtarefas recusadas pelo mesmo motivo viram uma linha, não oito (RCHFLW-61).
 */
function motivosDeFalha(failures: SubtaskFailure[]): { error: string; count: number }[] {
    const porMotivo = new Map<string, number>()
    for (const f of failures) {
        porMotivo.set(f.error, (porMotivo.get(f.error) ?? 0) + 1)
    }
    return [...porMotivo].map(([error, count]) => ({ error, count }))
}

/**
 * Por que nada foi confirmado. A tela dizia sempre "os verificadores rejeitaram", inclusive quando
 * nenhuma subtarefa tinha chegado à verificação (RCHFLW-61) — a explicação precisa seguir o que houve.
 * O orçamento vem antes de "o planejador não gerou nada": com o planejamento gastando o teto, nada é
 * despachado (subtasks = 0) e a frase seria falsa; com teto 0 o planejador nem roda (RCHFLW-62).
 */
function motivoSemConfirmados(result: DynamicWorkflowResponse): [string, Record<string, unknown>?] {
    if (result.subtasks === 0) {
        if ((result.notDispatched ?? 0) > 0) {
            return ['dynamicWorkflow.noConfirmedBudgetHint', { count: result.notDispatched }]
        }
        if (result.budgetExhausted && result.rounds === 0) {
            return ['dynamicWorkflow.noConfirmedBudgetBeforePlanningHint']
        }
        return ['dynamicWorkflow.noConfirmedNoSubtasksHint']
    }
    if (result.failedCount >= result.subtasks) return ['dynamicWorkflow.noConfirmedAllFailedHint']
    return ['dynamicWorkflow.noConfirmedHint']
}

/**
 * O que acompanha o total de tokens: quantas chamadas não informaram uso (o total vira piso) ou, sem
 * isso, de quanto era o orçamento. Antes a tela não dizia nada sobre gasto (RCHFLW-62).
 */
function dicaDeTokens(
    result: DynamicWorkflowResponse,
    budget: number | undefined,
): [string, Record<string, unknown>?] {
    if (result.usageUnknown > 0) return ['dynamicWorkflow.stats.tokensUnknownHint', { count: result.usageUnknown }]
    if (budget != null) return ['dynamicWorkflow.stats.tokensBudgetHint', { budget }]
    return ['dynamicWorkflow.stats.tokensHint']
}

/**
 * No-code surface for ADR-0002 dynamic workflows: compose a goal + policy in a
 * form and fire decompose → fan-out → verify → loop-until-dry against the
 * /api/orchestration/run endpoint, bounded by an optional token budget.
 */
export default function DynamicWorkflowPage() {
    const { t } = useTranslation()

    const [goal, setGoal] = useState('')
    const [decomposePrompt, setDecomposePrompt] = useState('')
    const [maxSubtasks, setMaxSubtasks] = useState<number | string>(8)
    const [voters, setVoters] = useState<number | string>(1)
    const [maxRounds, setMaxRounds] = useState<number | string>(5)
    const [concurrency, setConcurrency] = useState<number | string>(4)
    const [budgetTokens, setBudgetTokens] = useState<number | string>('')

    const [running, setRunning] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const [result, setResult] = useState<DynamicWorkflowResponse | null>(null)
    // O orçamento da execução mostrada — o campo pode mudar depois, e o aviso fala do que valeu nela.
    const [ranBudget, setRanBudget] = useState<number | undefined>(undefined)

    const num = (v: number | string): number | undefined =>
        v === '' || v === null ? undefined : Number(v)

    const run = async () => {
        setRunning(true)
        setError(null)
        try {
            const budget = num(budgetTokens)
            const res = await orchestrationApi.run({
                goal: goal.trim(),
                decomposePrompt: decomposePrompt.trim() || undefined,
                maxSubtasks: num(maxSubtasks),
                voters: num(voters),
                maxRounds: num(maxRounds),
                concurrency: num(concurrency),
                budgetTokens: budget,
            })
            setRanBudget(budget)
            setResult(res)
        } catch (e) {
            setError(mensagemDoErro(e))
        } finally {
            setRunning(false)
        }
    }

    const traceColumns = useMemo((): VxColumn[] => [
        colunaApagada({ field: 'round', title: t('dynamicWorkflow.traceCols.round'), width: 90,
            fieldFormat: (r) => `r${r.round}` }),
        { field: 'type', title: t('dynamicWorkflow.traceCols.type'), width: 170, style: { fontWeight: 600 } },
        colunaStatus(
            // Uma linha "failed" não tem veredito (nada foi verificado), mas é falha: sai como tal.
            { field: 'confirmed', title: t('dynamicWorkflow.traceCols.verdict'), width: 150,
              fieldFormat: (r) => (r.type === 'failed' ? t('dynamicWorkflow.verdict.failed')
                  : r.confirmed == null ? ''
                  : r.confirmed ? t('dynamicWorkflow.verdict.confirmed') : t('dynamicWorkflow.verdict.rejected')) },
            (r) => (r.confirmed ? 'teal' : 'red'),
        ),
        // Qual agente do catálogo atendeu a subtarefa (RCHFLW-64): sem isto o escopo de sub-agentes
        // não era auditável pela tela. Vazio nas linhas que não são de uma subtarefa.
        { field: 'componentId', title: t('dynamicWorkflow.traceCols.agent'), width: 180,
          fieldFormat: (r) => (r.componentId == null ? '' : String(r.componentId)) },
        { field: 'detail', title: t('dynamicWorkflow.traceCols.detail'), width: 'auto', minWidth: 260 },
    ], [t])

    const trace = result?.trace ?? []
    const failures = result?.failures ?? []

    return (
        <Stack p="md" gap="lg">
            <PageHeader
                icon={IconSitemap}
                accent="violet"
                title={t('dynamicWorkflow.title')}
                subtitle={t('dynamicWorkflow.subtitle')}
            />

            <Grid gap="lg" align="flex-start">
                <Grid.Col span={{ base: 12, lg: 5 }}>
                    <Stack gap="lg">
                        <SectionCard
                            icon={IconTarget}
                            accent="violet"
                            title={t('dynamicWorkflow.goalSection')}
                            description={t('dynamicWorkflow.goalSectionHint')}
                        >
                            <Stack gap="sm">
                                <Textarea
                                    size="sm"
                                    label={t('dynamicWorkflow.goal')}
                                    placeholder={t('dynamicWorkflow.goalPlaceholder')}
                                    required
                                    autosize
                                    minRows={2}
                                    value={goal}
                                    onChange={(e) => setGoal(e.currentTarget.value)}
                                    data-testid="dw-goal"
                                />
                                <Textarea
                                    size="sm"
                                    label={t('dynamicWorkflow.decomposePrompt')}
                                    placeholder={t('dynamicWorkflow.decomposePromptPlaceholder')}
                                    autosize
                                    minRows={1}
                                    value={decomposePrompt}
                                    onChange={(e) => setDecomposePrompt(e.currentTarget.value)}
                                />
                            </Stack>
                        </SectionCard>

                        <SectionCard
                            icon={IconAdjustmentsHorizontal}
                            accent="violet"
                            title={t('dynamicWorkflow.policySection')}
                            description={t('dynamicWorkflow.policySectionHint')}
                        >
                            <Stack gap="md">
                                <SimpleGrid cols={{ base: 1, xs: 3 }} spacing="sm">
                                    <NumberInput size="sm" label={t('dynamicWorkflow.maxSubtasks')} min={1}
                                                 value={maxSubtasks} onChange={setMaxSubtasks} />
                                    <NumberInput size="sm" label={t('dynamicWorkflow.voters')} min={1}
                                                 value={voters} onChange={setVoters} />
                                    <NumberInput size="sm" label={t('dynamicWorkflow.maxRounds')} min={1}
                                                 value={maxRounds} onChange={setMaxRounds} />
                                    <NumberInput size="sm" label={t('dynamicWorkflow.concurrency')} min={1}
                                                 value={concurrency} onChange={setConcurrency} />
                                    <NumberInput
                                        size="sm"
                                        label={t('dynamicWorkflow.budgetTokens')}
                                        min={0}
                                        step={1000}
                                        placeholder="∞"
                                        title={t('dynamicWorkflow.budgetHint')}
                                        value={budgetTokens}
                                        onChange={setBudgetTokens}
                                    />
                                    <Group justify="flex-end" align="flex-end">
                                        <Button
                                            leftSection={<IconPlayerPlay size={16} />}
                                            onClick={run}
                                            loading={running}
                                            disabled={!goal.trim()}
                                            data-testid="dw-run"
                                        >
                                            {t('dynamicWorkflow.run')}
                                        </Button>
                                    </Group>
                                </SimpleGrid>
                            </Stack>
                        </SectionCard>
                    </Stack>
                </Grid.Col>

                <Grid.Col span={{ base: 12, lg: 7 }}>
                    <Stack gap="lg">
                        {error && (
                            <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />}>{error}</Alert>
                        )}

                        {result ? (
                            <Stack gap="lg" data-testid="dw-result">
                                <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="md">
                                    <StatCard
                                        label={t('dynamicWorkflow.stats.rounds')}
                                        value={result.rounds}
                                        icon={IconRepeat}
                                        accent="violet"
                                        hint={t('dynamicWorkflow.stats.roundsHint')}
                                    />
                                    <StatCard
                                        label={t('dynamicWorkflow.stats.confirmed')}
                                        value={result.confirmedCount}
                                        icon={IconCircleCheck}
                                        accent="teal"
                                        hint={t('dynamicWorkflow.stats.confirmedHint')}
                                    />
                                    <StatCard
                                        label={t('dynamicWorkflow.stats.failed')}
                                        value={`${result.failedCount}/${result.subtasks}`}
                                        icon={IconAlertTriangle}
                                        accent={result.failedCount > 0 ? 'rose' : 'slate'}
                                        hint={t('dynamicWorkflow.stats.failedHint')}
                                    />
                                    <StatCard
                                        label={t('dynamicWorkflow.stats.tokens')}
                                        // Com uso não informado o total é um piso: "≥" em vez de um número
                                        // que parece completo (RCHFLW-62).
                                        value={`${result.usageUnknown > 0 ? '≥ ' : ''}${result.tokensSpent}`}
                                        icon={IconCoins}
                                        accent={result.budgetExhausted ? 'rose' : 'violet'}
                                        hint={t(...dicaDeTokens(result, ranBudget))}
                                    />
                                </SimpleGrid>

                                {result.budgetExhausted && (
                                    <Alert
                                        color="orange"
                                        variant="light"
                                        icon={<IconCoins size={16} />}
                                        title={t('dynamicWorkflow.budgetExhaustedTitle')}
                                        data-testid="dw-budget-exhausted"
                                    >
                                        {t('dynamicWorkflow.budgetExhaustedHint', { budget: ranBudget ?? '—' })}
                                    </Alert>
                                )}

                                {failures.length > 0 && (
                                    <Alert
                                        color={result.failedCount >= result.subtasks ? 'red' : 'orange'}
                                        variant="light"
                                        icon={<IconAlertTriangle size={16} />}
                                        title={t('dynamicWorkflow.failuresTitle',
                                            { failed: result.failedCount, total: result.subtasks })}
                                        data-testid="dw-failures"
                                    >
                                        <Text size="sm" mb={4}>{t('dynamicWorkflow.failuresHint')}</Text>
                                        <List size="sm" spacing={2}>
                                            {motivosDeFalha(failures).map(({ error, count }) => (
                                                <List.Item key={error}>
                                                    <Text span size="sm" fw={600}>{count}×</Text> {error}
                                                </List.Item>
                                            ))}
                                        </List>
                                    </Alert>
                                )}

                                <SectionCard
                                    icon={IconCircleCheck}
                                    accent="teal"
                                    title={t('dynamicWorkflow.results')}
                                    description={t('dynamicWorkflow.confirmedCount', { count: result.confirmedCount })}
                                >
                                    {result.confirmed.length === 0 ? (
                                        <EmptyState
                                            compact
                                            accent="teal"
                                            icon={<IconCircleCheck />}
                                            title={t('dynamicWorkflow.noConfirmed')}
                                            description={t(...motivoSemConfirmados(result))}
                                        />
                                    ) : (
                                        <Stack gap="xs">
                                            {result.confirmed.map((item, i) => (
                                                <Code key={i} block>
                                                    {typeof item === 'string' ? item : JSON.stringify(item, null, 2)}
                                                </Code>
                                            ))}
                                        </Stack>
                                    )}
                                </SectionCard>

                                {trace.length > 0 && (
                                    <SectionCard
                                        flush
                                        icon={IconListDetails}
                                        accent="violet"
                                        title={t('dynamicWorkflow.trace')}
                                        description={t('dynamicWorkflow.traceHint', { count: trace.length })}
                                    >
                                        <VxTable
                                            legenda={t('dynamicWorkflow.trace')}
                                            records={trace as unknown as Record<string, unknown>[]}
                                            columns={traceColumns}
                                            maxHeight={420}
                                            testid="dw-trace"
                                        />
                                    </SectionCard>
                                )}
                            </Stack>
                        ) : (
                            !error && (
                                <SectionCard
                                    icon={IconListDetails}
                                    accent="violet"
                                    title={t('dynamicWorkflow.results')}
                                    description={t('dynamicWorkflow.resultsHint')}
                                >
                                    {running ? (
                                        <Stack align="center" gap="sm" py="xl" mih={180} justify="center">
                                            <Loader color="violet" />
                                            <Text size="sm" c="dimmed">{t('dynamicWorkflow.running')}</Text>
                                        </Stack>
                                    ) : (
                                        <EmptyState
                                            compact
                                            accent="violet"
                                            icon={<IconSitemap />}
                                            title={t('dynamicWorkflow.emptyTitle')}
                                            description={t('dynamicWorkflow.empty')}
                                        />
                                    )}
                                </SectionCard>
                            )
                        )}
                    </Stack>
                </Grid.Col>
            </Grid>
        </Stack>
    )
}
