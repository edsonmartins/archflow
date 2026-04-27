import {
  Title, Table, Badge, Text, Paper, Stack, Group, Button, SimpleGrid, LoadingOverlay, Alert,
} from '@mantine/core'
import { IconPlus, IconAlertCircle } from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
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
  const { t } = useTranslation()
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
        if (!cancelled) setError(e instanceof Error ? e.message : t('admin.superadmin.tenants.loadFailed'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
        <Title order={3}>{t('admin.superadmin.tenants.title')}</Title>
        <Button leftSection={<IconPlus size={16} />} onClick={() => navigate('/admin/tenants/new')}>
          {t('admin.superadmin.tenants.newTenant')}
        </Button>
      </Group>

      {error && (
        <Alert color="red" icon={<IconAlertCircle size={16} />}>
          {error}
        </Alert>
      )}

      <SimpleGrid cols={{ base: 2, lg: 4 }} spacing="md">
        <StatCard label={t('admin.superadmin.tenants.stats.active')} value={stats?.totalActive ?? '—'} color="var(--green)" />
        <StatCard label={t('admin.superadmin.tenants.stats.executionsToday')} value={stats?.executionsToday ?? '—'} color="var(--blue)" />
        <StatCard label={t('admin.superadmin.tenants.stats.tokensMonth')} value={stats ? `${(stats.tokensThisMonth / 1_000_000).toFixed(1)}M` : '—'} color="var(--text)" />
        <StatCard label={t('admin.superadmin.tenants.stats.trial')} value={stats?.totalTrial ?? '—'} color="var(--amber)" />
      </SimpleGrid>

      <Paper withBorder radius="lg" style={{ overflow: 'hidden' }}>
        <Table striped highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('admin.superadmin.tenants.columns.tenant')}</Table.Th>
              <Table.Th>{t('admin.superadmin.tenants.columns.status')}</Table.Th>
              <Table.Th>{t('admin.superadmin.tenants.columns.plan')}</Table.Th>
              <Table.Th>{t('admin.superadmin.tenants.columns.created')}</Table.Th>
              <Table.Th w={160}>{t('admin.superadmin.tenants.columns.tokenUsage')}</Table.Th>
              <Table.Th w={100}>{t('admin.superadmin.tenants.columns.actions')}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {/* Local variable is `tenant` so we don't shadow the
                useTranslation() `t` function — calling that on a tenant
                object would silently break rendering. */}
            {tenants.map(tenant => (
              <Table.Tr key={tenant.id}>
                <Table.Td>
                  <Group gap="sm" wrap="nowrap">
                    <div style={{
                      width: 32, height: 32, borderRadius: 8,
                      background: '#378ADD15', color: '#378ADD',
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                      fontWeight: 700, fontSize: 12,
                    }}>
                      {tenant.name.slice(0, 2).toUpperCase()}
                    </div>
                    <div>
                      <Text size="sm" fw={500}>{tenant.name}</Text>
                      <Text size="xs" c="dimmed" ff="monospace">{tenant.id}</Text>
                    </div>
                  </Group>
                </Table.Td>
                <Table.Td><Badge color={STATUS_COLORS[tenant.status] ?? 'gray'} size="sm">{tenant.status}</Badge></Table.Td>
                <Table.Td><Badge variant="light" color={PLAN_COLORS[tenant.plan] ?? 'gray'} size="sm">{tenant.plan}</Badge></Table.Td>
                <Table.Td><Text size="xs" c="dimmed">{tenant.createdAt ?? '—'}</Text></Table.Td>
                <Table.Td>
                  {tenant.usage?.tokensThisMonth !== undefined && tenant.limits?.tokensPerMonth ? (
                    <UsageBar current={tenant.usage.tokensThisMonth} limit={tenant.limits.tokensPerMonth} />
                  ) : (
                    <Text size="xs" c="dimmed">—</Text>
                  )}
                </Table.Td>
                <Table.Td>
                  <Group gap={4}>
                    <button
                      onClick={() => handleImpersonate(tenant)}
                      style={{
                        padding: '5px 12px', borderRadius: 6, fontSize: 12, fontWeight: 500,
                        cursor: 'pointer', fontFamily: 'var(--font-sans)',
                        border: '1px solid var(--border2)', background: 'var(--bg2)', color: 'var(--text2)',
                      }}
                    >{t('admin.superadmin.tenants.enter')}</button>
                    <button
                      onClick={() => navigate(`/admin/tenants/${tenant.id}`)}
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
                  <Text size="sm" c="dimmed" ta="center" py="md">{t('admin.superadmin.tenants.empty')}</Text>
                </Table.Td>
              </Table.Tr>
            )}
          </Table.Tbody>
        </Table>
      </Paper>
    </Stack>
  )
}
