import {
  Title, Table, Badge, Text, Paper, Stack, Group, Button, ActionIcon, Tooltip,
  SimpleGrid, LoadingOverlay,
} from '@mantine/core'
import { IconPlus, IconLogin, IconSettings } from '@tabler/icons-react'
import { useNavigate } from 'react-router-dom'
import { useTenantStore } from '../../../stores/useTenantStore'
import { UsageBar } from '../../../components/admin/UsageBar'

const MOCK_TENANTS = [
  { id: 'tenant_rio_quality', name: 'Rio Quality', plan: 'enterprise' as const, status: 'active' as const, workflows: 12, execPerDay: 340, tokensUsed: 4200000, tokensLimit: 5000000, createdAt: '2025-06' },
  { id: 'tenant_acme_corp',   name: 'Acme Corp',   plan: 'professional' as const, status: 'active' as const, workflows: 5,  execPerDay: 85,  tokensUsed: 1200000, tokensLimit: 2000000, createdAt: '2025-09' },
  { id: 'tenant_demo',        name: 'Demo Trial',   plan: 'trial' as const,        status: 'trial' as const,  workflows: 2,  execPerDay: 12,  tokensUsed: 50000,   tokensLimit: 100000,  createdAt: '2026-04' },
]

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
  const { startImpersonation } = useTenantStore()

  const handleImpersonate = (tenant: typeof MOCK_TENANTS[0]) => {
    sessionStorage.setItem('archflow_impersonate_tenant', tenant.id)
    startImpersonation({ id: tenant.id, name: tenant.name, plan: tenant.plan, status: tenant.status })
    navigate('/admin/workspace')
  }

  return (
    <Stack gap="md">
      <Group justify="space-between">
        <Title order={3}>Tenants</Title>
        <Button leftSection={<IconPlus size={16} />} onClick={() => navigate('/admin/tenants/new')}>
          New Tenant
        </Button>
      </Group>

      <SimpleGrid cols={{ base: 2, lg: 4 }} spacing="md">
        <StatCard label="Active tenants" value={2} color="var(--green)" />
        <StatCard label="Executions today" value="437" color="var(--blue)" />
        <StatCard label="Tokens this month" value="5.4M" color="var(--text)" />
        <StatCard label="Trial tenants" value={1} color="var(--amber)" />
      </SimpleGrid>

      <Paper withBorder radius="lg" style={{ overflow: 'hidden' }}>
        <Table striped highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Tenant</Table.Th>
              <Table.Th>Status</Table.Th>
              <Table.Th>Plan</Table.Th>
              <Table.Th>Workflows</Table.Th>
              <Table.Th>Exec/day</Table.Th>
              <Table.Th w={160}>Token usage</Table.Th>
              <Table.Th>Created</Table.Th>
              <Table.Th w={100}>Actions</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {MOCK_TENANTS.map(t => (
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
                <Table.Td><Badge color={STATUS_COLORS[t.status]} size="sm">{t.status}</Badge></Table.Td>
                <Table.Td><Badge variant="light" color={PLAN_COLORS[t.plan]} size="sm">{t.plan}</Badge></Table.Td>
                <Table.Td><Text size="sm">{t.workflows}</Text></Table.Td>
                <Table.Td><Text size="sm">{t.execPerDay}</Text></Table.Td>
                <Table.Td>
                  <UsageBar current={t.tokensUsed} limit={t.tokensLimit} />
                </Table.Td>
                <Table.Td><Text size="xs" c="dimmed">{t.createdAt}</Text></Table.Td>
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
          </Table.Tbody>
        </Table>
      </Paper>
    </Stack>
  )
}
