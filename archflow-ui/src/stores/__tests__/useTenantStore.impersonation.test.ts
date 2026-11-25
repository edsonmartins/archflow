/**
 * RCHFLW-96 — a personificação não pode ficar ativa sem aparecer. O cabeçalho X-Impersonate-Tenant sai do
 * sessionStorage (sobrevive ao F5); a faixa sai do store. O store tem de nascer coerente com o que vai no
 * cabeçalho: ou com a faixa, ou sem personificação.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'

const RIO = { id: 'tenant_rio', name: 'Rio Quality', plan: 'enterprise', status: 'active' }

async function storeAposRecarregar() {
  vi.resetModules()
  return (await import('../useTenantStore')).useTenantStore
}

beforeEach(() => {
  sessionStorage.clear()
})

describe('useTenantStore — personificação depois de recarregar a página', () => {
  it('superadmin que estava num tenant continua nele, com os dados para a faixa', async () => {
    sessionStorage.setItem('archflow_role', 'superadmin')
    sessionStorage.setItem('archflow_impersonate_tenant', RIO.id)
    sessionStorage.setItem('archflow_impersonate_tenant_info', JSON.stringify(RIO))

    const store = await storeAposRecarregar()

    expect(store.getState().impersonating).toEqual(RIO)
    expect(sessionStorage.getItem('archflow_impersonate_tenant')).toBe(RIO.id)
  })

  it('sem os dados do tenant (só o id), termina a personificação em vez de seguir invisível', async () => {
    sessionStorage.setItem('archflow_role', 'superadmin')
    sessionStorage.setItem('archflow_impersonate_tenant', RIO.id)

    const store = await storeAposRecarregar()

    expect(store.getState().impersonating).toBeNull()
    expect(sessionStorage.getItem('archflow_impersonate_tenant')).toBeNull()
  })

  it('dados de outro tenant não valem: termina', async () => {
    sessionStorage.setItem('archflow_role', 'superadmin')
    sessionStorage.setItem('archflow_impersonate_tenant', 'tenant_outro')
    sessionStorage.setItem('archflow_impersonate_tenant_info', JSON.stringify(RIO))

    const store = await storeAposRecarregar()

    expect(store.getState().impersonating).toBeNull()
    expect(sessionStorage.getItem('archflow_impersonate_tenant')).toBeNull()
  })

  it('quem não é superadmin não fica personificando', async () => {
    sessionStorage.setItem('archflow_role', 'editor')
    sessionStorage.setItem('archflow_impersonate_tenant', RIO.id)
    sessionStorage.setItem('archflow_impersonate_tenant_info', JSON.stringify(RIO))

    const store = await storeAposRecarregar()

    expect(store.getState().impersonating).toBeNull()
    expect(sessionStorage.getItem('archflow_impersonate_tenant')).toBeNull()
  })

  it('entrar e sair pelo store grava e apaga o cabeçalho e os dados juntos', async () => {
    const store = await storeAposRecarregar()

    store.getState().startImpersonation(RIO as never)
    expect(sessionStorage.getItem('archflow_impersonate_tenant')).toBe(RIO.id)
    expect(JSON.parse(sessionStorage.getItem('archflow_impersonate_tenant_info')!)).toEqual(RIO)

    store.getState().exitImpersonation()
    expect(sessionStorage.getItem('archflow_impersonate_tenant')).toBeNull()
    expect(sessionStorage.getItem('archflow_impersonate_tenant_info')).toBeNull()
  })
})
