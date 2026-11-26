/**
 * Helpers do 2º bug hunt de admin/observabilidade: fatia de uso pequena (RCHFLW-195 item 7), contexto da
 * auditoria em texto (RCHFLW-164), download autenticado (item 9), recusa do convite repetido e plurais da
 * tela de usuários (item 17).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import i18next from 'i18next'
import ptBR from '../../i18n/locales/pt-BR.json'
import en from '../../i18n/locales/en.json'
import { formatShare } from '../usageFormat'
import { contextoEmTexto } from '../format'
import { mensagemDeUsuarioDoWorkspace } from '../workspaceUserError'

const authFetchMock = vi.hoisted(() => vi.fn())
vi.mock('../../services/api', () => ({ authFetch: authFetchMock }))

import { baixarArquivo } from '../downloadFile'

describe('formatShare', () => {
  it('positivo abaixo de 0,1% não vira 0%', () => {
    expect(formatShare(0.00285, 'pt-BR')).toBe('< 0,1%')
    expect(formatShare(0, 'pt-BR')).toBe('0%')
    expect(formatShare(83.3333, 'pt-BR')).toBe('83,3%')
    expect(formatShare(null, 'pt-BR')).toBe('')
  })
})

describe('contextoEmTexto', () => {
  it('chave=valor em ordem de chave', () => {
    expect(contextoEmTexto({ tenant: '__default__', executionId: 'exec-5bf47251' }))
      .toBe('executionId=exec-5bf47251; tenant=__default__')
    expect(contextoEmTexto({})).toBe('')
    expect(contextoEmTexto(null)).toBe('')
  })
})

describe('baixarArquivo', () => {
  beforeEach(() => {
    authFetchMock.mockReset()
    URL.createObjectURL = vi.fn(() => 'blob:x')
    URL.revokeObjectURL = vi.fn()
  })

  it('baixa pela sessão (authFetch) e entrega o blob com o nome pedido', async () => {
    authFetchMock.mockResolvedValue(new Response('id,timestamp\n', { status: 200 }))
    const cliques: string[] = []
    const original = HTMLAnchorElement.prototype.click
    HTMLAnchorElement.prototype.click = function () { cliques.push(this.download) }
    try {
      await baixarArquivo('/api/admin/observability/audit/export?action=LOGIN_SUCCESS', 'audit-log.csv')
    } finally {
      HTMLAnchorElement.prototype.click = original
    }
    expect(authFetchMock).toHaveBeenCalledWith('/api/admin/observability/audit/export?action=LOGIN_SUCCESS')
    expect(cliques).toEqual(['audit-log.csv'])
  })

  it('resposta de erro não vira arquivo: lança com a frase do corpo', async () => {
    authFetchMock.mockResolvedValue(new Response('{"error":"Authorization header is required"}', { status: 401 }))
    await expect(baixarArquivo('/api/x', 'x.csv')).rejects.toThrow('Authorization header is required')
    expect(URL.createObjectURL).not.toHaveBeenCalled()
  })
})

describe('usuários do workspace', () => {
  const t = (k: string) => k

  it('convite repetido tem frase própria, não "já existe um usuário"', () => {
    const erro = Object.assign(new Error('There is already an invitation'), { code: 'INVITATION_ALREADY_SENT' })
    expect(mensagemDeUsuarioDoWorkspace(erro, t)).toBe('workspaceUserErrors.INVITATION_ALREADY_SENT')
  })

  it.each([['pt-BR', ptBR], ['en', en]])('plurais certos dos contadores (%s)', async (lng, recursos) => {
    const i18n = i18next.createInstance()
    await i18n.init({ lng, resources: { [lng]: { translation: recursos } }, interpolation: { escapeValue: false } })
    const pt = lng === 'pt-BR'
    expect(i18n.t('admin.tenant.users.invitedCount', { count: 1 }))
      .toBe(pt ? '1 convite pendente' : '1 pending invite')
    expect(i18n.t('admin.tenant.users.invitedCount', { count: 3 }))
      .toBe(pt ? '3 convites pendentes' : '3 pending invites')
    expect(i18n.t('admin.tenant.users.membersWithRole', { count: 0 })).toBe(pt ? 'Nenhum membro' : 'No members')
    expect(i18n.t('admin.tenant.users.listHint', {
      people: i18n.t('admin.tenant.users.peopleCount', { count: 1 }),
      invited: i18n.t('admin.tenant.users.invitedCount', { count: 1 }),
    })).toBe(pt ? '1 pessoa · 1 convite pendente' : '1 person · 1 pending invite')
  })
})
