import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

import { runAgUiWorkflow, type AgUiEvent } from '../agui-client'

/**
 * O Run do editor precisa chegar ao servidor com a mesma identidade de tenant que o resto da UI:
 * quem personifica um tenant e roda pelo editor tem de gravar a execução (e a aprovação dela) nesse
 * tenant, como a lista de fluxos já fazia (RCHFLW-13). Por isso o teste olha os headers da
 * requisição real — o fetch global —, não um mock do cliente.
 */
describe('runAgUiWorkflow — headers da requisição', () => {
    const fetchMock = vi.fn()

    beforeEach(() => {
        sessionStorage.clear()
        fetchMock.mockReset()
        fetchMock.mockResolvedValue(new Response(null, { status: 500, statusText: 'boom' }))
        vi.stubGlobal('fetch', fetchMock)
    })

    afterEach(() => {
        vi.unstubAllGlobals()
        sessionStorage.clear()
    })

    async function headersDoRun(): Promise<Headers> {
        const eventos: AgUiEvent[] = []
        runAgUiWorkflow('wf-1', { state: {} }, (e) => eventos.push(e))
        await vi.waitFor(() => expect(eventos.map((e) => e.type)).toContain('RUN_ERROR'))
        const chamada = fetchMock.mock.calls.find(([url]) => String(url).startsWith('/ag-ui/workflows/'))
        expect(chamada).toBeDefined()
        return new Headers((chamada![1] as RequestInit).headers)
    }

    it('manda o X-Impersonate-Tenant da personificação ativa', async () => {
        sessionStorage.setItem('archflow_token', 'tok')
        sessionStorage.setItem('archflow_impersonate_tenant', 'tenant-b')

        const headers = await headersDoRun()

        expect(headers.get('X-Impersonate-Tenant')).toBe('tenant-b')
        expect(headers.get('Authorization')).toBe('Bearer tok')
        expect(headers.get('Content-Type')).toBe('application/json')
    })

    it('sem personificação, não inventa tenant nenhum — o servidor resolve', async () => {
        sessionStorage.setItem('archflow_token', 'tok')

        const headers = await headersDoRun()

        expect(headers.has('X-Impersonate-Tenant')).toBe(false)
        expect(headers.has('X-Tenant-Id')).toBe(false)
    })
})
