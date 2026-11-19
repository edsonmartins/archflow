import { Badge, Text, Stack, Group, Switch, LoadingOverlay, Alert, Divider } from '@mantine/core'
import { IconAlertCircle, IconSettings, IconCpu, IconStack2, IconToggleRight } from '@tabler/icons-react'
import { Fragment, useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { notifications } from '@mantine/notifications'
import { globalConfigApi, type GlobalFeatureToggles, type LLMModel, type PlanDefaults } from '../../../services/admin-api'
import { PageHeader } from '../../../components/PageHeader'
import { SectionCard } from '../../../components/SectionCard'
import EmptyState from '../../../components/EmptyState'
import { VxTable, colunaStatus, type VxColumn } from '../../../components/VTable'
import { PLAN_COLORS } from './tenantColors'

const STATUS_COLORS: Record<string, string> = { active: 'green', beta: 'orange', deprecated: 'gray' }

const TOGGLE_KEYS: (keyof GlobalFeatureToggles)[] = [
  'allowLocalModels', 'humanInTheLoop', 'brainSentry', 'debugMode', 'linktorNotifications', 'auditLog',
]

/**
 * Configuração global do superadmin. Os modelos e os toggles são formulários (um Switch por linha), então ficam em
 * listas de linhas Mantine dentro de `SectionCard` — o canvas da VTable não comporta um Switch na célula. Os limites
 * por plano são só leitura e vão numa `VxTable`.
 */
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

  const colunasDePlano = useMemo((): VxColumn[] => [
    colunaStatus(
      { field: 'plan', title: t('admin.superadmin.globalConfig.planCols.plan'), width: 'auto', minWidth: 180,
        fieldFormat: (r) => t(`admin.superadmin.tenants.plans.${r.plan}`, { defaultValue: String(r.plan) }) },
      (r) => PLAN_COLORS[r.plan as string] ?? 'gray',
    ),
    { field: 'executionsPerDay', title: t('admin.superadmin.globalConfig.planCols.execPerDay'), width: 180,
      fieldFormat: (r) => Number(r.executionsPerDay).toLocaleString(locale) },
    { field: 'tokensPerMonth', title: t('admin.superadmin.globalConfig.planCols.tokensPerMonth'), width: 180,
      fieldFormat: (r) => Number(r.tokensPerMonth).toLocaleString(locale) },
    { field: 'maxWorkflows', title: t('admin.superadmin.globalConfig.planCols.maxWorkflows'), width: 160 },
    { field: 'maxUsers', title: t('admin.superadmin.globalConfig.planCols.maxUsers'), width: 140 },
  ], [t, locale])

  return (
    <Stack gap="lg" p="md" pos="relative">
      <LoadingOverlay visible={loading} />
      <PageHeader
        icon={IconSettings}
        accent="slate"
        title={t('admin.superadmin.globalConfig.title')}
        subtitle={t('admin.superadmin.globalConfig.subtitle')}
      />

      {error && (
        <Alert color="red" icon={<IconAlertCircle size={16} />}>
          {error}
        </Alert>
      )}

      <SectionCard
        icon={IconCpu}
        accent="violet"
        title={t('admin.superadmin.globalConfig.models')}
        description={t('admin.superadmin.globalConfig.modelsHint')}
      >
        {models.length === 0 ? (
          !loading && (
            <EmptyState compact icon={<IconCpu />} accent="violet"
              title={t('admin.superadmin.globalConfig.modelsEmpty')} />
          )
        ) : (
          <Stack gap={0}>
            {models.map((m, index) => (
              <Fragment key={m.id}>
                {index > 0 && <Divider />}
                <Group justify="space-between" wrap="nowrap" gap="md" py="sm" data-testid={`model-row-${m.id}`}>
                  <Stack gap={2} style={{ minWidth: 0, flex: 1 }}>
                    <Group gap="xs" wrap="nowrap">
                      <Text size="sm" fw={600} truncate>{m.name}</Text>
                      <Badge color={STATUS_COLORS[m.status] ?? 'gray'} size="sm" variant="light">
                        {t(`admin.superadmin.globalConfig.modelStatuses.${m.status}`, { defaultValue: m.status })}
                      </Badge>
                    </Group>
                    <Text size="xs" c="dimmed" truncate>
                      {m.provider} · {t('admin.superadmin.globalConfig.costLine', {
                        input: m.costInputPer1M.toFixed(2), output: m.costOutputPer1M.toFixed(2),
                      })}
                    </Text>
                  </Stack>
                  <Switch
                    aria-label={t('admin.superadmin.globalConfig.enableModel', { name: m.name })}
                    checked={m.status !== 'deprecated'}
                    onChange={(e) => toggleModel(m, e.currentTarget.checked)}
                  />
                </Group>
              </Fragment>
            ))}
          </Stack>
        )}
      </SectionCard>

      <SectionCard
        flush
        icon={IconStack2}
        accent="blue"
        title={t('admin.superadmin.globalConfig.plans')}
        description={t('admin.superadmin.globalConfig.plansHint')}
      >
        <VxTable
          legenda={t('admin.superadmin.globalConfig.plans')}
          records={plans as unknown as Record<string, unknown>[]}
          columns={colunasDePlano}
          loading={loading && plans.length === 0}
          emptyMessage={t('admin.superadmin.globalConfig.plansEmpty')}
          emptyIcon={<IconStack2 />}
          emptyAccent="blue"
          testid="plan-defaults"
          rowTestId={(r) => `plan-row-${r.plan}`}
        />
      </SectionCard>

      <SectionCard
        icon={IconToggleRight}
        accent="teal"
        title={t('admin.superadmin.globalConfig.toggles')}
        description={t('admin.superadmin.globalConfig.togglesHint')}
      >
        {toggles && (
          <Stack gap={0}>
            {TOGGLE_KEYS.map((key, index) => (
              <Fragment key={key}>
                {index > 0 && <Divider />}
                <Switch
                  py="sm"
                  labelPosition="left"
                  styles={{ body: { justifyContent: 'space-between' }, labelWrapper: { flex: 1 } }}
                  label={t(`admin.superadmin.globalConfig.toggleLabels.${key}`)}
                  checked={toggles[key]}
                  onChange={() => updateToggle(key)}
                />
              </Fragment>
            ))}
          </Stack>
        )}
      </SectionCard>
    </Stack>
  )
}
