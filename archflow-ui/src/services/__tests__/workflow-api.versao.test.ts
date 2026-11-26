/**
 * RCHFLW-125: o PUT do fluxo leva a versão lida (updatedAt) no If-Match, e o 409 do servidor chega a quem chamou
 * com o status — é por ele que o editor abre o aviso de conflito. Sem a versão, o PUT é o de sempre.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { ApiError, workflowApi } from '../api'

const fetchMock = vi.fn()

describe('workflowApi.update — versão lida no If-Match', () => {
    beforeEach(() => {
        sessionStorage.clear()
        fetchMock.mockReset()
        vi.stubGlobal('fetch', fetchMock)
    })
    afterEach(() => vi.unstubAllGlobals())

    it('com a versão, manda If-Match com a entity tag entre aspas', async () => {
        fetchMock.mockResolvedValue(new Response('{"id":"wf-1"}', { status: 200 }))

        await workflowApi.update('wf-1', { steps: [] }, '2026-11-25T10:00:00Z')

        const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
        expect(url).toMatch(/\/workflows\/wf-1$/)
        expect(init.method).toBe('PUT')
        expect((init.headers as Record<string, string>)['If-Match']).toBe('"2026-11-25T10:00:00Z"')
        expect(JSON.parse(String(init.body))).toEqual({ steps: [] })
    })

    it('sem a versão, não manda If-Match (grava por cima, como antes)', async () => {
        fetchMock.mockResolvedValue(new Response('{"id":"wf-1"}', { status: 200 }))

        await workflowApi.update('wf-1', { steps: [] })

        const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
        expect((init.headers as Record<string, string>)['If-Match']).toBeUndefined()
    })

    it('409 do servidor vira ApiError com status 409', async () => {
        fetchMock.mockResolvedValue(new Response(
            '{"error":"Workflow was changed elsewhere since it was loaded","code":"WORKFLOW_CONFLICT"}',
            { status: 409, statusText: 'Conflict' }))

        const erro = await workflowApi.update('wf-1', {}, 'velha').catch((e: unknown) => e)

        expect(erro).toBeInstanceOf(ApiError)
        expect((erro as ApiError).status).toBe(409)
    })
})
