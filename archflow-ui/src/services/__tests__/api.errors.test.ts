/**
 * RCHFLW-65/94: `ApiError.message` é a frase do corpo de erro (é o que alertas e toasts mostram); o corpo
 * cru continua em `body` e os campos estruturados em `details` — o YAML inválido traz `line`/`column`.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { ApiError, api } from '../api'
import { workflowYamlApi } from '../workflow-yaml-api'

function responder(status: number, body: string, statusText = '') {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(body, { status, statusText })))
}

async function erroDe(p: Promise<unknown>): Promise<ApiError> {
    try {
        await p
    } catch (e) {
        expect(e).toBeInstanceOf(ApiError)
        return e as ApiError
    }
    throw new Error('a chamada deveria ter falhado')
}

describe('request() — erro HTTP', () => {
    beforeEach(() => sessionStorage.clear())
    afterEach(() => vi.unstubAllGlobals())

    it('{"error": ...} do GlobalExceptionHandler vira a frase; o corpo cru fica em body', async () => {
        const corpo = '{"error":"Resolved LLM config has no provider; cannot build a ChatModel","status":409}'
        responder(409, corpo, 'Conflict')

        const e = await erroDe(api.post('/orchestration/run', { goal: 'x' }))

        expect(e.status).toBe(409)
        expect(e.message).toBe('Resolved LLM config has no provider; cannot build a ChatModel')
        expect(e.body).toBe(corpo)
    })

    it('YAML inválido: message limpa e line/column acessíveis em details', async () => {
        responder(400, JSON.stringify({
            status: 400,
            error: 'Invalid YAML at line 6, column 1: expected the node content, but found \'<stream end>\'',
            message: 'Invalid YAML at line 6, column 1: expected the node content, but found \'<stream end>\'',
            line: 6,
            column: 1,
        }), 'Bad Request')

        const e = await erroDe(workflowYamlApi.update('wf-1', 'steps: [ {'))

        expect(e.message).toBe('Invalid YAML at line 6, column 1: expected the node content, but found \'<stream end>\'')
        expect(e.message).not.toContain('{')
        expect(e.details).toMatchObject({ line: 6, column: 1 })
    })

    it('corpo vazio usa o statusText; sem ele, "HTTP <status>"', async () => {
        responder(502, '', 'Bad Gateway')
        expect((await erroDe(api.get('/x'))).message).toBe('Bad Gateway')

        responder(503, '')
        expect((await erroDe(api.get('/x'))).message).toBe('HTTP 503')
    })

    it('texto puro chega inteiro e details é null', async () => {
        responder(500, 'boom')
        const e = await erroDe(api.get('/x'))
        expect(e.message).toBe('boom')
        expect(e.details).toBeNull()
    })
})
