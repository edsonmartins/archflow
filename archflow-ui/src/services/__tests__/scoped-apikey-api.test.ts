/**
 * RCHFLW-83/86: o cliente de `/api/apikeys` não escolhe o dono (sem `X-User-Id`), revoga pelo `keyId` que o
 * backend procura e devolve a frase do erro, não o corpo JSON (`HTTP 400: {...}`, como o alerta mostrava).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { scopedApiKeyApi } from '../scoped-apikey-api'

type Chamada = { url: string; init: RequestInit }
let chamadas: Chamada[]

function responder(status: number, body: string) {
    chamadas = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
        chamadas.push({ url, init })
        return new Response(status === 204 ? null : body, { status })
    }))
}

const cabecalhos = (c: Chamada) => new Headers(c.init.headers as Record<string, string>)

describe('scopedApiKeyApi', () => {
    beforeEach(() => {
        sessionStorage.clear()
        localStorage.clear()
        sessionStorage.setItem('archflow_token', 'jwt-do-usuario')
        // O que a camada antiga lia para montar o X-User-Id.
        localStorage.setItem('archflow_user', JSON.stringify({ userId: 'outro-usuario' }))
    })
    afterEach(() => vi.unstubAllGlobals())

    it('cria com o token do usuário e sem X-User-Id', async () => {
        responder(200, JSON.stringify({ keyId: 'ak_1', apiKey: 'ak_1.s' }))

        await scopedApiKeyApi.create({ name: 'CI', scopes: ['WORKFLOW_EXECUTE'] })

        const [c] = chamadas
        expect(c.url).toBe('/api/apikeys')
        expect(cabecalhos(c).get('Authorization')).toBe('Bearer jwt-do-usuario')
        expect(cabecalhos(c).has('X-User-Id')).toBe(false)
        expect(JSON.parse(String(c.init.body))).toEqual({ name: 'CI', scopes: ['WORKFLOW_EXECUTE'] })
    })

    it('lê o vocabulário de escopos do backend', async () => {
        responder(200, JSON.stringify([{ name: 'WORKFLOW_READ', permission: 'workflow:read', description: 'x' }]))

        const scopes = await scopedApiKeyApi.scopes()

        expect(chamadas[0].url).toBe('/api/apikeys/scopes')
        expect(scopes[0].name).toBe('WORKFLOW_READ')
    })

    it('revoga pelo keyId e aceita o 204 sem corpo', async () => {
        responder(204, '')

        await expect(scopedApiKeyApi.remove('ak_1')).resolves.toBeUndefined()

        expect(chamadas[0].url).toBe('/api/apikeys/ak_1')
        expect(chamadas[0].init.method).toBe('DELETE')
    })

    it('erro do backend vira a frase, sem o JSON', async () => {
        responder(400, '{"error":"Unknown scope \'workflow.read\'. Valid scopes: WORKFLOW_READ","status":400}')

        await expect(scopedApiKeyApi.create({ name: 'CI', scopes: ['workflow.read'] }))
            .rejects.toThrow(/^Unknown scope 'workflow\.read'\. Valid scopes: WORKFLOW_READ$/)
    })
})
