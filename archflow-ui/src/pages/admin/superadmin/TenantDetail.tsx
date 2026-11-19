import { Text, Stack, Group, Button, Badge, LoadingOverlay, Alert, SimpleGrid, Box } from '@mantine/core'
import {
  IconAlertCircle, IconBuildingSkyscraper, IconId, IconCreditCard, IconChartBar, IconAlertTriangle,
  IconPlayerPause, IconPlayerPlay, IconTrash,
} from '@tabler/icons-react'
import type { ReactNode } from 'react'
import { PageHeader } from '../../../components/PageHeader'
import { SectionCard } from '../../../components/SectionCard'
import { StatusBadge } from '../../../components/StatusBadge'
import { useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { notifications } from '@mantine/notifications'
import { tenantApi, type TenantDetail as TenantDetailDto } from '../../../services/admin-api'
import { UsageBar } from '../../../components/admin/UsageBar'
import { confirmAction, confirmWithText } from '../../../lib/confirm'
import { PLAN_COLORS, TENANT_STATUS_COLORS } from './tenantColors'

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

  const suspended = tenant?.status === 'suspended'

  return (
    <Stack gap="lg" p="md" pos="relative">
      <LoadingOverlay visible={loading || submitting} />

      <PageHeader
        icon={IconBuildingSkyscraper}
        accent="blue"
        title={tenant?.name ?? t('admin.superadmin.tenantDetail.title')}
        subtitle={tenant ? t('admin.superadmin.tenantDetail.subtitle', { id: tenant.id }) : undefined}
        backTo="/admin/tenants"
        backLabel={t('common.back')}
        breadcrumbs={[
          { label: t('admin.layout.tenants'), to: '/admin/tenants' },
          { label: tenant?.name ?? t('admin.superadmin.tenantDetail.title') },
        ]}
        actions={tenant && (
          <Button
            variant="light"
            color={suspended ? 'teal' : 'orange'}
            leftSection={suspended ? <IconPlayerPlay size={16} /> : <IconPlayerPause size={16} />}
            onClick={handleStatusAction}
          >
            {suspended ? t('admin.superadmin.tenantDetail.activate') : t('admin.superadmin.tenantDetail.suspend')}
          </Button>
        )}
      />

      {error && (
        <Alert color="red" icon={<IconAlertCircle size={16} />}>
          {error}
        </Alert>
      )}

      {tenant && (
        <>
          <SimpleGrid cols={{ base: 1, md: 2 }} spacing="lg">
            <SectionCard icon={IconId} accent="blue"
              title={t('admin.superadmin.tenantDetail.identity')}
              description={t('admin.superadmin.tenantDetail.identityHint')}>
              <SimpleGrid cols={{ base: 1, xs: 2 }} spacing="md" verticalSpacing="md">
                <Prop label={t('admin.superadmin.tenantDetail.name')}>{tenant.name}</Prop>
                <Prop label={t('admin.superadmin.tenantDetail.tenantId')}><Text size="sm" c="dimmed" truncate>{tenant.id}</Text></Prop>
                <Prop label={t('admin.superadmin.tenantDetail.admin')}>{tenant.adminEmail}</Prop>
                <Prop label={t('admin.superadmin.tenantDetail.sector')}>{tenant.sector}</Prop>
                <Prop label={t('admin.superadmin.tenantDetail.created')}>{tenant.createdAt}</Prop>
              </SimpleGrid>
            </SectionCard>

            <SectionCard icon={IconCreditCard} accent="teal"
              title={t('admin.superadmin.tenantDetail.planStatus')}
              description={t('admin.superadmin.tenantDetail.planStatusHint')}>
              <SimpleGrid cols={{ base: 1, xs: 2 }} spacing="md" verticalSpacing="md">
                <Prop label={t('admin.superadmin.tenantDetail.plan')}>
                  <Badge variant="light" color={PLAN_COLORS[tenant.plan] ?? 'gray'} size="sm">
                    {t(`admin.superadmin.tenants.plans.${tenant.plan}`, { defaultValue: tenant.plan })}
                  </Badge>
                </Prop>
                <Prop label={t('admin.superadmin.tenantDetail.status')}>
                  <StatusBadge
                    status={tenant.status}
                    color={TENANT_STATUS_COLORS[tenant.status] ?? 'gray'}
                    label={t(`admin.superadmin.tenants.statuses.${tenant.status}`, { defaultValue: tenant.status })}
                  />
                </Prop>
                <Box style={{ gridColumn: '1 / -1' }}>
                  <Prop label={t('admin.superadmin.tenantDetail.allowedModels')}>
                    {tenant.allowedModels.length > 0 ? (
                      <Group gap={6}>
                        {tenant.allowedModels.map((model) => (
                          <Badge key={model} variant="default" size="sm" tt="none">{model}</Badge>
                        ))}
                      </Group>
                    ) : '—'}
                  </Prop>
                </Box>
              </SimpleGrid>
            </SectionCard>
          </SimpleGrid>

          <SectionCard icon={IconChartBar} accent="violet"
            title={t('admin.superadmin.tenantDetail.usage')}
            description={t('admin.superadmin.tenantDetail.usageHint')}>
            <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="xl" verticalSpacing="lg">
              <UsageBar current={tenant.usage.executionsToday} limit={tenant.limits.executionsPerDay} label={t('admin.superadmin.tenantDetail.bars.execPerDay')} />
              <UsageBar current={tenant.usage.tokensThisMonth} limit={tenant.limits.tokensPerMonth} label={t('admin.superadmin.tenantDetail.bars.tokensPerMonth')} />
              <UsageBar current={tenant.usage.workflowCount} limit={tenant.limits.maxWorkflows} label={t('admin.superadmin.tenantDetail.bars.workflows')} />
              <UsageBar current={tenant.usage.userCount} limit={tenant.limits.maxUsers} label={t('admin.superadmin.tenantDetail.bars.users')} />
            </SimpleGrid>
          </SectionCard>

          <SectionCard icon={IconAlertTriangle} accent="rose"
            title={t('admin.superadmin.tenantDetail.dangerTitle')}
            description={t('admin.superadmin.tenantDetail.dangerHint')}
            actions={
              <Button size="xs" color="red" variant="light" leftSection={<IconTrash size={14} />} onClick={handleDelete}>
                {t('admin.superadmin.tenantDetail.delete')}
              </Button>
            }>
            <Text size="sm" c="dimmed">{t('admin.superadmin.tenantDetail.dangerBody')}</Text>
          </SectionCard>
        </>
      )}
    </Stack>
  )
}

/** Par rótulo/valor do cartão de propriedades. */
function Prop({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Stack gap={4} style={{ minWidth: 0 }}>
      <Text size="xs" fw={600} c="dimmed" tt="uppercase" style={{ letterSpacing: '0.06em' }}>{label}</Text>
      {typeof children === 'string' ? <Text size="sm" fw={500} truncate>{children}</Text> : children}
    </Stack>
  )
}
