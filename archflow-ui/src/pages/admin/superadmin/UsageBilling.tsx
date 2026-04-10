import { Title, Table, Text, Paper, Stack, Group, Select, Button, SimpleGrid } from '@mantine/core'
import { IconDownload } from '@tabler/icons-react'
import { useState } from 'react'

const MOCK_USAGE = [
  { tenant: 'Rio Quality',  exec: 8400, tokIn: 3200000,  tokOut: 1000000, cost: 48.50,  pct: 62 },
  { tenant: 'Acme Corp',    exec: 2100, tokIn: 800000,   tokOut: 400000,  cost: 18.20,  pct: 24 },
  { tenant: 'Demo Trial',   exec: 350,  tokIn: 40000,    tokOut: 10000,   cost: 1.10,   pct: 14 },
]

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

  return (
    <Stack gap="md">
      <Group justify="space-between">
        <Title order={3}>Usage & Billing</Title>
        <Group gap="sm">
          <Select size="sm" w={140} data={['2026-04', '2026-03', '2026-02', '2026-01']}
            value={month} onChange={setMonth} />
          <Button variant="light" leftSection={<IconDownload size={14} />} size="sm">Export CSV</Button>
        </Group>
      </Group>

      <SimpleGrid cols={{ base: 2, lg: 4 }} spacing="md">
        <StatCard label="Executions" value="10,850" />
        <StatCard label="Tokens consumed" value="5.4M" />
        <StatCard label="Estimated cost" value="$67.80" />
        <StatCard label="Avg latency" value="1.2s" />
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
            {MOCK_USAGE.map(r => (
              <Table.Tr key={r.tenant}>
                <Table.Td><Text size="sm" fw={500}>{r.tenant}</Text></Table.Td>
                <Table.Td><Text size="sm">{r.exec.toLocaleString()}</Text></Table.Td>
                <Table.Td><Text size="sm">{(r.tokIn / 1000000).toFixed(1)}M</Text></Table.Td>
                <Table.Td><Text size="sm">{(r.tokOut / 1000000).toFixed(1)}M</Text></Table.Td>
                <Table.Td><Text size="sm" fw={500}>${r.cost.toFixed(2)}</Text></Table.Td>
                <Table.Td><Text size="sm">{r.pct}%</Text></Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Paper>
    </Stack>
  )
}
