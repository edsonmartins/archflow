import { Stack, SimpleGrid, Paper, Text, LoadingOverlay, Alert } from '@mantine/core'
import { IconAlertCircle } from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { workspaceApi, type WorkspaceSummary } from '../../../services/admin-api'
import { UsageBar } from '../../../components/admin/UsageBar'

function StatCard({ label, value, color }: { label: string; value: string | number; color?: string }) {
  return (
    <div style={{
      padding: '18px 20px', borderRadius: 10,
      border: '1px solid var(--border)', background: 'var(--bg2)',
    }}>
      <div style={{ fontSize: 11, fontWeight: 500, color: 'var(--text3)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>{label}</div>
      <div style={{ fontSize: 28, fontWeight: 600, marginTop: 4, letterSpacing: '-0.5px', color: color ?? 'var(--text)' }}>{value}</div>
    </div>
  )
}

export default function WorkspaceOverview() {
  const { t } = useTranslation()
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

  return (
    <Stack gap="md" pos="relative">
      <LoadingOverlay visible={loading} />
      {error && (
        <Alert color="red" icon={<IconAlertCircle size={16} />}>
          {error}
        </Alert>
      )}

      <span style={{ fontSize: 22, fontWeight: 600, letterSpacing: '-0.3px', color: 'var(--text)' }}>{t('admin.tenant.workspace.title')}</span>

      <SimpleGrid cols={{ base: 2, lg: 4 }} spacing="md">
        <StatCard label={t('admin.tenant.workspace.stats.workflows')} value={summary?.workflowCount ?? '—'} />
        <StatCard label={t('admin.tenant.workspace.stats.executionsToday')} value={summary?.executionsToday ?? '—'} color="var(--blue)" />
        <StatCard label={t('admin.tenant.workspace.stats.users')} value={summary?.userCount ?? '—'} />
        <StatCard label={t('admin.tenant.workspace.stats.apiKeys')} value={summary?.apiKeyCount ?? '—'} />
      </SimpleGrid>

      <Paper withBorder p="lg" radius="lg">
        <Text fw={600} size="sm" mb="md">{t('admin.tenant.workspace.planUsage')}</Text>
        {summary ? (
          <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="md">
            <UsageBar current={summary.executionsToday} limit={summary.limits.executionsPerDay} label={t('admin.tenant.workspace.bars.execPerDay')} />
            <UsageBar current={summary.tokensThisMonth} limit={summary.limits.tokensPerMonth} label={t('admin.tenant.workspace.bars.tokensPerMonth')} />
            <UsageBar current={summary.workflowCount} limit={summary.limits.maxWorkflows} label={t('admin.tenant.workspace.bars.workflows')} />
            <UsageBar current={summary.userCount} limit={summary.limits.maxUsers} label={t('admin.tenant.workspace.bars.users')} />
          </SimpleGrid>
        ) : (
          <Text size="sm" c="dimmed">
            {t('admin.tenant.workspace.summaryUnavailable')}
          </Text>
        )}
      </Paper>
    </Stack>
  )
}
