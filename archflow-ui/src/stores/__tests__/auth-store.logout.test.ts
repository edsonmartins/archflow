/**
 * RCHFLW-96 — a personificação é da sessão de quem saiu. Sem encerrá-la no logout, o próximo login nesta aba
 * seguiria mandando X-Impersonate-Tenant do tenant anterior (o sessionStorage sobrevive ao logout).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'

const authApiMock = vi.hoisted(() => ({ logout: vi.fn(async () => undefined), login: vi.fn(), me: vi.fn() }))
vi.mock('../../services/api', () => ({ authApi: authApiMock }))

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

describe('auth-store — resposta do /auth/me de uma sessão que já acabou', () => {
  // O ProtectedRoute busca o usuário depois de um F5 (RCHFLW-144). Se o Sair acontece com o /auth/me no ar, a
  // resposta chegava depois do logout e regravava o usuário e o papel (superadmin) numa aba já sem sessão.
  it('o /auth/me que volta depois do logout não regrava usuário nem papel', async () => {
    let responder: (v: unknown) => void = () => {}
    authApiMock.me.mockReturnValueOnce(new Promise((r) => { responder = r }))
    sessionStorage.setItem('archflow_token', 't-1')
    useAuthStore.setState({ token: 't-1', user: null })

    const carregando = useAuthStore.getState().loadUser()
    await useAuthStore.getState().logout()
    responder({ id: 'u-1', username: 'admin', email: 'a@x', roles: ['ADMIN'] })
    await carregando

    expect(useAuthStore.getState().user).toBeNull()
    expect(useAuthStore.getState().token).toBeNull()
    expect(useAuthStore.getState().loading).toBe(false)
    expect(useTenantStore.getState().currentRole).toBe('viewer')
    expect(sessionStorage.getItem('archflow_role')).toBeNull()
  })

  it('na mesma sessão, o usuário e o papel são gravados', async () => {
    authApiMock.me.mockResolvedValueOnce({ id: 'u-1', username: 'admin', email: 'a@x', roles: ['ADMIN'] })
    useAuthStore.setState({ token: 't-1', user: null })

    await useAuthStore.getState().loadUser()

    expect(useAuthStore.getState().user?.username).toBe('admin')
    expect(useTenantStore.getState().currentRole).toBe('superadmin')
  })
})
