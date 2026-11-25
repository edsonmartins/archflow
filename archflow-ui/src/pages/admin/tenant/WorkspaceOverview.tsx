import { Stack, SimpleGrid, LoadingOverlay, Alert, Badge, Text } from '@mantine/core'
import {
  IconAlertCircle, IconLayoutGrid, IconTopologyRing, IconPlayerPlay, IconUsers, IconKey, IconGauge,
  IconCoins, IconCurrencyReal,
} from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { workspaceApi, type WorkspaceSummary } from '../../../services/admin-api'
import { UsageBar } from '../../../components/admin/UsageBar'
import { PageHeader } from '../../../components/PageHeader'
import { SectionCard } from '../../../components/SectionCard'
import { StatCard } from '../../../components/admin/StatCard'
import EmptyState from '../../../components/EmptyState'
import { compactTokens, formatReais } from '../../../lib/usageFormat'

/**
 * Visão geral do workspace (RCHFLW-75): tudo medido — fluxos e execuções do tenant, tokens e custo
 * registrados no mês, usuários do login e chaves ativas. Os limites são os do cadastro do tenant; sem
 * cadastro não há limite a mostrar, e a tela diz isso em vez de desenhar uma barra contra um número
 * inventado (antes: 4,2M de 5M tokens fixos no código).
 */
export default function WorkspaceOverview() {
  const { t, i18n } = useTranslation()
  const locale = i18n.resolvedLanguage ?? i18n.language
  const [summary, setSummary] = useState<WorkspaceSummary | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)

    workspaceApi.summary()
      .then((dto) => {
        if (!cancelled) setSummary(dto)
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : t('admin.tenant.workspace.loadFailed'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const limits = summary?.limits ?? null
  const usage = summary?.usageThisMonth
  const limitHint = (key: string, n: number | undefined) =>
    n === undefined ? t('admin.tenant.workspace.hints.noLimit') : t(key, { limit: n.toLocaleString(locale) })
  // "Sem preço" afirma que houve consumo sem preço declarado. Sem nenhuma execução medida não houve
  // consumo: o custo do mês é zero, como os tokens — o custo nulo do backend aqui é só "nada somado".
  const costValue = !usage || usage.executions === 0 ? formatReais(0, locale)
    : usage.costReais == null ? t('usage.noPrice') : formatReais(usage.costReais, locale)
  const costHint = usage && usage.executionsWithoutPrice > 0
    ? t('usage.withoutPriceCount', { count: usage.executionsWithoutPrice })
    : t('admin.tenant.workspace.hints.costMonth')

  return (
    <Stack gap="lg" p="md" pos="relative">
      <LoadingOverlay visible={loading} />

      <PageHeader
        icon={IconLayoutGrid}
        accent="blue"
        title={t('admin.tenant.workspace.title')}
        subtitle={summary
          ? t('admin.tenant.workspace.subtitleNamed', { name: summary.tenantName })
          : t('admin.tenant.workspace.subtitle')}
        actions={summary?.plan && (
          <Badge variant="light" size="lg" color="blue" data-testid="workspace-plan">
            {t(`admin.superadmin.tenants.plans.${summary.plan}`, { defaultValue: summary.plan })}
          </Badge>
        )}
      />

      {error && (
        <Alert color="red" icon={<IconAlertCircle size={16} />}>
          {error}
        </Alert>
      )}

      <SimpleGrid cols={{ base: 1, xs: 2, md: 3 }} spacing="md">
        <StatCard label={t('admin.tenant.workspace.stats.workflows')} value={summary?.workflowCount ?? '—'}
          icon={IconTopologyRing} accent="blue"
          hint={limitHint('admin.tenant.workspace.hints.ofLimit', limits?.maxWorkflows)} />
        <StatCard label={t('admin.tenant.workspace.stats.executionsToday')} value={summary?.executionsToday ?? '—'}
          icon={IconPlayerPlay} accent="violet"
          hint={limitHint('admin.tenant.workspace.hints.perDay', limits?.executionsPerDay)} />
        <StatCard label={t('admin.tenant.workspace.stats.users')} value={summary?.userCount ?? '—'}
          icon={IconUsers} accent="teal"
          hint={limitHint('admin.tenant.workspace.hints.seats', limits?.maxUsers)} />
        <StatCard label={t('admin.tenant.workspace.stats.apiKeys')} value={summary?.apiKeyCount ?? '—'}
          icon={IconKey} accent="slate" hint={t('admin.tenant.workspace.hints.apiKeys')} />
        <StatCard label={t('admin.tenant.workspace.stats.tokensMonth')}
          value={summary ? compactTokens(summary.tokensThisMonth) : '—'}
          icon={IconCoins} accent="teal"
          hint={limitHint('admin.tenant.workspace.hints.tokensOfLimit', limits?.tokensPerMonth)} />
        <StatCard label={t('admin.tenant.workspace.stats.costMonth')} value={summary ? costValue : '—'}
          icon={IconCurrencyReal} accent="amber" hint={costHint} />
      </SimpleGrid>

      <SectionCard
        icon={IconGauge}
        accent="blue"
        title={t('admin.tenant.workspace.planUsage')}
        description={t('admin.tenant.workspace.planUsageHint')}
      >
        {summary && limits ? (
          <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="xl" verticalSpacing="lg">
            <UsageBar current={summary.executionsToday} limit={limits.executionsPerDay} label={t('admin.tenant.workspace.bars.execPerDay')} />
            <UsageBar current={summary.tokensThisMonth} limit={limits.tokensPerMonth} label={t('admin.tenant.workspace.bars.tokensPerMonth')} />
            <UsageBar current={summary.workflowCount} limit={limits.maxWorkflows} label={t('admin.tenant.workspace.bars.workflows')} />
            <UsageBar current={summary.userCount} limit={limits.maxUsers} label={t('admin.tenant.workspace.bars.users')} />
          </SimpleGrid>
        ) : summary ? (
          <Text size="sm" c="dimmed" data-testid="workspace-no-limits">{t('admin.tenant.workspace.noPlanLimits')}</Text>
        ) : (
          !loading && (
            <EmptyState compact icon={<IconGauge />} accent="blue"
              title={t('admin.tenant.workspace.summaryUnavailable')} />
          )
        )}
      </SectionCard>
    </Stack>
  )
}
