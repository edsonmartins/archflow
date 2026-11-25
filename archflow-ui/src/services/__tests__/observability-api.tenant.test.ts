/**
 * O servidor passou a usar o tenant da requisição quando a observabilidade chega sem `tenantId`
 * (RCHFLW-39); antes a ausência era "todos os tenants". As telas de observabilidade não escolhem
 * tenant, então é o cliente que pede `'all'` para o superadmin — a mesma regra da fila de aprovações
 * (`approvalTenantParam`) — e nada para os demais papéis. Quem confere o papel é o servidor.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

const get = vi.fn()
const post = vi.fn()
vi.mock('../api', () => ({
  api: {
    get: (path: string) => get(path),
    post: (path: string, body: unknown) => post(path, body),
  },
}))

import { observabilityApi } from '../observability-api'
import { useTenantStore } from '../../stores/useTenantStore'

beforeEach(() => {
  get.mockReset()
  post.mockReset()
  get.mockResolvedValue({})
  post.mockResolvedValue(undefined)
})

afterEach(() => {
  useTenantStore.setState({ currentRole: 'viewer', impersonating: null })
})

describe('observabilityApi — tenant da query', () => {
  it('superadmin sem tenant escolhido pede a visão de todos', async () => {
    useTenantStore.setState({ currentRole: 'superadmin', impersonating: null })

    await observabilityApi.getOverview()
    await observabilityApi.listTraces({ page: 0 })
    await observabilityApi.getTrace('t-1')
    await observabilityApi.listAudit()
    await observabilityApi.listRunningFlows()
    await observabilityApi.cancelRunningFlow('f-1')

    expect(get).toHaveBeenNthCalledWith(1, '/admin/observability/overview?tenantId=all')
    expect(get).toHaveBeenNthCalledWith(2, '/admin/observability/traces?tenantId=all&page=0')
    expect(get).toHaveBeenNthCalledWith(3, '/admin/observability/traces/t-1?tenantId=all')
    expect(get).toHaveBeenNthCalledWith(4, '/admin/observability/audit?tenantId=all')
    expect(get).toHaveBeenNthCalledWith(5, '/admin/observability/running?tenantId=all')
    expect(post).toHaveBeenCalledWith('/admin/observability/running/f-1/cancel?tenantId=all', {})
    expect(observabilityApi.auditExportUrl()).toBe('/api/admin/observability/audit/export?tenantId=all')
  })

  it('personificando, pede o tenant personificado', async () => {
    useTenantStore.setState({
      currentRole: 'superadmin',
      impersonating: { id: 'acme', name: 'Acme', plan: 'trial', status: 'active' },
    })

    await observabilityApi.getMetricSeries('latency_ms', { buckets: 12 })

    expect(get).toHaveBeenCalledWith('/admin/observability/metrics/series?metric=latency_ms&tenantId=acme&buckets=12')
  })

  it('outros papéis não mandam tenantId: o servidor usa o da requisição', async () => {
    for (const role of ['tenant_admin', 'editor', 'viewer'] as const) {
      useTenantStore.setState({ currentRole: role, impersonating: null })
      get.mockClear()

      await observabilityApi.getMetricsSnapshot()
      await observabilityApi.listTraces()

      expect(get).toHaveBeenNthCalledWith(1, '/admin/observability/metrics')
      expect(get).toHaveBeenNthCalledWith(2, '/admin/observability/traces')
    }
  })

  it('um tenant escolhido pela tela vence a regra do papel', async () => {
    useTenantStore.setState({ currentRole: 'superadmin', impersonating: null })

    await observabilityApi.listRunningFlows('globex')

    expect(get).toHaveBeenCalledWith('/admin/observability/running?tenantId=globex')
  })
})
