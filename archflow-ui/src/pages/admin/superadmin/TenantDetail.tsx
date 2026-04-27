import { Title, Text, Paper, Stack, Group, Button, Badge, LoadingOverlay, Alert } from '@mantine/core'
import { IconAlertCircle } from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { notifications } from '@mantine/notifications'
import { tenantApi, type TenantDetail as TenantDetailDto } from '../../../services/admin-api'
import { UsageBar } from '../../../components/admin/UsageBar'

export default function TenantDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { t } = useTranslation()
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
        if (!cancelled) setError(e instanceof Error ? e.message : t('admin.superadmin.tenantDetail.loadFailed'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
        notifications.show({ title: t('admin.superadmin.tenantDetail.activated'), message: tenant.name, color: 'teal' })
      } else {
        await tenantApi.suspend(id)
        notifications.show({ title: t('admin.superadmin.tenantDetail.suspended'), message: tenant.name, color: 'orange' })
      }
      await reload()
    } catch (e) {
      setError(e instanceof Error ? e.message : t('admin.superadmin.tenantDetail.updateFailed'))
    } finally {
      setSubmitting(false)
    }
  }

  const handleDelete = async () => {
    if (!id || !tenant) return
    setSubmitting(true)
    try {
      await tenantApi.delete(id)
      notifications.show({ title: t('admin.superadmin.tenantDetail.deleted'), message: tenant.name, color: 'red' })
      navigate('/admin/tenants')
    } catch (e) {
      setError(e instanceof Error ? e.message : t('admin.superadmin.tenantDetail.deleteFailed'))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Stack gap="md" pos="relative">
      <LoadingOverlay visible={loading || submitting} />

      <Group justify="space-between">
        <Group gap="xs">
          <Title order={3}>{t('admin.superadmin.tenantDetail.title')}</Title>
          {tenant && <Badge variant="light">{tenant.id}</Badge>}
        </Group>
        <Button variant="subtle" onClick={() => navigate('/admin/tenants')}>{t('admin.superadmin.tenantDetail.back')}</Button>
      </Group>

      {error && (
        <Alert color="red" icon={<IconAlertCircle size={16} />}>
          {error}
        </Alert>
      )}

      {tenant && (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
          <Paper withBorder p="lg" radius="lg">
            <Text fw={600} size="sm" mb="md">{t('admin.superadmin.tenantDetail.identity')}</Text>
            <Stack gap="xs">
              <InfoRow label={t('admin.superadmin.tenantDetail.name')} value={tenant.name} />
              <InfoRow label={t('admin.superadmin.tenantDetail.tenantId')} value={tenant.id} mono />
              <InfoRow label={t('admin.superadmin.tenantDetail.admin')} value={tenant.adminEmail} />
              <InfoRow label={t('admin.superadmin.tenantDetail.sector')} value={tenant.sector} />
              <InfoRow label={t('admin.superadmin.tenantDetail.created')} value={tenant.createdAt} />
            </Stack>
          </Paper>

          <Paper withBorder p="lg" radius="lg">
            <Text fw={600} size="sm" mb="md">{t('admin.superadmin.tenantDetail.planStatus')}</Text>
            <Stack gap="xs">
              <Group justify="space-between">
                <Text size="xs" c="dimmed">{t('admin.superadmin.tenantDetail.plan')}</Text>
                <Badge color="blue" size="sm">{tenant.plan}</Badge>
              </Group>
              <Group justify="space-between">
                <Text size="xs" c="dimmed">{t('admin.superadmin.tenantDetail.status')}</Text>
                <Badge color={tenant.status === 'suspended' ? 'red' : tenant.status === 'trial' ? 'orange' : 'green'} size="sm">{tenant.status}</Badge>
              </Group>
              <Group justify="space-between">
                <Text size="xs" c="dimmed">{t('admin.superadmin.tenantDetail.allowedModels')}</Text>
                <Text size="xs" fw={500}>{tenant.allowedModels.join(', ') || '—'}</Text>
              </Group>
            </Stack>
            <Group mt="md" gap="xs">
              <Button size="xs" color={tenant.status === 'suspended' ? 'teal' : 'orange'} variant="light" onClick={handleStatusAction}>
                {tenant.status === 'suspended' ? t('admin.superadmin.tenantDetail.activate') : t('admin.superadmin.tenantDetail.suspend')}
              </Button>
              <Button size="xs" color="red" variant="subtle" onClick={handleDelete}>
                {t('admin.superadmin.tenantDetail.delete')}
              </Button>
            </Group>
          </Paper>

          <Paper withBorder p="lg" radius="lg" style={{ gridColumn: '1 / -1' }}>
            <Text fw={600} size="sm" mb="md">{t('admin.superadmin.tenantDetail.usage')}</Text>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
              <UsageBar current={tenant.usage.executionsToday} limit={tenant.limits.executionsPerDay} label={t('admin.superadmin.tenantDetail.bars.execPerDay')} />
              <UsageBar current={tenant.usage.tokensThisMonth} limit={tenant.limits.tokensPerMonth} label={t('admin.superadmin.tenantDetail.bars.tokensPerMonth')} />
              <UsageBar current={tenant.usage.workflowCount} limit={tenant.limits.maxWorkflows} label={t('admin.superadmin.tenantDetail.bars.workflows')} />
              <UsageBar current={tenant.usage.userCount} limit={tenant.limits.maxUsers} label={t('admin.superadmin.tenantDetail.bars.users')} />
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
