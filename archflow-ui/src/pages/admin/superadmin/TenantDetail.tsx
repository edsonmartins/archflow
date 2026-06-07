import { Text, Paper, Stack, Group, Button, Badge, LoadingOverlay, Alert } from '@mantine/core'
import { IconAlertCircle } from '@tabler/icons-react'
import { PageHeader } from '../../../components/PageHeader'
import { StatusBadge } from '../../../components/StatusBadge'
import { useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { notifications } from '@mantine/notifications'
import { tenantApi, type TenantDetail as TenantDetailDto } from '../../../services/admin-api'
import { UsageBar } from '../../../components/admin/UsageBar'
import { confirmAction, confirmWithText } from '../../../lib/confirm'

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

  const doStatusAction = async () => {
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

  const handleStatusAction = () => {
    if (!id || !tenant) return
    // Activating a suspended tenant is benign — only the disruptive
    // suspend action needs confirmation.
    if (tenant.status === 'suspended') {
      void doStatusAction()
      return
    }
    confirmAction({
      title: t('confirmations.suspendTenantTitle'),
      message: t('confirmations.suspendTenantMessage', { name: tenant.name }),
      confirmLabel: t('confirmations.suspend'),
      onConfirm: doStatusAction,
    })
  }

  const doDelete = async () => {
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

  const handleDelete = () => {
    if (!id || !tenant) return
    // Highest-impact action in the app: require typing the tenant name.
    confirmWithText({
      title: t('confirmations.deleteTenantTitle'),
      message: t('confirmations.deleteTenantMessage', { name: tenant.name }),
      prompt: t('confirmations.deleteTenantPrompt'),
      expected: tenant.name,
      confirmLabel: t('confirmations.delete'),
      onConfirm: doDelete,
    })
  }

  return (
    <Stack gap="md" pos="relative">
      <LoadingOverlay visible={loading || submitting} />

      <PageHeader
        title={tenant?.name ?? t('admin.superadmin.tenantDetail.title')}
        subtitle={tenant?.id}
        backTo="/admin/tenants"
        backLabel={t('admin.superadmin.tenantDetail.back')}
        breadcrumbs={[
          { label: t('admin.layout.tenants'), to: '/admin/tenants' },
          { label: tenant?.name ?? t('admin.superadmin.tenantDetail.title') },
        ]}
      />

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
                <Badge variant="light" color="blue" size="sm">{t(`admin.superadmin.tenants.plans.${tenant.plan}`, { defaultValue: tenant.plan })}</Badge>
              </Group>
              <Group justify="space-between">
                <Text size="xs" c="dimmed">{t('admin.superadmin.tenantDetail.status')}</Text>
                <StatusBadge
                  status={tenant.status}
                  color={tenant.status === 'suspended' ? 'red' : tenant.status === 'trial' ? 'orange' : 'green'}
                  label={t(`admin.superadmin.tenants.statuses.${tenant.status}`, { defaultValue: tenant.status })}
                />
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
