import { Title, Text, Paper, Stack, Group, Button, Badge, LoadingOverlay, Alert } from '@mantine/core'
import { IconAlertCircle } from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { notifications } from '@mantine/notifications'
import { tenantApi, type TenantDetail as TenantDetailDto } from '../../../services/admin-api'
import { UsageBar } from '../../../components/admin/UsageBar'

export default function TenantDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [tenant, setTenant] = useState<TenantDetailDto | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    if (!id) return
    let cancelled = false
    setLoading(true)
    setError(null)

    tenantApi.get(id)
      .then((dto) => {
        if (!cancelled) setTenant(dto)
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : 'Failed to load tenant')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [id])

  const reload = async () => {
    if (!id) return
    const dto = await tenantApi.get(id)
    setTenant(dto)
  }

  const handleStatusAction = async () => {
    if (!id || !tenant) return
    setSubmitting(true)
    try {
      if (tenant.status === 'suspended') {
        await tenantApi.activate(id)
        notifications.show({ title: 'Tenant activated', message: tenant.name, color: 'teal' })
      } else {
        await tenantApi.suspend(id)
        notifications.show({ title: 'Tenant suspended', message: tenant.name, color: 'orange' })
      }
      await reload()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to update tenant')
    } finally {
      setSubmitting(false)
    }
  }

  const handleDelete = async () => {
    if (!id || !tenant) return
    setSubmitting(true)
    try {
      await tenantApi.delete(id)
      notifications.show({ title: 'Tenant deleted', message: tenant.name, color: 'red' })
      navigate('/admin/tenants')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to delete tenant')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Stack gap="md" pos="relative">
      <LoadingOverlay visible={loading || submitting} />

      <Group justify="space-between">
        <Group gap="xs">
          <Title order={3}>Tenant Detail</Title>
          {tenant && <Badge variant="light">{tenant.id}</Badge>}
        </Group>
        <Button variant="subtle" onClick={() => navigate('/admin/tenants')}>← Back</Button>
      </Group>

      {error && (
        <Alert color="red" icon={<IconAlertCircle size={16} />}>
          {error}
        </Alert>
      )}

      {tenant && (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
          <Paper withBorder p="lg" radius="lg">
            <Text fw={600} size="sm" mb="md">Identity</Text>
            <Stack gap="xs">
              <InfoRow label="Name" value={tenant.name} />
              <InfoRow label="Tenant ID" value={tenant.id} mono />
              <InfoRow label="Admin" value={tenant.adminEmail} />
              <InfoRow label="Sector" value={tenant.sector} />
              <InfoRow label="Created" value={tenant.createdAt} />
            </Stack>
          </Paper>

          <Paper withBorder p="lg" radius="lg">
            <Text fw={600} size="sm" mb="md">Plan & Status</Text>
            <Stack gap="xs">
              <Group justify="space-between">
                <Text size="xs" c="dimmed">Plan</Text>
                <Badge color="blue" size="sm">{tenant.plan}</Badge>
              </Group>
              <Group justify="space-between">
                <Text size="xs" c="dimmed">Status</Text>
                <Badge color={tenant.status === 'suspended' ? 'red' : tenant.status === 'trial' ? 'orange' : 'green'} size="sm">{tenant.status}</Badge>
              </Group>
              <Group justify="space-between">
                <Text size="xs" c="dimmed">Allowed models</Text>
                <Text size="xs" fw={500}>{tenant.allowedModels.join(', ') || '—'}</Text>
              </Group>
            </Stack>
            <Group mt="md" gap="xs">
              <Button size="xs" color={tenant.status === 'suspended' ? 'teal' : 'orange'} variant="light" onClick={handleStatusAction}>
                {tenant.status === 'suspended' ? 'Activate' : 'Suspend'}
              </Button>
              <Button size="xs" color="red" variant="subtle" onClick={handleDelete}>
                Delete
              </Button>
            </Group>
          </Paper>

          <Paper withBorder p="lg" radius="lg" style={{ gridColumn: '1 / -1' }}>
            <Text fw={600} size="sm" mb="md">Usage</Text>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
              <UsageBar current={tenant.usage.executionsToday} limit={tenant.limits.executionsPerDay} label="Executions/day" />
              <UsageBar current={tenant.usage.tokensThisMonth} limit={tenant.limits.tokensPerMonth} label="Tokens/month" />
              <UsageBar current={tenant.usage.workflowCount} limit={tenant.limits.maxWorkflows} label="Workflows" />
              <UsageBar current={tenant.usage.userCount} limit={tenant.limits.maxUsers} label="Users" />
            </div>
          </Paper>
        </div>
      )}
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
