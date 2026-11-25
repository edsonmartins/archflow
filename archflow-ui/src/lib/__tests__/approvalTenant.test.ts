import { describe, it, expect, vi, beforeEach } from 'vitest'

const get = vi.fn()
vi.mock('../../services/api', () => ({ api: { get: (path: string) => get(path) } }))

import { approvalTenantParam } from '../approvalTenant'
import { approvalApi } from '../../services/approval-api'

describe('approvalTenantParam — quem decide o tenant da fila de aprovações', () => {
  it('personificando, manda o id do tenant personificado', () => {
    expect(approvalTenantParam({ id: 'acme' }, 'superadmin')).toBe('acme')
    expect(approvalTenantParam({ id: 'acme' }, 'editor')).toBe('acme')
  })

  it('superadmin sem personificar vê todos os tenants', () => {
    expect(approvalTenantParam(null, 'superadmin')).toBe('all')
  })

  it('os demais papéis não mandam tenant — o servidor usa o da requisição, nunca "default"', () => {
    for (const role of ['tenant_admin', 'editor', 'viewer'] as const) {
      expect(approvalTenantParam(null, role)).toBeUndefined()
    }
  })
})

describe('approvalApi — sem tenant, a query não leva tenantId', () => {
  beforeEach(() => {
    get.mockReset()
    get.mockResolvedValue([])
  })

  it('listPending/pendingCount sem tenant omitem o parâmetro', async () => {
    await approvalApi.listPending(undefined)
    await approvalApi.pendingCount(undefined)
    expect(get).toHaveBeenNthCalledWith(1, '/approvals/pending')
    expect(get).toHaveBeenNthCalledWith(2, '/approvals/pending/count')
  })

  it('com tenant, o parâmetro vai codificado', async () => {
    await approvalApi.listPending('a b')
    await approvalApi.pendingCount('all')
    expect(get).toHaveBeenNthCalledWith(1, '/approvals/pending?tenantId=a%20b')
    expect(get).toHaveBeenNthCalledWith(2, '/approvals/pending/count?tenantId=all')
  })
})
