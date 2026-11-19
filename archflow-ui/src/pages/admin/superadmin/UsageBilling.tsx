import { Stack, Select, Button, SimpleGrid, Alert } from '@mantine/core'
import {
  IconDownload, IconAlertCircle, IconReceipt, IconPlayerPlay, IconCoins, IconCurrencyDollar,
  IconBuildingSkyscraper, IconCalendar,
} from '@tabler/icons-react'
import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { usageApi, type TenantUsageRow } from '../../../services/admin-api'
import { StatCard } from '../../../components/admin/StatCard'
import { PageHeader } from '../../../components/PageHeader'
import { SectionCard } from '../../../components/SectionCard'
import { VxTable, colunaBarra, colunaDupla, type VxColumn } from '../../../components/VTable'

/** Tokens compactos: 4.3M, 50K, 900 — `0.0M` escondia os tenants pequenos. */
const millions = (n: number) =>
  n >= 1_000_000 ? `${(n / 1_000_000).toFixed(1)}M` : n >= 1_000 ? `${Math.round(n / 1_000)}K` : String(n)

export default function UsageBilling() {
  const { t, i18n } = useTranslation()
  const locale = i18n.resolvedLanguage ?? i18n.language
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
        if (!cancelled) setError(e instanceof Error ? e.message : t('admin.superadmin.usage.loadFailed'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [month])

  const totals = useMemo(() => {
    const executions = rows.reduce((sum, row) => sum + row.executions, 0)
    const tokens = rows.reduce((sum, row) => sum + row.tokensInput + row.tokensOutput, 0)
    const cost = rows.reduce((sum, row) => sum + row.estimatedCost, 0)
    return { executions, tokens, cost }
  }, [rows])

  const colunas = useMemo((): VxColumn[] => [
    {
      ...colunaDupla(
        { field: 'tenantName', title: t('admin.superadmin.usage.columns.tenant'), width: 'auto', minWidth: 220 },
        (r) => String(r.tenantName),
        (r) => String(r.tenantId),
      ),
      espelho: (r) => <><span>{String(r.tenantName)}</span> <span>{String(r.tenantId)}</span></>,
    },
    { field: 'executions', title: t('admin.superadmin.usage.columns.executions'), width: 140,
      fieldFormat: (r) => Number(r.executions).toLocaleString(locale) },
    { field: 'tokensInput', title: t('admin.superadmin.usage.columns.tokensIn'), width: 130,
      fieldFormat: (r) => millions(Number(r.tokensInput)) },
    { field: 'tokensOutput', title: t('admin.superadmin.usage.columns.tokensOut'), width: 130,
      fieldFormat: (r) => millions(Number(r.tokensOutput)) },
    { field: 'estimatedCost', title: t('admin.superadmin.usage.columns.cost'), width: 130,
      fieldFormat: (r) => `$${Number(r.estimatedCost).toFixed(2)}`, style: { fontWeight: 600 } },
    colunaBarra({ field: 'percentOfTotal', title: t('admin.superadmin.usage.columns.percent'), width: 170 }),
  ], [t, locale])

  return (
    <Stack gap="lg" p="md">
      <PageHeader
        icon={IconReceipt}
        accent="slate"
        title={t('admin.superadmin.usage.title')}
        subtitle={t('admin.superadmin.usage.subtitle')}
        actions={
          <>
            <Select size="sm" w={150} data={['2026-04', '2026-03', '2026-02', '2026-01']}
              leftSection={<IconCalendar size={14} />}
              aria-label={t('admin.superadmin.usage.month')}
              value={month} onChange={setMonth} allowDeselect={false} />
            <Button
              component="a"
              href={usageApi.exportCsv(month ?? '')}
              variant="light"
              leftSection={<IconDownload size={14} />}
              size="sm"
            >{t('admin.superadmin.usage.exportCsv')}</Button>
          </>
        }
      />

      {error && (
        <Alert color="red" icon={<IconAlertCircle size={16} />}>
          {error}
        </Alert>
      )}

      <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="md">
        <StatCard label={t('admin.superadmin.usage.stats.executions')} value={totals.executions.toLocaleString(locale)}
          icon={IconPlayerPlay} accent="violet" hint={t('admin.superadmin.usage.hints.executions', { month })} />
        <StatCard label={t('admin.superadmin.usage.stats.tokens')} value={millions(totals.tokens)}
          icon={IconCoins} accent="teal" hint={t('admin.superadmin.usage.hints.tokens')} />
        <StatCard label={t('admin.superadmin.usage.stats.cost')} value={`$${totals.cost.toFixed(2)}`}
          icon={IconCurrencyDollar} accent="amber" hint={t('admin.superadmin.usage.hints.cost')} />
        <StatCard label={t('admin.superadmin.usage.stats.tenants')} value={String(rows.length)}
          icon={IconBuildingSkyscraper} accent="blue" hint={t('admin.superadmin.usage.hints.tenants')} />
      </SimpleGrid>

      <SectionCard
        flush
        icon={IconReceipt}
        accent="slate"
        title={t('admin.superadmin.usage.listTitle')}
        description={t('admin.superadmin.usage.listHint')}
      >
        <VxTable
          legenda={t('admin.superadmin.usage.listTitle')}
          records={rows as unknown as Record<string, unknown>[]}
          columns={colunas}
          rowHeight={58}
          loading={loading}
          emptyMessage={t('admin.superadmin.usage.empty')}
          emptyDescription={t('admin.superadmin.usage.emptyHint')}
          emptyIcon={<IconReceipt />}
          emptyAccent="slate"
          testid="usage-by-tenant"
          rowTestId={(r) => `usage-row-${r.tenantId}`}
        />
      </SectionCard>
    </Stack>
  )
}
