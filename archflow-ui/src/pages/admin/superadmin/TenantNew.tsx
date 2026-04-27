import {
  Title, TextInput, Select, NumberInput, Button, Paper, Stack, Group, Divider, Text, Alert,
} from '@mantine/core'
import { IconAlertCircle } from '@tabler/icons-react'
import { notifications } from '@mantine/notifications'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { tenantApi } from '../../../services/admin-api'

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
        plan: plan as any,
        limits: {
          executionsPerDay: execLimit,
          tokensPerMonth: tokenLimit,
          maxWorkflows,
          maxUsers,
        },
      })
      notifications.show({ title: t('admin.superadmin.tenantNew.created'), message: t('admin.superadmin.tenantNew.createdMsg', { name }), color: 'teal' })
      navigate('/admin/tenants')
    } catch (e) {
      setError(e instanceof Error ? e.message : t('admin.superadmin.tenantNew.createFailed'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <Stack gap="md">
      <Group justify="space-between">
        <Title order={3}>{t('admin.superadmin.tenantNew.title')}</Title>
        <Button variant="subtle" onClick={() => navigate('/admin/tenants')}>{t('admin.superadmin.tenantNew.back')}</Button>
      </Group>

      {error && (
        <Alert color="red" icon={<IconAlertCircle size={16} />}>
          {error}
        </Alert>
      )}

      <div style={{ display: 'flex', gap: 20 }}>
        <Stack gap="md" style={{ flex: 1 }}>
          <Paper withBorder p="lg" radius="lg">
            <Text fw={600} size="sm" mb="md">{t('admin.superadmin.tenantNew.identity')}</Text>
            <Stack gap="sm">
              <TextInput label={t('admin.superadmin.tenantNew.form.name')} required value={name}
                onChange={e => { setName(e.currentTarget.value); setTenantId(e.currentTarget.value.toLowerCase().trim().replace(/\s+/g, '_')) }} />
              <TextInput label={t('admin.superadmin.tenantNew.form.id')} required value={tenantId} onChange={e => setTenantId(e.currentTarget.value)}
                description={t('admin.superadmin.tenantNew.form.idHint')} styles={{ input: { fontFamily: 'var(--font-mono)', fontSize: 12 } }} />
              <TextInput label={t('admin.superadmin.tenantNew.form.email')} required value={email} onChange={e => setEmail(e.currentTarget.value)}
                description={t('admin.superadmin.tenantNew.form.emailHint')} />
              <Select label={t('admin.superadmin.tenantNew.form.sector')} data={['Food Distribution', 'Legal', 'Healthcare', 'Fintech', 'Other']}
                value={sector} onChange={setSector} />
            </Stack>
          </Paper>

          <Paper withBorder p="lg" radius="lg">
            <Text fw={600} size="sm" mb="md">{t('admin.superadmin.tenantNew.planLimits')}</Text>
            <Stack gap="sm">
              <Select label={t('admin.superadmin.tenantNew.form.plan')} required data={[
                { value: 'trial',        label: t('admin.superadmin.tenantNew.plans.trial') },
                { value: 'professional', label: t('admin.superadmin.tenantNew.plans.professional') },
                { value: 'enterprise',   label: t('admin.superadmin.tenantNew.plans.enterprise') },
                { value: 'internal',     label: t('admin.superadmin.tenantNew.plans.internal') },
              ]} value={plan} onChange={setPlan} />
              <SimpleGrid cols={2}>
                <NumberInput label={t('admin.superadmin.tenantNew.form.execPerDay')}   value={execLimit}    onChange={v => setExecLimit(Number(v))}    min={1} />
                <NumberInput label={t('admin.superadmin.tenantNew.form.tokensPerMonth')} value={tokenLimit}    onChange={v => setTokenLimit(Number(v))}   min={1000} step={100000} />
                <NumberInput label={t('admin.superadmin.tenantNew.form.maxWorkflows')} value={maxWorkflows} onChange={v => setMaxWorkflows(Number(v))} min={1} />
                <NumberInput label={t('admin.superadmin.tenantNew.form.maxUsers')}     value={maxUsers}     onChange={v => setMaxUsers(Number(v))}     min={1} />
              </SimpleGrid>
            </Stack>
          </Paper>
        </Stack>

        <Paper withBorder p="lg" radius="lg" w={280} style={{ flexShrink: 0, position: 'sticky', top: 80, alignSelf: 'flex-start' }}>
          <Text fw={600} size="sm" mb="md">{t('admin.superadmin.tenantNew.summary')}</Text>
          <Stack gap="xs">
            <SummaryRow label={t('admin.superadmin.tenantNew.summaryRows.name')}       value={name || '—'} />
            <SummaryRow label={t('admin.superadmin.tenantNew.summaryRows.id')}         value={tenantId || '—'} mono />
            <SummaryRow label={t('admin.superadmin.tenantNew.summaryRows.email')}      value={email || '—'} />
            <SummaryRow label={t('admin.superadmin.tenantNew.summaryRows.plan')}       value={plan || '—'} />
            <Divider my="xs" />
            <SummaryRow label={t('admin.superadmin.tenantNew.summaryRows.exec')}       value={String(execLimit)} />
            <SummaryRow label={t('admin.superadmin.tenantNew.summaryRows.tokensMo')}   value={tokenLimit.toLocaleString(locale)} />
            <SummaryRow label={t('admin.superadmin.tenantNew.summaryRows.workflows')}  value={String(maxWorkflows)} />
            <SummaryRow label={t('admin.superadmin.tenantNew.summaryRows.users')}      value={String(maxUsers)} />
          </Stack>
          <Button fullWidth mt="lg" onClick={handleCreate} disabled={!name || !email || !sector || !plan} loading={loading}>
            {t('admin.superadmin.tenantNew.createBtn')}
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
