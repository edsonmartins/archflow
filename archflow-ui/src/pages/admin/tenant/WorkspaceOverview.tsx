import { Stack, SimpleGrid, Paper, Text, LoadingOverlay, Alert } from '@mantine/core'
import { IconAlertCircle } from '@tabler/icons-react'
import { useEffect, useState } from 'react'
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
        if (!cancelled) setError(e instanceof Error ? e.message : 'Failed to load workspace')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [])

  return (
    <Stack gap="md" pos="relative">
      <LoadingOverlay visible={loading} />
      {error && (
        <Alert color="red" icon={<IconAlertCircle size={16} />}>
          {error}
        </Alert>
      )}

      <span style={{ fontSize: 22, fontWeight: 600, letterSpacing: '-0.3px', color: 'var(--text)' }}>Workspace Overview</span>

      <SimpleGrid cols={{ base: 2, lg: 4 }} spacing="md">
        <StatCard label="Workflows" value={summary?.workflowCount ?? '—'} />
        <StatCard label="Executions today" value={summary?.executionsToday ?? '—'} color="var(--blue)" />
        <StatCard label="Users" value={summary?.userCount ?? '—'} />
        <StatCard label="API Keys" value={summary?.apiKeyCount ?? '—'} />
      </SimpleGrid>

      <Paper withBorder p="lg" radius="lg">
        <Text fw={600} size="sm" mb="md">Plan Usage</Text>
        {summary ? (
          <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="md">
            <UsageBar current={summary.executionsToday} limit={summary.limits.executionsPerDay} label="Executions/day" />
            <UsageBar current={summary.tokensThisMonth} limit={summary.limits.tokensPerMonth} label="Tokens/month" />
            <UsageBar current={summary.workflowCount} limit={summary.limits.maxWorkflows} label="Workflows" />
            <UsageBar current={summary.userCount} limit={summary.limits.maxUsers} label="Users" />
          </SimpleGrid>
        ) : (
          <Text size="sm" c="dimmed">
            Workspace summary is unavailable right now.
          </Text>
        )}
      </Paper>
    </Stack>
  )
}
