/**
 * RCHFLW-96 — a personificação é da sessão de quem saiu. Sem encerrá-la no logout, o próximo login nesta aba
 * seguiria mandando X-Impersonate-Tenant do tenant anterior (o sessionStorage sobrevive ao logout).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../services/api', () => ({
  authApi: { logout: vi.fn(async () => undefined), login: vi.fn(), me: vi.fn() },
}))

import { useAuthStore } from '../auth-store'
import { useTenantStore } from '../useTenantStore'

beforeEach(() => sessionStorage.clear())

describe('auth-store — logout encerra a personificação', () => {
  it('apaga o tenant personificado do store e do sessionStorage', async () => {
    useTenantStore.getState().setRole('superadmin')
    useTenantStore.getState().startImpersonation({ id: 'tenant_rio', name: 'Rio' } as never)
    expect(sessionStorage.getItem('archflow_impersonate_tenant')).toBe('tenant_rio')

    await useAuthStore.getState().logout()

    expect(useTenantStore.getState().impersonating).toBeNull()
    expect(sessionStorage.getItem('archflow_impersonate_tenant')).toBeNull()
    expect(sessionStorage.getItem('archflow_impersonate_tenant_info')).toBeNull()
  })
})
