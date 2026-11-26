/**
 * Tenant do caminho do WebSocket de voz (QA2 T4). O handshake passou a exigir que o tenant do caminho seja o do
 * token (outro → 403, salvo personificação); o SPA mandava '__default__' para todo usuário que não personificava,
 * e quem é de outro tenant tinha a sessão recusada.
 */
import { describe, it, expect } from 'vitest'
import { realtimeTenantId } from '../realtime-client'
import { tokenTenantId } from '../api'

/** JWT só com o payload legível — a assinatura não importa ao SPA, quem confere é o servidor. */
function jwt(payload: Record<string, unknown>): string {
  const b64url = (s: string) => btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  return `${b64url('{"alg":"HS512"}')}.${b64url(JSON.stringify(payload))}.assinatura`
}

describe('realtimeTenantId', () => {
  it('usa o tenant do token de quem é de outro tenant', () => {
    const token = jwt({ sub: 'u1', type: 'access', tenantId: 'tenant_qa2_b' })
    expect(realtimeTenantId(null, token)).toBe('tenant_qa2_b')
  })

  it('cai no tenant padrão quando o token não tem a claim', () => {
    expect(realtimeTenantId(null, jwt({ sub: 'u1', type: 'access' }))).toBe('__default__')
    expect(realtimeTenantId(null, null)).toBe('__default__')
    expect(realtimeTenantId(null, 'nao-e-um-jwt')).toBe('__default__')
  })

  it('a personificação vence o tenant do token', () => {
    const token = jwt({ sub: 'admin', type: 'access', tenantId: 'tenant_a' })
    expect(realtimeTenantId('tenant_b', token)).toBe('tenant_b')
  })
})

describe('tokenTenantId', () => {
  it('ignora claim vazia ou de outro tipo', () => {
    expect(tokenTenantId(jwt({ tenantId: '   ' }))).toBeNull()
    expect(tokenTenantId(jwt({ tenantId: 42 }))).toBeNull()
    expect(tokenTenantId(jwt({ tenantId: ' t1 ' }))).toBe('t1')
  })
})
