import { Title, Text, Paper, Stack, Group, Button, Badge } from '@mantine/core'
import { useParams, useNavigate } from 'react-router-dom'
import { UsageBar } from '../../../components/admin/UsageBar'

export default function TenantDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()

  return (
    <Stack gap="md">
      <Group justify="space-between">
        <Group gap="xs">
          <Title order={3}>Tenant Detail</Title>
          <Badge variant="light">{id}</Badge>
        </Group>
        <Button variant="subtle" onClick={() => navigate('/admin/tenants')}>← Back</Button>
      </Group>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
        <Paper withBorder p="lg" radius="lg">
          <Text fw={600} size="sm" mb="md">Identity</Text>
          <Stack gap="xs">
            <InfoRow label="Name" value="Rio Quality" />
            <InfoRow label="Tenant ID" value={id ?? ''} mono />
            <InfoRow label="Admin" value="admin@rioquality.com.br" />
            <InfoRow label="Sector" value="Food Distribution" />
            <InfoRow label="Created" value="June 2025" />
          </Stack>
        </Paper>

        <Paper withBorder p="lg" radius="lg">
          <Text fw={600} size="sm" mb="md">Plan & Status</Text>
          <Stack gap="xs">
            <Group justify="space-between">
              <Text size="xs" c="dimmed">Plan</Text>
              <Badge color="blue" size="sm">Enterprise</Badge>
            </Group>
            <Group justify="space-between">
              <Text size="xs" c="dimmed">Status</Text>
              <Badge color="green" size="sm">Active</Badge>
            </Group>
          </Stack>
          <Group mt="md" gap="xs">
            <Button size="xs" color="red" variant="light">Suspend</Button>
          </Group>
        </Paper>

        <Paper withBorder p="lg" radius="lg" style={{ gridColumn: '1 / -1' }}>
          <Text fw={600} size="sm" mb="md">Usage</Text>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
            <UsageBar current={340} limit={500} label="Executions/day" />
            <UsageBar current={4200000} limit={5000000} label="Tokens/month" />
            <UsageBar current={12} limit={20} label="Workflows" />
            <UsageBar current={5} limit={10} label="Users" />
          </div>
        </Paper>
      </div>
    </Stack>
  )
}

function InfoRow({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <Group justify="space-between">
      <Text size="xs" c="dimmed">{label}</Text>
      <Text size="xs" fw={500} ff={mono ? 'monospace' : undefined}>{value}</Text>
    </Group>
  )
}
