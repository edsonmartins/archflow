import { Title, Table, Badge, Text, Paper, Stack, Switch, Group, Divider, LoadingOverlay, Alert } from '@mantine/core'
import { IconAlertCircle } from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { notifications } from '@mantine/notifications'
import { globalConfigApi, type GlobalFeatureToggles, type LLMModel, type PlanDefaults } from '../../../services/admin-api'

const STATUS_COLORS: Record<string, string> = { active: 'green', beta: 'orange', deprecated: 'gray' }

export default function GlobalConfig() {
  const { t, i18n } = useTranslation()
  const locale = i18n.resolvedLanguage ?? i18n.language
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
        if (!cancelled) setError(e instanceof Error ? e.message : t('admin.superadmin.globalConfig.loadFailed'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const updateToggle = async (key: keyof GlobalFeatureToggles) => {
    if (!toggles) return
    const next = { ...toggles, [key]: !toggles[key] }
    setToggles(next)
    try {
      await globalConfigApi.updateToggles(next)
    } catch (e) {
      setToggles(toggles)
      setError(e instanceof Error ? e.message : t('admin.superadmin.globalConfig.updateTogglesFailed'))
    }
  }

  const toggleModel = async (model: LLMModel, active: boolean) => {
    const previous = models
    setModels(models.map((entry) => entry.id === model.id ? { ...entry, status: active ? 'active' : 'deprecated' } : entry))
    try {
      await globalConfigApi.toggleModel(model.id, active)
      notifications.show({ title: t('admin.superadmin.globalConfig.modelUpdated'), message: model.name, color: 'teal' })
    } catch (e) {
      setModels(previous)
      setError(e instanceof Error ? e.message : t('admin.superadmin.globalConfig.updateModelFailed'))
    }
  }

  return (
    <Stack gap="md" pos="relative">
      <LoadingOverlay visible={loading} />
      <Title order={3}>{t('admin.superadmin.globalConfig.title')}</Title>

      {error && (
        <Alert color="red" icon={<IconAlertCircle size={16} />}>
          {error}
        </Alert>
      )}

      <Paper withBorder p="lg" radius="lg">
        <Text fw={600} size="sm" mb="md">{t('admin.superadmin.globalConfig.models')}</Text>
        <Table striped highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('admin.superadmin.globalConfig.modelCols.model')}</Table.Th>
              <Table.Th>{t('admin.superadmin.globalConfig.modelCols.provider')}</Table.Th>
              <Table.Th>{t('admin.superadmin.globalConfig.modelCols.status')}</Table.Th>
              <Table.Th>{t('admin.superadmin.globalConfig.modelCols.inputCost')}</Table.Th>
              <Table.Th>{t('admin.superadmin.globalConfig.modelCols.outputCost')}</Table.Th>
              <Table.Th>{t('admin.superadmin.globalConfig.modelCols.enabled')}</Table.Th>
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
        <Text fw={600} size="sm" mb="md">{t('admin.superadmin.globalConfig.plans')}</Text>
        <Table striped highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('admin.superadmin.globalConfig.planCols.plan')}</Table.Th>
              <Table.Th>{t('admin.superadmin.globalConfig.planCols.execPerDay')}</Table.Th>
              <Table.Th>{t('admin.superadmin.globalConfig.planCols.tokensPerMonth')}</Table.Th>
              <Table.Th>{t('admin.superadmin.globalConfig.planCols.maxWorkflows')}</Table.Th>
              <Table.Th>{t('admin.superadmin.globalConfig.planCols.maxUsers')}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {plans.map(plan => (
              <Table.Tr key={plan.plan}>
                <Table.Td><Badge variant="light">{plan.plan}</Badge></Table.Td>
                <Table.Td>{plan.executionsPerDay}</Table.Td>
                <Table.Td>{plan.tokensPerMonth.toLocaleString(locale)}</Table.Td>
                <Table.Td>{plan.maxWorkflows}</Table.Td>
                <Table.Td>{plan.maxUsers}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Paper>

      <Paper withBorder p="lg" radius="lg">
        <Text fw={600} size="sm" mb="md">{t('admin.superadmin.globalConfig.toggles')}</Text>
        {toggles && (
          <Stack gap="sm">
            <Switch label={t('admin.superadmin.globalConfig.toggleLabels.allowLocalModels')}     checked={toggles.allowLocalModels}     onChange={() => updateToggle('allowLocalModels')} />
            <Switch label={t('admin.superadmin.globalConfig.toggleLabels.humanInTheLoop')}       checked={toggles.humanInTheLoop}       onChange={() => updateToggle('humanInTheLoop')} />
            <Switch label={t('admin.superadmin.globalConfig.toggleLabels.brainSentry')}          checked={toggles.brainSentry}          onChange={() => updateToggle('brainSentry')} />
            <Switch label={t('admin.superadmin.globalConfig.toggleLabels.debugMode')}            checked={toggles.debugMode}            onChange={() => updateToggle('debugMode')} />
            <Switch label={t('admin.superadmin.globalConfig.toggleLabels.linktorNotifications')} checked={toggles.linktorNotifications} onChange={() => updateToggle('linktorNotifications')} />
            <Switch label={t('admin.superadmin.globalConfig.toggleLabels.auditLog')}             checked={toggles.auditLog}             onChange={() => updateToggle('auditLog')} />
          </Stack>
        )}
      </Paper>
    </Stack>
  )
}
