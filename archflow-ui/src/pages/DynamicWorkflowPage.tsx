import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
    Alert, Badge, Button, Code, Group, NumberInput, Paper, Stack, Text, Textarea,
} from '@mantine/core'
import { IconAlertCircle, IconPlayerPlay, IconSitemap } from '@tabler/icons-react'
import { PageHeader } from '../components/PageHeader'
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

    return (
        <Stack p="md" gap="md" maw={860}>
            <PageHeader title={t('dynamicWorkflow.title')} subtitle={t('dynamicWorkflow.subtitle')} />

            <Paper withBorder radius="lg" p="lg">
                <Stack gap="sm">
                    <Textarea
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
                        label={t('dynamicWorkflow.decomposePrompt')}
                        placeholder={t('dynamicWorkflow.decomposePromptPlaceholder')}
                        autosize
                        minRows={1}
                        value={decomposePrompt}
                        onChange={(e) => setDecomposePrompt(e.currentTarget.value)}
                    />
                    <Group gap="sm" grow wrap="wrap">
                        <NumberInput label={t('dynamicWorkflow.maxSubtasks')} min={1} value={maxSubtasks} onChange={setMaxSubtasks} />
                        <NumberInput label={t('dynamicWorkflow.voters')} min={1} value={voters} onChange={setVoters} />
                        <NumberInput label={t('dynamicWorkflow.maxRounds')} min={1} value={maxRounds} onChange={setMaxRounds} />
                        <NumberInput label={t('dynamicWorkflow.concurrency')} min={1} value={concurrency} onChange={setConcurrency} />
                        <NumberInput
                            label={t('dynamicWorkflow.budgetTokens')}
                            min={0}
                            step={1000}
                            placeholder="∞"
                            value={budgetTokens}
                            onChange={setBudgetTokens}
                        />
                    </Group>
                    <Group justify="flex-end">
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
                </Stack>
            </Paper>

            {error && (
                <Alert color="red" variant="light" icon={<IconAlertCircle size={16} />}>{error}</Alert>
            )}

            {result ? (
                <Paper withBorder radius="lg" p="lg" data-testid="dw-result">
                    <Group gap="sm" mb="sm">
                        <Badge variant="light">{t('dynamicWorkflow.rounds', { count: result.rounds })}</Badge>
                        <Badge variant="light" color="teal">
                            {t('dynamicWorkflow.confirmedCount', { count: result.confirmedCount })}
                        </Badge>
                    </Group>
                    <Text fw={600} size="sm" mb="xs">{t('dynamicWorkflow.results')}</Text>
                    <Stack gap="xs">
                        {result.confirmed.map((item, i) => (
                            <Code key={i} block>
                                {typeof item === 'string' ? item : JSON.stringify(item, null, 2)}
                            </Code>
                        ))}
                    </Stack>

                    {result.trace && result.trace.length > 0 && (
                        <>
                            <Text fw={600} size="sm" mt="md" mb="xs">{t('dynamicWorkflow.trace')}</Text>
                            <Stack gap={4}>
                                {result.trace.map((e, i) => (
                                    <Group key={i} gap="xs" wrap="nowrap">
                                        <Badge size="xs" variant="light" color="gray" w={70} style={{ flexShrink: 0 }}>
                                            r{e.round} · {e.type}
                                        </Badge>
                                        {e.confirmed != null && (
                                            <Badge size="xs" variant="light" color={e.confirmed ? 'teal' : 'red'}>
                                                {e.confirmed ? '✓' : '✗'}
                                            </Badge>
                                        )}
                                        <Text size="xs" c="dimmed" lineClamp={1}>{e.detail}</Text>
                                    </Group>
                                ))}
                            </Stack>
                        </>
                    )}
                </Paper>
            ) : (
                !error && (
                    <Stack align="center" gap="xs" py="xl" c="dimmed">
                        <IconSitemap size={36} opacity={0.4} />
                        <Text size="sm">{t('dynamicWorkflow.empty')}</Text>
                    </Stack>
                )
            )}
        </Stack>
    )
}
