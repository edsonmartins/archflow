import {
  Title, Table, Badge, Text, Paper, Stack, Group, Button, SimpleGrid, LoadingOverlay, Alert,
} from '@mantine/core'
import { IconPlus, IconAlertCircle } from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { tenantApi, type TenantStats } from '../../../services/admin-api'
import { useTenantStore } from '../../../stores/useTenantStore'
import { UsageBar } from '../../../components/admin/UsageBar'

const PLAN_COLORS: Record<string, string> = {
  enterprise:   'blue',
  professional: 'teal',
  trial:        'orange',
  internal:     'gray',
}

const STATUS_COLORS: Record<string, string> = {
  active:    'green',
  trial:     'orange',
  suspended: 'red',
}

function StatCard({ label, value, color }: { label: string; value: string | number; color: string }) {
  return (
    <div style={{
      padding: '18px 20px', borderRadius: 10,
      border: '1px solid var(--border)', background: 'var(--bg2)',
    }}>
      <div style={{ fontSize: 11, fontWeight: 500, color: 'var(--text3)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>{label}</div>
      <div style={{ fontSize: 28, fontWeight: 600, marginTop: 4, letterSpacing: '-0.5px', color }}>{value}</div>
    </div>
  )
}

export default function TenantList() {
  const navigate = useNavigate()
  const { startImpersonation, setTenants } = useTenantStore()
  const [tenants, setTenantRows] = useState<Array<{
    id: string
    name: string
    plan: string
    status: string
    usage?: { tokensThisMonth: number }
    limits?: { tokensPerMonth: number }
    createdAt?: string
    usageCount?: { workflows: number; executions: number }
  }>>([])
  const [stats, setStats] = useState<TenantStats | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)

    Promise.all([tenantApi.list(), tenantApi.stats()])
      .then(([tenantList, statsDto]) => {
        if (cancelled) return
        setTenants(tenantList)
        setTenantRows(tenantList as typeof tenants)
        setStats(statsDto)
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : 'Failed to load tenants')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [setTenants])

  const handleImpersonate = (tenant: typeof tenants[number]) => {
    sessionStorage.setItem('archflow_impersonate_tenant', tenant.id)
    startImpersonation({
      id: tenant.id,
      name: tenant.name,
      plan: tenant.plan as any,
      status: tenant.status as any,
    })
    navigate('/admin/workspace')
  }

  return (
    <Stack gap="md" pos="relative">
      <LoadingOverlay visible={loading} />

      <Group justify="space-between">
        <Title order={3}>Tenants</Title>
        <Button leftSection={<IconPlus size={16} />} onClick={() => navigate('/admin/tenants/new')}>
          New Tenant
        </Button>
      </Group>

      {error && (
        <Alert color="red" icon={<IconAlertCircle size={16} />}>
          {error}
        </Alert>
      )}

      <SimpleGrid cols={{ base: 2, lg: 4 }} spacing="md">
        <StatCard label="Active tenants" value={stats?.totalActive ?? '—'} color="var(--green)" />
        <StatCard label="Executions today" value={stats?.executionsToday ?? '—'} color="var(--blue)" />
        <StatCard label="Tokens this month" value={stats ? `${(stats.tokensThisMonth / 1_000_000).toFixed(1)}M` : '—'} color="var(--text)" />
        <StatCard label="Trial tenants" value={stats?.totalTrial ?? '—'} color="var(--amber)" />
      </SimpleGrid>

      <Paper withBorder radius="lg" style={{ overflow: 'hidden' }}>
        <Table striped highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Tenant</Table.Th>
              <Table.Th>Status</Table.Th>
              <Table.Th>Plan</Table.Th>
              <Table.Th>Created</Table.Th>
              <Table.Th w={160}>Token usage</Table.Th>
              <Table.Th w={100}>Actions</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {tenants.map(t => (
              <Table.Tr key={t.id}>
                <Table.Td>
                  <Group gap="sm" wrap="nowrap">
                    <div style={{
                      width: 32, height: 32, borderRadius: 8,
                      background: '#378ADD15', color: '#378ADD',
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                      fontWeight: 700, fontSize: 12,
                    }}>
                      {t.name.slice(0, 2).toUpperCase()}
                    </div>
                    <div>
                      <Text size="sm" fw={500}>{t.name}</Text>
                      <Text size="xs" c="dimmed" ff="monospace">{t.id}</Text>
                    </div>
                  </Group>
                </Table.Td>
                <Table.Td><Badge color={STATUS_COLORS[t.status] ?? 'gray'} size="sm">{t.status}</Badge></Table.Td>
                <Table.Td><Badge variant="light" color={PLAN_COLORS[t.plan] ?? 'gray'} size="sm">{t.plan}</Badge></Table.Td>
                <Table.Td><Text size="xs" c="dimmed">{t.createdAt ?? '—'}</Text></Table.Td>
                <Table.Td>
                  {t.usage?.tokensThisMonth !== undefined && t.limits?.tokensPerMonth ? (
                    <UsageBar current={t.usage.tokensThisMonth} limit={t.limits.tokensPerMonth} />
                  ) : (
                    <Text size="xs" c="dimmed">—</Text>
                  )}
                </Table.Td>
                <Table.Td>
                  <Group gap={4}>
                    <button
                      onClick={() => handleImpersonate(t)}
                      style={{
                        padding: '5px 12px', borderRadius: 6, fontSize: 12, fontWeight: 500,
                        cursor: 'pointer', fontFamily: 'var(--font-sans)',
                        border: '1px solid var(--border2)', background: 'var(--bg2)', color: 'var(--text2)',
                      }}
                    >Enter</button>
                    <button
                      onClick={() => navigate(`/admin/tenants/${t.id}`)}
                      style={{
                        width: 28, height: 28, borderRadius: 6, border: 'none',
                        background: 'transparent', cursor: 'pointer', color: 'var(--text3)',
                        display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 14,
                      }}
                    >⚙</button>
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
            {!loading && tenants.length === 0 && (
              <Table.Tr>
                <Table.Td colSpan={6}>
                  <Text size="sm" c="dimmed" ta="center" py="md">No tenants found.</Text>
                </Table.Td>
              </Table.Tr>
            )}
          </Table.Tbody>
        </Table>
      </Paper>
    </Stack>
  )
}
