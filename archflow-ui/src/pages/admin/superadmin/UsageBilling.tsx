import { Title, Table, Text, Paper, Stack, Group, Select, Button, SimpleGrid, LoadingOverlay, Alert } from '@mantine/core'
import { IconDownload, IconAlertCircle } from '@tabler/icons-react'
import { useEffect, useMemo, useState } from 'react'
import { usageApi, type TenantUsageRow } from '../../../services/admin-api'

function StatCard({ label, value }: { label: string; value: string }) {
  return (
    <div style={{
      padding: '18px 20px', borderRadius: 10,
      border: '1px solid var(--border)', background: 'var(--bg2)',
    }}>
      <div style={{ fontSize: 11, fontWeight: 500, color: 'var(--text3)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>{label}</div>
      <div style={{ fontSize: 28, fontWeight: 600, marginTop: 4, letterSpacing: '-0.5px', color: 'var(--text)' }}>{value}</div>
    </div>
  )
}

export default function UsageBilling() {
  const [month, setMonth] = useState<string | null>('2026-04')
  const [rows, setRows] = useState<TenantUsageRow[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!month) return
    let cancelled = false
    setLoading(true)
    setError(null)

    usageApi.byTenant(month)
      .then((dto) => {
        if (!cancelled) setRows(dto)
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : 'Failed to load usage')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [month])

  const totals = useMemo(() => {
    const executions = rows.reduce((sum, row) => sum + row.executions, 0)
    const tokens = rows.reduce((sum, row) => sum + row.tokensInput + row.tokensOutput, 0)
    const cost = rows.reduce((sum, row) => sum + row.estimatedCost, 0)
    return { executions, tokens, cost }
  }, [rows])

  return (
    <Stack gap="md" pos="relative">
      <LoadingOverlay visible={loading} />

      <Group justify="space-between">
        <Title order={3}>Usage & Billing</Title>
        <Group gap="sm">
          <Select size="sm" w={140} data={['2026-04', '2026-03', '2026-02', '2026-01']}
            value={month} onChange={setMonth} />
          <Button
            component="a"
            href={usageApi.exportCsv(month ?? '')}
            variant="light"
            leftSection={<IconDownload size={14} />}
            size="sm"
          >Export CSV</Button>
        </Group>
      </Group>

      {error && (
        <Alert color="red" icon={<IconAlertCircle size={16} />}>
          {error}
        </Alert>
      )}

      <SimpleGrid cols={{ base: 2, lg: 4 }} spacing="md">
        <StatCard label="Executions" value={totals.executions.toLocaleString()} />
        <StatCard label="Tokens consumed" value={`${(totals.tokens / 1_000_000).toFixed(1)}M`} />
        <StatCard label="Estimated cost" value={`$${totals.cost.toFixed(2)}`} />
        <StatCard label="Tenants" value={String(rows.length)} />
      </SimpleGrid>

      <Paper withBorder radius="lg" style={{ overflow: 'hidden' }}>
        <Table striped highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Tenant</Table.Th>
              <Table.Th>Executions</Table.Th>
              <Table.Th>Tokens In</Table.Th>
              <Table.Th>Tokens Out</Table.Th>
              <Table.Th>Est. Cost</Table.Th>
              <Table.Th>% of Total</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map(r => (
              <Table.Tr key={r.tenantId}>
                <Table.Td><Text size="sm" fw={500}>{r.tenantName}</Text></Table.Td>
                <Table.Td><Text size="sm">{r.executions.toLocaleString()}</Text></Table.Td>
                <Table.Td><Text size="sm">{(r.tokensInput / 1000000).toFixed(1)}M</Text></Table.Td>
                <Table.Td><Text size="sm">{(r.tokensOutput / 1000000).toFixed(1)}M</Text></Table.Td>
                <Table.Td><Text size="sm" fw={500}>${r.estimatedCost.toFixed(2)}</Text></Table.Td>
                <Table.Td><Text size="sm">{r.percentOfTotal}%</Text></Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Paper>
    </Stack>
  )
}
