import {
  TextInput, Select, NumberInput, Button, Stack, Group, Divider, Text, Alert, SimpleGrid, Grid,
} from '@mantine/core'
import { IconAlertCircle, IconBuildingSkyscraper, IconId, IconGauge, IconListCheck, IconCheck } from '@tabler/icons-react'
import { notifications } from '@mantine/notifications'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { tenantApi } from '../../../services/admin-api'
import { PageHeader } from '../../../components/PageHeader'
import { SectionCard } from '../../../components/SectionCard'
import { AllowedModelsInput } from './AllowedModelsInput'

export default function TenantNew() {
  const navigate = useNavigate()
  const { t, i18n } = useTranslation()
  const locale = i18n.resolvedLanguage ?? i18n.language
  const [name, setName] = useState('')
  const [tenantId, setTenantId] = useState('')
  const [email, setEmail] = useState('')
  const [sector, setSector] = useState<string | null>(null)
  const [plan, setPlan] = useState<string | null>('professional')
  const [execLimit, setExecLimit] = useState<number>(500)
  const [tokenLimit, setTokenLimit] = useState<number>(5000000)
  const [maxWorkflows, setMaxWorkflows] = useState<number>(20)
  const [maxUsers, setMaxUsers] = useState<number>(10)
  // Vazio = todo o catálogo ativo (RCHFLW-79); antes a criação nem tinha o campo.
  const [allowedModels, setAllowedModels] = useState<string[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleCreate = async () => {
    if (!plan || !sector) return
    setLoading(true)
    setError(null)
    try {
      await tenantApi.create({
        name,
        tenantId,
        adminEmail: email,
        sector,
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        plan: plan as any,
        limits: {
          executionsPerDay: execLimit,
          tokensPerMonth: tokenLimit,
          maxWorkflows,
          maxUsers,
        },
        allowedModels,
      })
      notifications.show({ title: t('admin.superadmin.tenantNew.created'), message: t('admin.superadmin.tenantNew.createdMsg', { name }), color: 'teal' })
      navigate('/admin/tenants')
    } catch (e) {
      setError(e instanceof Error ? e.message : t('admin.superadmin.tenantNew.createFailed'))
    } finally {
      setLoading(false)
    }
  }

  const planOptions = [
    { value: 'trial',        label: t('admin.superadmin.tenantNew.plans.trial') },
    { value: 'professional', label: t('admin.superadmin.tenantNew.plans.professional') },
    { value: 'enterprise',   label: t('admin.superadmin.tenantNew.plans.enterprise') },
    { value: 'internal',     label: t('admin.superadmin.tenantNew.plans.internal') },
  ]
  const planLabel = planOptions.find((option) => option.value === plan)?.label

  return (
    <Stack gap="lg" p="md">
      <PageHeader
        icon={IconBuildingSkyscraper}
        accent="blue"
        title={t('admin.superadmin.tenantNew.title')}
        subtitle={t('admin.superadmin.tenantNew.subtitle')}
        backTo="/admin/tenants"
        backLabel={t('common.back')}
        breadcrumbs={[
          { label: t('admin.layout.tenants'), to: '/admin/tenants' },
          { label: t('admin.superadmin.tenantNew.title') },
        ]}
      />

      {error && (
        <Alert color="red" icon={<IconAlertCircle size={16} />}>
          {error}
        </Alert>
      )}

      <Grid gap="lg">
        <Grid.Col span={{ base: 12, md: 8 }}>
          <Stack gap="lg">
            <SectionCard icon={IconId} accent="blue"
              title={t('admin.superadmin.tenantNew.identity')}
              description={t('admin.superadmin.tenantNew.identityHint')}>
              <Stack gap="sm">
                <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
                  <TextInput size="sm" label={t('admin.superadmin.tenantNew.form.name')} required value={name}
                    onChange={e => { setName(e.currentTarget.value); setTenantId(e.currentTarget.value.toLowerCase().trim().replace(/\s+/g, '_')) }} />
                  <TextInput size="sm" label={t('admin.superadmin.tenantNew.form.id')} required value={tenantId} onChange={e => setTenantId(e.currentTarget.value)}
                    description={t('admin.superadmin.tenantNew.form.idHint')} />
                </SimpleGrid>
                <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
                  <TextInput size="sm" label={t('admin.superadmin.tenantNew.form.email')} required value={email} onChange={e => setEmail(e.currentTarget.value)}
                    description={t('admin.superadmin.tenantNew.form.emailHint')} />
                  <Select size="sm" label={t('admin.superadmin.tenantNew.form.sector')} data={['Food Distribution', 'Legal', 'Healthcare', 'Fintech', 'Other']}
                    value={sector} onChange={setSector} />
                </SimpleGrid>
              </Stack>
            </SectionCard>

            <SectionCard icon={IconGauge} accent="teal"
              title={t('admin.superadmin.tenantNew.planLimits')}
              description={t('admin.superadmin.tenantNew.planLimitsHint')}>
              <Stack gap="sm">
                <Select size="sm" label={t('admin.superadmin.tenantNew.form.plan')} required data={planOptions} value={plan} onChange={setPlan} />
                <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
                  <NumberInput size="sm" label={t('admin.superadmin.tenantNew.form.execPerDay')}     value={execLimit}    onChange={v => setExecLimit(Number(v))}    min={1} thousandSeparator />
                  <NumberInput size="sm" label={t('admin.superadmin.tenantNew.form.tokensPerMonth')} value={tokenLimit}   onChange={v => setTokenLimit(Number(v))}   min={1000} step={100000} thousandSeparator />
                  <NumberInput size="sm" label={t('admin.superadmin.tenantNew.form.maxWorkflows')}   value={maxWorkflows} onChange={v => setMaxWorkflows(Number(v))} min={1} />
                  <NumberInput size="sm" label={t('admin.superadmin.tenantNew.form.maxUsers')}       value={maxUsers}     onChange={v => setMaxUsers(Number(v))}     min={1} />
                </SimpleGrid>
                <AllowedModelsInput value={allowedModels} onChange={setAllowedModels}
                  label={t('admin.superadmin.tenantNew.form.allowedModels')} />
              </Stack>
            </SectionCard>
          </Stack>
        </Grid.Col>

        <Grid.Col span={{ base: 12, md: 4 }}>
          <SectionCard icon={IconListCheck} accent="slate"
            title={t('admin.superadmin.tenantNew.summary')}
            description={t('admin.superadmin.tenantNew.summaryHint')}
            style={{ position: 'sticky', top: 80 }}>
            <Stack gap="xs">
              <SummaryRow label={t('admin.superadmin.tenantNew.summaryRows.name')}       value={name || '—'} />
              <SummaryRow label={t('admin.superadmin.tenantNew.summaryRows.id')}         value={tenantId || '—'} mono />
              <SummaryRow label={t('admin.superadmin.tenantNew.summaryRows.email')}      value={email || '—'} />
              <SummaryRow label={t('admin.superadmin.tenantNew.summaryRows.plan')}       value={planLabel || '—'} />
              <Divider my={4} />
              <SummaryRow label={t('admin.superadmin.tenantNew.summaryRows.exec')}       value={execLimit.toLocaleString(locale)} />
              <SummaryRow label={t('admin.superadmin.tenantNew.summaryRows.tokensMo')}   value={tokenLimit.toLocaleString(locale)} />
              <SummaryRow label={t('admin.superadmin.tenantNew.summaryRows.workflows')}  value={String(maxWorkflows)} />
              <SummaryRow label={t('admin.superadmin.tenantNew.summaryRows.users')}      value={String(maxUsers)} />
              <SummaryRow label={t('admin.superadmin.tenantNew.summaryRows.models')}
                value={allowedModels.length === 0 ? t('admin.superadmin.tenantDetail.allowedModelsAll') : String(allowedModels.length)} />
            </Stack>
            <Group justify="flex-end" gap="xs" mt="lg">
              <Button variant="default" onClick={() => navigate('/admin/tenants')}>{t('common.cancel')}</Button>
              <Button leftSection={<IconCheck size={16} />} onClick={handleCreate}
                disabled={!name || !email || !sector || !plan} loading={loading}>
                {t('admin.superadmin.tenantNew.createBtn')}
              </Button>
            </Group>
          </SectionCard>
        </Grid.Col>
      </Grid>
    </Stack>
  )
}

function SummaryRow({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <Group justify="space-between" wrap="nowrap" gap="sm">
      <Text size="xs" c="dimmed">{label}</Text>
      <Text size="xs" fw={600} ff={mono ? 'monospace' : undefined} truncate style={{ minWidth: 0 }}>{value}</Text>
    </Group>
  )
}
