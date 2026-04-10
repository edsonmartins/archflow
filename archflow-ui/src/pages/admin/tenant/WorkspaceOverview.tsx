import { Stack, SimpleGrid } from '@mantine/core'
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
  return (
    <Stack gap="md">
      <span style={{ fontSize: 22, fontWeight: 600, letterSpacing: '-0.3px', color: 'var(--text)' }}>Workspace Overview</span>

      <SimpleGrid cols={{ base: 2, lg: 4 }} spacing="md">
        <StatCard label="Workflows" value={12} />
        <StatCard label="Executions today" value={340} color="var(--blue)" />
        <StatCard label="Users" value={5} />
        <StatCard label="API Keys" value={3} />
      </SimpleGrid>

      <div style={{ border: '1px solid var(--border)', borderRadius: 10, background: 'var(--bg2)', padding: '20px' }}>
        <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 16, color: 'var(--text)' }}>Plan Usage</div>
        <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="md">
          <UsageBar current={340} limit={500} label="Executions/day" />
          <UsageBar current={4200000} limit={5000000} label="Tokens/month" />
          <UsageBar current={12} limit={20} label="Workflows" />
          <UsageBar current={5} limit={10} label="Users" />
        </SimpleGrid>
      </div>
    </Stack>
  )
}
