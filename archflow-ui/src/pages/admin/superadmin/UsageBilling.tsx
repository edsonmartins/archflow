import { Stack, Select, Button, SimpleGrid, Alert } from '@mantine/core'
import {
  IconDownload, IconAlertCircle, IconReceipt, IconPlayerPlay, IconCoins, IconCurrencyReal,
  IconBuildingSkyscraper, IconCalendar,
} from '@tabler/icons-react'
import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { usageApi, type TenantUsageRow } from '../../../services/admin-api'
import { StatCard } from '../../../components/admin/StatCard'
import { PageHeader } from '../../../components/PageHeader'
import { SectionCard } from '../../../components/SectionCard'
import { VxTable, colunaBarra, colunaDupla, type VxColumn } from '../../../components/VTable'
import { compactTokens as millions, formatReais, formatShare, recentMonths } from '../../../lib/usageFormat'
import { baixarArquivo } from '../../../lib/downloadFile'

/**
 * Uso e faturamento (RCHFLW-75): o consumo medido de cada tenant no mês. Custo em reais pela tabela de
 * preços do servidor; tenant sem nenhum preço declarado mostra "sem preço", nunca R$ 0.
 */
export default function UsageBilling() {
  const { t, i18n } = useTranslation()
  const locale = i18n.resolvedLanguage ?? i18n.language
  const months = useMemo(() => recentMonths(), [])
  const [month, setMonth] = useState<string | null>(months[0])
  const [rows, setRows] = useState<TenantUsageRow[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [exporting, setExporting] = useState(false)

  // Download autenticado: o `<a href>` não levava o token e, com a autenticação ligada, o arquivo salvo
  // seria o JSON do 401 (RCHFLW-195 item 9).
  const exportar = () => {
    if (!month) return
    setExporting(true)
    baixarArquivo(usageApi.exportCsv(month), `usage-${month}.csv`)
      .catch((e) => setError(e instanceof Error ? e.message : t('admin.superadmin.usage.exportFailed')))
      .finally(() => setExporting(false))
  }

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
    const priced = rows.filter((row) => row.costReais != null)
    const cost = priced.length === 0 ? null : priced.reduce((sum, row) => sum + (row.costReais ?? 0), 0)
    const withoutPrice = rows.reduce((sum, row) => sum + row.executionsWithoutPrice, 0)
    return { executions, tokens, cost, withoutPrice }
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
    { field: 'costReais', title: t('admin.superadmin.usage.columns.cost'), width: 190,
      fieldFormat: (r) => {
        const cost = r.costReais == null ? t('usage.noPrice') : formatReais(Number(r.costReais), locale)
        const without = Number(r.executionsWithoutPrice)
        return r.costReais != null && without > 0
          ? `${cost} · ${t('usage.withoutPriceCount', { count: without })}` : cost
      },
      style: { fontWeight: 600 } },
    // O limite de tokens/mês do plano (vinha na API e no CSV, mas a tela não mostrava); sem cadastro, "—".
    { field: 'planLimit', title: t('admin.superadmin.usage.columns.planLimit'), width: 150,
      fieldFormat: (r) => (r.planLimit == null ? '—' : millions(Number(r.planLimit))) },
    colunaBarra({ field: 'percentOfTotal', title: t('admin.superadmin.usage.columns.percent'), width: 170,
      fieldFormat: (r) => formatShare(r.percentOfTotal as number | null, locale) }),
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
            <Select size="sm" w={150} data={months}
              leftSection={<IconCalendar size={14} />}
              aria-label={t('admin.superadmin.usage.month')}
              value={month} onChange={setMonth} allowDeselect={false} />
            <Button
              onClick={exportar}
              loading={exporting}
              disabled={!month}
              variant="light"
              leftSection={<IconDownload size={14} />}
              size="sm"
              data-testid="usage-export"
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
        <StatCard label={t('admin.superadmin.usage.stats.cost')}
          value={totals.executions === 0 ? formatReais(0, locale)
            : totals.cost == null ? t('usage.noPrice') : formatReais(totals.cost, locale)}
          icon={IconCurrencyReal} accent="amber"
          hint={totals.withoutPrice > 0
            ? t('usage.withoutPriceCount', { count: totals.withoutPrice })
            : t('admin.superadmin.usage.hints.cost')} />
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
