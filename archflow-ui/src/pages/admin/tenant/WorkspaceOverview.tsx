import { Stack, SimpleGrid, LoadingOverlay, Alert, Badge } from '@mantine/core'
import {
  IconAlertCircle, IconLayoutGrid, IconTopologyRing, IconPlayerPlay, IconUsers, IconKey, IconGauge,
} from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { workspaceApi, type WorkspaceSummary } from '../../../services/admin-api'
import { UsageBar } from '../../../components/admin/UsageBar'
import { PageHeader } from '../../../components/PageHeader'
import { SectionCard } from '../../../components/SectionCard'
import { StatCard } from '../../../components/admin/StatCard'
import EmptyState from '../../../components/EmptyState'

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

  const limit = (n: number | undefined) => (n === undefined ? '—' : n.toLocaleString(locale))

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
        actions={summary && (
          <Badge variant="light" size="lg" color="blue">
            {t(`admin.superadmin.tenants.plans.${summary.plan}`, { defaultValue: summary.plan })}
          </Badge>
        )}
      />

      {error && (
        <Alert color="red" icon={<IconAlertCircle size={16} />}>
          {error}
        </Alert>
      )}

      <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="md">
        <StatCard label={t('admin.tenant.workspace.stats.workflows')} value={summary?.workflowCount ?? '—'}
          icon={IconTopologyRing} accent="blue"
          hint={t('admin.tenant.workspace.hints.ofLimit', { limit: limit(summary?.limits.maxWorkflows) })} />
        <StatCard label={t('admin.tenant.workspace.stats.executionsToday')} value={summary?.executionsToday ?? '—'}
          icon={IconPlayerPlay} accent="violet"
          hint={t('admin.tenant.workspace.hints.perDay', { limit: limit(summary?.limits.executionsPerDay) })} />
        <StatCard label={t('admin.tenant.workspace.stats.users')} value={summary?.userCount ?? '—'}
          icon={IconUsers} accent="teal"
          hint={t('admin.tenant.workspace.hints.seats', { limit: limit(summary?.limits.maxUsers) })} />
        <StatCard label={t('admin.tenant.workspace.stats.apiKeys')} value={summary?.apiKeyCount ?? '—'}
          icon={IconKey} accent="slate" hint={t('admin.tenant.workspace.hints.apiKeys')} />
      </SimpleGrid>

      <SectionCard
        icon={IconGauge}
        accent="blue"
        title={t('admin.tenant.workspace.planUsage')}
        description={t('admin.tenant.workspace.planUsageHint')}
      >
        {summary ? (
          <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="xl" verticalSpacing="lg">
            <UsageBar current={summary.executionsToday} limit={summary.limits.executionsPerDay} label={t('admin.tenant.workspace.bars.execPerDay')} />
            <UsageBar current={summary.tokensThisMonth} limit={summary.limits.tokensPerMonth} label={t('admin.tenant.workspace.bars.tokensPerMonth')} />
            <UsageBar current={summary.workflowCount} limit={summary.limits.maxWorkflows} label={t('admin.tenant.workspace.bars.workflows')} />
            <UsageBar current={summary.userCount} limit={summary.limits.maxUsers} label={t('admin.tenant.workspace.bars.users')} />
          </SimpleGrid>
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
