import {
  Title, TextInput, Select, NumberInput, Button, Paper, Stack, Group, Divider, Text,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'

export default function TenantNew() {
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [tenantId, setTenantId] = useState('')
  const [email, setEmail] = useState('')
  const [sector, setSector] = useState<string | null>(null)
  const [plan, setPlan] = useState<string | null>('professional')
  const [execLimit, setExecLimit] = useState<number>(500)
  const [tokenLimit, setTokenLimit] = useState<number>(5000000)
  const [maxWorkflows, setMaxWorkflows] = useState<number>(20)
  const [maxUsers, setMaxUsers] = useState<number>(10)

  const handleCreate = () => {
    notifications.show({ title: 'Tenant created', message: `${name} created successfully`, color: 'teal' })
    navigate('/admin/tenants')
  }

  return (
    <Stack gap="md">
      <Group justify="space-between">
        <Title order={3}>Create Tenant</Title>
        <Button variant="subtle" onClick={() => navigate('/admin/tenants')}>← Back</Button>
      </Group>

      <div style={{ display: 'flex', gap: 20 }}>
        <Stack gap="md" style={{ flex: 1 }}>
          <Paper withBorder p="lg" radius="lg">
            <Text fw={600} size="sm" mb="md">Identity</Text>
            <Stack gap="sm">
              <TextInput label="Tenant name" required value={name}
                onChange={e => { setName(e.currentTarget.value); setTenantId(e.currentTarget.value.toLowerCase().replace(/\s+/g, '_')) }} />
              <TextInput label="Tenant ID" required value={tenantId} onChange={e => setTenantId(e.currentTarget.value)}
                description="URL-safe slug, must be unique" styles={{ input: { fontFamily: 'var(--font-mono)', fontSize: 12 } }} />
              <TextInput label="Admin email" required value={email} onChange={e => setEmail(e.currentTarget.value)}
                description="Invitation will be sent to this email" />
              <Select label="Sector" data={['Food Distribution', 'Legal', 'Healthcare', 'Fintech', 'Other']}
                value={sector} onChange={setSector} />
            </Stack>
          </Paper>

          <Paper withBorder p="lg" radius="lg">
            <Text fw={600} size="sm" mb="md">Plan & Limits</Text>
            <Stack gap="sm">
              <Select label="Plan" required data={[
                { value: 'trial', label: 'Trial (30 days)' },
                { value: 'professional', label: 'Professional' },
                { value: 'enterprise', label: 'Enterprise' },
                { value: 'internal', label: 'Internal' },
              ]} value={plan} onChange={setPlan} />
              <SimpleGrid cols={2}>
                <NumberInput label="Executions/day" value={execLimit} onChange={v => setExecLimit(Number(v))} min={1} />
                <NumberInput label="Tokens/month" value={tokenLimit} onChange={v => setTokenLimit(Number(v))} min={1000} step={100000} />
                <NumberInput label="Max workflows" value={maxWorkflows} onChange={v => setMaxWorkflows(Number(v))} min={1} />
                <NumberInput label="Max users" value={maxUsers} onChange={v => setMaxUsers(Number(v))} min={1} />
              </SimpleGrid>
            </Stack>
          </Paper>
        </Stack>

        {/* Summary panel */}
        <Paper withBorder p="lg" radius="lg" w={280} style={{ flexShrink: 0, position: 'sticky', top: 80, alignSelf: 'flex-start' }}>
          <Text fw={600} size="sm" mb="md">Summary</Text>
          <Stack gap="xs">
            <SummaryRow label="Name" value={name || '—'} />
            <SummaryRow label="ID" value={tenantId || '—'} mono />
            <SummaryRow label="Email" value={email || '—'} />
            <SummaryRow label="Plan" value={plan || '—'} />
            <Divider my="xs" />
            <SummaryRow label="Exec/day" value={String(execLimit)} />
            <SummaryRow label="Tokens/mo" value={tokenLimit.toLocaleString()} />
            <SummaryRow label="Workflows" value={String(maxWorkflows)} />
            <SummaryRow label="Users" value={String(maxUsers)} />
          </Stack>
          <Button fullWidth mt="lg" onClick={handleCreate} disabled={!name || !email}>
            Create tenant
          </Button>
        </Paper>
      </div>
    </Stack>
  )
}

function SummaryRow({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <Group justify="space-between">
      <Text size="xs" c="dimmed">{label}</Text>
      <Text size="xs" fw={500} ff={mono ? 'monospace' : undefined}>{value}</Text>
    </Group>
  )
}

function SimpleGrid({ cols, children }: { cols: number; children: React.ReactNode }) {
  return <div style={{ display: 'grid', gridTemplateColumns: `repeat(${cols}, 1fr)`, gap: 12 }}>{children}</div>
}
