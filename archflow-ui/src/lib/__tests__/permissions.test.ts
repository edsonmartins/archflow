/**
 * RCHFLW-74: a matriz da UI é a mesma de Role.java (e do WorkflowPermissionInterceptor), lida do claim
 * `roles` do access token.
 */
import { describe, it, expect } from 'vitest'
import { rolesFromToken, rolesGrant } from '../permissions'
import { tokenComPapeis } from './papeisDeTeste'
import { adminUser, e2eAccessToken } from '../../../e2e/support/api'

describe('permissões por papel', () => {
  it('VIEWER lê; EXECUTOR lê e executa; DESIGNER edita e executa; ADMIN/SUPERADMIN tudo', () => {
    expect(rolesGrant(['VIEWER'], 'workflow:read')).toBe(true)
    expect(rolesGrant(['VIEWER'], 'execution:read')).toBe(true)
    expect(rolesGrant(['VIEWER'], 'workflow:execute')).toBe(false)
    expect(rolesGrant(['EXECUTOR'], 'workflow:execute')).toBe(true)
    expect(rolesGrant(['EXECUTOR'], 'workflow:update')).toBe(false)
    expect(rolesGrant(['DESIGNER'], 'workflow:delete')).toBe(true)
    expect(rolesGrant(['DESIGNER'], 'execution:manage')).toBe(false)
    expect(rolesGrant(['ROLE_ADMIN'], 'execution:manage')).toBe(true)
    expect(rolesGrant(['superadmin'], 'workflow:create')).toBe(true)
  })

  it('papel desconhecido ou nenhum papel não concede nada', () => {
    expect(rolesGrant([], 'workflow:read')).toBe(false)
    expect(rolesGrant(['EDITOR', 'TENANT_ADMIN'], 'workflow:update')).toBe(false)
  })

  it('lê os papéis do token; token ausente ou ilegível não tem papel', () => {
    expect(rolesFromToken(tokenComPapeis(['VIEWER', 'EXECUTOR']))).toEqual(['VIEWER', 'EXECUTOR'])
    expect(rolesFromToken(null)).toEqual([])
    expect(rolesFromToken('nao-e-jwt')).toEqual([])
  })

  // A suíte Playwright mockada (CI, `pnpm run test:e2e`) entra com o token de e2e/support/api.ts. Ele
  // era o opaco 'e2e-token': sem papéis, o admin mockado não via Novo/Salvar/Executar/Excluir e as
  // jornadas do editor e da lista esperavam por botões que não existiam.
  it('o token da sessão mockada do e2e carrega os papéis do usuário e concede as ações do admin', () => {
    const roles = rolesFromToken(e2eAccessToken(adminUser.roles))
    expect(roles).toEqual(adminUser.roles)
    for (const p of ['workflow:create', 'workflow:update', 'workflow:delete', 'workflow:execute'] as const) {
      expect(rolesGrant(roles, p)).toBe(true)
    }
    expect(rolesFromToken(e2eAccessToken(['VIEWER']))).toEqual(['VIEWER'])
  })
})
