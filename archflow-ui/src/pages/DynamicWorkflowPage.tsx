import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
    Alert, Button, Code, Grid, Group, Loader, NumberInput, SimpleGrid, Stack, Text, Textarea,
} from '@mantine/core'
import {
    IconAdjustmentsHorizontal, IconAlertCircle, IconCircleCheck, IconListDetails, IconPlayerPlay,
    IconRepeat, IconSitemap, IconTarget,
} from '@tabler/icons-react'
import { PageHeader } from '../components/PageHeader'
import { SectionCard } from '../components/SectionCard'
import { StatCard } from '../components/admin/StatCard'
import EmptyState from '../components/EmptyState'
import { VxTable, colunaApagada, colunaStatus, type VxColumn } from '../components/VTable'
import { orchestrationApi, type DynamicWorkflowResponse } from '../services/orchestration-api'
import { ApiError } from '../services/api'

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

    const num = (v: number | string): number | undefined =>
        v === '' || v === null ? undefined : Number(v)

    const run = async () => {
        setRunning(true)
        setError(null)
        try {
            const res = await orchestrationApi.run({
                goal: goal.trim(),
                decomposePrompt: decomposePrompt.trim() || undefined,
                maxSubtasks: num(maxSubtasks),
                voters: num(voters),
                maxRounds: num(maxRounds),
                concurrency: num(concurrency),
                budgetTokens: num(budgetTokens),
            })
            setResult(res)
        } catch (e) {
            setError(e instanceof ApiError ? e.message : String(e))
        } finally {
            setRunning(false)
        }
    }

    const traceColumns = useMemo((): VxColumn[] => [
        colunaApagada({ field: 'round', title: t('dynamicWorkflow.traceCols.round'), width: 90,
            fieldFormat: (r) => `r${r.round}` }),
        { field: 'type', title: t('dynamicWorkflow.traceCols.type'), width: 170, style: { fontWeight: 600 } },
        colunaStatus(
            { field: 'confirmed', title: t('dynamicWorkflow.traceCols.verdict'), width: 150,
              fieldFormat: (r) => (r.confirmed == null ? ''
                  : r.confirmed ? t('dynamicWorkflow.verdict.confirmed') : t('dynamicWorkflow.verdict.rejected')) },
            (r) => (r.confirmed ? 'teal' : 'red'),
        ),
        { field: 'detail', title: t('dynamicWorkflow.traceCols.detail'), width: 'auto', minWidth: 260 },
    ], [t])

    const trace = result?.trace ?? []

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
                                <SimpleGrid cols={{ base: 1, xs: 2 }} spacing="md">
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
                                </SimpleGrid>

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
                                            description={t('dynamicWorkflow.noConfirmedHint')}
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
