import { Button, SimpleGrid, Stack, Alert, TextInput } from '@mantine/core'
import {
  IconPlus, IconAlertCircle, IconBuildingSkyscraper, IconCircleCheck, IconPlayerPlay, IconCoins,
  IconHourglass, IconSearch,
} from '@tabler/icons-react'
import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { tenantApi, type TenantStats } from '../../../services/admin-api'
import { useTenantStore } from '../../../stores/useTenantStore'
import { StatCard } from '../../../components/admin/StatCard'
import { PageHeader } from '../../../components/PageHeader'
import { SectionCard } from '../../../components/SectionCard'
import {
  VxTable, colunaAcoes, colunaApagada, colunaBarra, colunaDupla, colunaStatus, type VxColumn,
} from '../../../components/VTable'
import { PLAN_COLORS, TENANT_STATUS_COLORS } from './tenantColors'

type TenantRow = {
  id: string
  name: string
  plan: string
  status: string
  usage?: { tokensThisMonth: number }
  limits?: { tokensPerMonth: number }
  createdAt?: string
  usageCount?: { workflows: number; executions: number }
}

/** Percentual de uso de tokens do mês (0..100), ou null quando o tenant não tem limite/uso informado. */
function tokenPct(tenant: TenantRow): number | null {
  const used = tenant.usage?.tokensThisMonth
  const limit = tenant.limits?.tokensPerMonth
  if (used === undefined || !limit) return null
  return Math.min(Math.round((used / limit) * 100), 100)
}

const millions = (n: number) => `${(n / 1_000_000).toFixed(1)}M`

export default function TenantList() {
  const navigate = useNavigate()
  const { t } = useTranslation()
  const { startImpersonation, setTenants } = useTenantStore()
  const [tenants, setTenantRows] = useState<TenantRow[]>([])
  const [stats, setStats] = useState<TenantStats | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [query, setQuery] = useState('')

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)

    Promise.all([tenantApi.list(), tenantApi.stats()])
      .then(([tenantList, statsDto]) => {
        if (cancelled) return
        setTenants(tenantList)
        setTenantRows(tenantList as TenantRow[])
        setStats(statsDto)
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : t('admin.superadmin.tenants.loadFailed'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [setTenants])

  const handleImpersonate = (tenant: TenantRow) => {
    startImpersonation({
      id: tenant.id,
      name: tenant.name,
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      plan: tenant.plan as any,
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      status: tenant.status as any,
    })
    navigate('/admin/workspace')
  }

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    const rows = q
      ? tenants.filter((tenant) => tenant.name.toLowerCase().includes(q) || tenant.id.toLowerCase().includes(q))
      : tenants
    return rows.map((tenant) => ({ ...tenant, tokenPct: tokenPct(tenant) }))
  }, [tenants, query])

  // Variável local é `tenant`, não `t`: sombrear o `t` do useTranslation quebraria a renderização em silêncio.
  const colunas = useMemo((): VxColumn[] => [
    {
      ...colunaDupla(
        { field: 'name', title: t('admin.superadmin.tenants.columns.tenant'), width: 'auto', minWidth: 240 },
        (r) => String(r.name),
        (r) => String(r.id),
      ),
      espelho: (r) => <><span>{String(r.name)}</span> <span>{String(r.id)}</span></>,
    },
    colunaStatus(
      { field: 'status', title: t('admin.superadmin.tenants.columns.status'), width: 150,
        fieldFormat: (r) => t(`admin.superadmin.tenants.statuses.${r.status}`, { defaultValue: String(r.status) }) },
      (r) => TENANT_STATUS_COLORS[r.status as string] ?? 'gray',
    ),
    colunaStatus(
      { field: 'plan', title: t('admin.superadmin.tenants.columns.plan'), width: 160,
        fieldFormat: (r) => t(`admin.superadmin.tenants.plans.${r.plan}`, { defaultValue: String(r.plan) }) },
      (r) => PLAN_COLORS[r.plan as string] ?? 'gray',
    ),
    colunaApagada({ field: 'createdAt', title: t('admin.superadmin.tenants.columns.created'), width: 120,
      fieldFormat: (r) => (r.createdAt as string | undefined) ?? '—' }),
    colunaBarra(
      { field: 'tokenPct', title: t('admin.superadmin.tenants.columns.tokenUsage'), width: 200,
        fieldFormat: (r) => {
          const row = r as unknown as TenantRow & { tokenPct: number | null }
          if (row.tokenPct === null) return '—'
          return `${row.tokenPct}% · ${millions(row.usage!.tokensThisMonth)}`
        } },
      (r) => {
        const pct = r.tokenPct as number | null
        return pct === null ? undefined : pct >= 90 ? 'red' : pct >= 70 ? 'yellow' : 'blue'
      },
    ),
    colunaAcoes([
      { nome: 'enter', icone: 'external', rotulo: t('admin.superadmin.tenants.enter'), cor: 'blue' },
      { nome: 'details', icone: 'eye', rotulo: t('admin.superadmin.tenants.details') },
    ]),
  ], [t])

  return (
    <Stack gap="lg" p="md">
      <PageHeader
        icon={IconBuildingSkyscraper}
        accent="blue"
        title={t('admin.superadmin.tenants.title')}
        subtitle={t('admin.superadmin.tenants.subtitle')}
        actions={
          <Button leftSection={<IconPlus size={16} />} onClick={() => navigate('/admin/tenants/new')}>
            {t('admin.superadmin.tenants.newTenant')}
          </Button>
        }
      />

      {error && (
        <Alert color="red" icon={<IconAlertCircle size={16} />}>
          {error}
        </Alert>
      )}

      <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }} spacing="md">
        <StatCard label={t('admin.superadmin.tenants.stats.active')} value={stats?.totalActive ?? '—'}
          icon={IconCircleCheck} accent="green" hint={t('admin.superadmin.tenants.hints.active')} />
        <StatCard label={t('admin.superadmin.tenants.stats.executionsToday')} value={stats?.executionsToday ?? '—'}
          icon={IconPlayerPlay} accent="violet" hint={t('admin.superadmin.tenants.hints.executionsToday')} />
        <StatCard label={t('admin.superadmin.tenants.stats.tokensMonth')} value={stats ? millions(stats.tokensThisMonth) : '—'}
          icon={IconCoins} accent="teal" hint={t('admin.superadmin.tenants.hints.tokensMonth')} />
        <StatCard label={t('admin.superadmin.tenants.stats.trial')} value={stats?.totalTrial ?? '—'}
          color={stats?.totalTrial ? 'orange' : undefined}
          icon={IconHourglass} accent="amber" hint={t('admin.superadmin.tenants.hints.trial')} />
      </SimpleGrid>

      <SectionCard
        flush
        icon={IconBuildingSkyscraper}
        accent="blue"
        title={t('admin.superadmin.tenants.listTitle')}
        description={t('admin.superadmin.tenants.listHint')}
        actions={
          <TextInput
            size="xs"
            w={240}
            leftSection={<IconSearch size={14} />}
            placeholder={t('admin.superadmin.tenants.searchPlaceholder')}
            aria-label={t('admin.superadmin.tenants.searchPlaceholder')}
            value={query}
            onChange={(e) => setQuery(e.currentTarget.value)}
          />
        }
      >
        <VxTable
          legenda={t('admin.superadmin.tenants.listTitle')}
          records={filtered as unknown as Record<string, unknown>[]}
          columns={colunas}
          rowHeight={58}
          loading={loading && tenants.length === 0}
          onRowClick={(r) => navigate(`/admin/tenants/${r.id}`)}
          onIconClick={(nome, r) => {
            const tenant = r as unknown as TenantRow
            if (nome === 'enter') handleImpersonate(tenant)
            else navigate(`/admin/tenants/${tenant.id}`)
          }}
          emptyMessage={query ? t('admin.superadmin.tenants.noMatch') : t('admin.superadmin.tenants.empty')}
          emptyDescription={query ? undefined : t('admin.superadmin.tenants.emptyHint')}
          emptyIcon={<IconBuildingSkyscraper />}
          emptyAccent="blue"
          emptyAction={query ? undefined : (
            <Button variant="light" size="xs" leftSection={<IconPlus size={14} />}
                    onClick={() => navigate('/admin/tenants/new')}>
              {t('admin.superadmin.tenants.newTenant')}
            </Button>
          )}
          testid="tenant-list"
          rowTestId={(r) => `tenant-row-${r.id}`}
        />
      </SectionCard>
    </Stack>
  )
}
