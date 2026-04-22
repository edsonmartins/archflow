import { Title, Table, Badge, Text, Paper, Stack, Switch, Group, Divider, LoadingOverlay, Alert } from '@mantine/core'
import { IconAlertCircle } from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { notifications } from '@mantine/notifications'
import { globalConfigApi, type GlobalFeatureToggles, type LLMModel, type PlanDefaults } from '../../../services/admin-api'

const STATUS_COLORS: Record<string, string> = { active: 'green', beta: 'orange', deprecated: 'gray' }

export default function GlobalConfig() {
  const [models, setModels] = useState<LLMModel[]>([])
  const [plans, setPlans] = useState<PlanDefaults[]>([])
  const [toggles, setToggles] = useState<GlobalFeatureToggles | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)

    Promise.all([
      globalConfigApi.getModels(),
      globalConfigApi.getPlanDefaults(),
      globalConfigApi.getToggles(),
    ])
      .then(([modelsDto, plansDto, togglesDto]) => {
        if (cancelled) return
        setModels(modelsDto)
        setPlans(plansDto)
        setToggles(togglesDto)
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : 'Failed to load configuration')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [])

  const updateToggle = async (key: keyof GlobalFeatureToggles) => {
    if (!toggles) return
    const next = { ...toggles, [key]: !toggles[key] }
    setToggles(next)
    try {
      await globalConfigApi.updateToggles(next)
    } catch (e) {
      setToggles(toggles)
      setError(e instanceof Error ? e.message : 'Failed to update toggles')
    }
  }

  const toggleModel = async (model: LLMModel, active: boolean) => {
    const previous = models
    setModels(models.map((entry) => entry.id === model.id ? { ...entry, status: active ? 'active' : 'deprecated' } : entry))
    try {
      await globalConfigApi.toggleModel(model.id, active)
      notifications.show({ title: 'Model updated', message: model.name, color: 'teal' })
    } catch (e) {
      setModels(previous)
      setError(e instanceof Error ? e.message : 'Failed to update model')
    }
  }

  return (
    <Stack gap="md" pos="relative">
      <LoadingOverlay visible={loading} />
      <Title order={3}>Global Configuration</Title>

      {error && (
        <Alert color="red" icon={<IconAlertCircle size={16} />}>
          {error}
        </Alert>
      )}

      <Paper withBorder p="lg" radius="lg">
        <Text fw={600} size="sm" mb="md">LLM Models</Text>
        <Table striped highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Model</Table.Th>
              <Table.Th>Provider</Table.Th>
              <Table.Th>Status</Table.Th>
              <Table.Th>Cost/1M input</Table.Th>
              <Table.Th>Cost/1M output</Table.Th>
              <Table.Th>Enabled</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {models.map(m => (
              <Table.Tr key={m.id}>
                <Table.Td><Text size="sm" fw={500} ff="monospace">{m.name}</Text></Table.Td>
                <Table.Td><Text size="sm">{m.provider}</Text></Table.Td>
                <Table.Td><Badge color={STATUS_COLORS[m.status] ?? 'gray'} size="sm">{m.status}</Badge></Table.Td>
                <Table.Td><Text size="sm">${m.costInputPer1M.toFixed(2)}</Text></Table.Td>
                <Table.Td><Text size="sm">${m.costOutputPer1M.toFixed(2)}</Text></Table.Td>
                <Table.Td>
                  <Switch
                    aria-label={`Enable ${m.name}`}
                    checked={m.status !== 'deprecated'}
                    onChange={(e) => toggleModel(m, e.currentTarget.checked)}
                  />
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Paper>

      <Paper withBorder p="lg" radius="lg">
        <Text fw={600} size="sm" mb="md">Plan Defaults</Text>
        <Table striped highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Plan</Table.Th>
              <Table.Th>Executions/day</Table.Th>
              <Table.Th>Tokens/month</Table.Th>
              <Table.Th>Max workflows</Table.Th>
              <Table.Th>Max users</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {plans.map(plan => (
              <Table.Tr key={plan.plan}>
                <Table.Td><Badge variant="light">{plan.plan}</Badge></Table.Td>
                <Table.Td>{plan.executionsPerDay}</Table.Td>
                <Table.Td>{plan.tokensPerMonth.toLocaleString()}</Table.Td>
                <Table.Td>{plan.maxWorkflows}</Table.Td>
                <Table.Td>{plan.maxUsers}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Paper>

      <Paper withBorder p="lg" radius="lg">
        <Text fw={600} size="sm" mb="md">Feature Toggles</Text>
        {toggles && (
          <Stack gap="sm">
            <Switch label="Allow local models (Ollama / RTX)" checked={toggles.allowLocalModels} onChange={() => updateToggle('allowLocalModels')} />
            <Switch label="Human-in-the-loop" checked={toggles.humanInTheLoop} onChange={() => updateToggle('humanInTheLoop')} />
            <Switch label="Brain Sentry (long-term memory via FalkorDB)" checked={toggles.brainSentry} onChange={() => updateToggle('brainSentry')} />
            <Switch label="Debug mode — full trace per node" checked={toggles.debugMode} onChange={() => updateToggle('debugMode')} />
            <Switch label="Linktor notifications (WhatsApp)" checked={toggles.linktorNotifications} onChange={() => updateToggle('linktorNotifications')} />
            <Switch label="Audit log for user actions" checked={toggles.auditLog} onChange={() => updateToggle('auditLog')} />
          </Stack>
        )}
      </Paper>
    </Stack>
  )
}
