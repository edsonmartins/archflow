/**
 * Os clientes de admin levam o `code` da recusa junto com a frase (RCHFLW-85): é por ele que a tela de
 * usuários e a página de aceite mostram a frase no idioma da UI, em vez da frase em inglês do backend.
 */
import { describe, it, expect, afterEach, vi } from 'vitest'
import { AdminApiError, acceptInvitation, userApi } from '../admin-api'

afterEach(() => {
  vi.unstubAllGlobals()
})

function respondWith(status: number, body: string) {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(body, { status, statusText: 'Conflict' })))
}

describe('admin-api — erros com código', () => {
  it('a recusa do workspace vira AdminApiError com code, status e a frase (não o JSON)', async () => {
    respondWith(409, JSON.stringify({ error: 'CONFLICT', code: 'USER_EXISTS',
      message: 'A user with the e-mail ana@acme.com already exists', status: 409 }))

    const erro = await userApi.invite({ email: 'ana@acme.com', role: 'VIEWER' }).catch((e: unknown) => e)

    expect(erro).toBeInstanceOf(AdminApiError)
    expect((erro as AdminApiError).code).toBe('USER_EXISTS')
    expect((erro as AdminApiError).status).toBe(409)
    expect((erro as AdminApiError).message).toBe('A user with the e-mail ana@acme.com already exists')
  })

  it('o aceite público também leva o code', async () => {
    respondWith(410, JSON.stringify({ error: 'EXPIRED', code: 'INVITATION_EXPIRED', message: 'expired', status: 410 }))

    const erro = await acceptInvitation('u.s', 'senha-nova-123').catch((e: unknown) => e)

    expect((erro as AdminApiError).code).toBe('INVITATION_EXPIRED')
  })

  it('corpo sem code (ou que não é JSON): code nulo, só a frase', async () => {
    respondWith(500, 'falha no proxy')

    const erro = await userApi.list().catch((e: unknown) => e)

    expect((erro as AdminApiError).code).toBeNull()
    expect((erro as AdminApiError).message).toBe('falha no proxy')
  })
})
