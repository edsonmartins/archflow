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

/**
 * RCHFLW-54: o Parar só abortava o fetch. Durante um passo longo o servidor não escreve nada, então
 * não notava a desconexão: o passo ia até o fim, o seguinte rodava e a execução ficava COMPLETED.
 * Agora o Parar também cancela no servidor pelo id que o RUN_STARTED trouxe.
 */
describe('runAgUiWorkflow — Parar cancela no servidor', () => {
    const encoder = new TextEncoder()
    let cancelStatus = 200

    /** Stream SSE que entrega os frames e fica aberto (o passo longo), até o abort. */
    function stream(frames: object[], signal: AbortSignal | null | undefined): Response {
        const body = new ReadableStream<Uint8Array>({
            start(controller) {
                for (const f of frames) controller.enqueue(encoder.encode(`data: ${JSON.stringify(f)}\n\n`))
                signal?.addEventListener('abort', () => controller.close())
            },
        })
        return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
    }

    function rede(frames: object[]) {
        const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
            if (String(url).startsWith('/ag-ui/workflows/')) return stream(frames, init?.signal)
            if (/\/executions\/[^/]+\/cancel$/.test(String(url))) {
                return new Response(JSON.stringify(cancelStatus === 200 ? { id: 'exec-1', status: 'CANCELLED' }
                    : { error: 'not found' }), { status: cancelStatus })
            }
            return new Response('', { status: 404 })
        })
        vi.stubGlobal('fetch', fetchMock)
        return fetchMock
    }

    function cancelamentos(fetchMock: ReturnType<typeof vi.fn>) {
        return fetchMock.mock.calls.filter(([url]) => /\/cancel$/.test(String(url)))
    }

    beforeEach(() => {
        sessionStorage.clear()
        sessionStorage.setItem('archflow_token', 'tok')
        cancelStatus = 200
    })

    afterEach(() => {
        vi.unstubAllGlobals()
        sessionStorage.clear()
    })

    it('depois do RUN_STARTED, Parar faz POST /api/executions/{id}/cancel com o id da execução', async () => {
        const fetchMock = rede([{ type: 'RUN_STARTED', runId: 'exec-1' }, { type: 'STEP_STARTED', stepId: 'a' }])
        const eventos: AgUiEvent[] = []
        const parar = runAgUiWorkflow('wf-1', {}, (e) => eventos.push(e))
        await vi.waitFor(() => expect(eventos.map((e) => e.type)).toContain('STEP_STARTED'))

        await expect(parar()).resolves.toBe('stopped')

        const chamadas = cancelamentos(fetchMock)
        expect(chamadas).toHaveLength(1)
        expect(String(chamadas[0][0])).toBe('/api/executions/exec-1/cancel')
        expect((chamadas[0][1] as RequestInit).method).toBe('POST')
    })

    it('servidor recusa o cancelamento → failed (a tela avisa que pode continuar rodando)', async () => {
        cancelStatus = 404
        rede([{ type: 'RUN_STARTED', runId: 'exec-1' }])
        const eventos: AgUiEvent[] = []
        const parar = runAgUiWorkflow('wf-1', {}, (e) => eventos.push(e))
        await vi.waitFor(() => expect(eventos.map((e) => e.type)).toContain('RUN_STARTED'))

        await expect(parar()).resolves.toBe('failed')
    })

    // DESIGNER/EXECUTOR executam (workflow:execute) mas não cancelam pela API (execution:manage, só
    // ADMIN): o 403 não é "pode continuar rodando" — o stream já fechou e o servidor cancela pela
    // desconexão.
    it('servidor recusa por permissão (403) → byDisconnect, não failed', async () => {
        cancelStatus = 403
        rede([{ type: 'RUN_STARTED', runId: 'exec-1' }])
        const eventos: AgUiEvent[] = []
        const parar = runAgUiWorkflow('wf-1', {}, (e) => eventos.push(e))
        await vi.waitFor(() => expect(eventos.map((e) => e.type)).toContain('RUN_STARTED'))

        await expect(parar()).resolves.toBe('byDisconnect')
    })

    it('run já terminado não é cancelado', async () => {
        const fetchMock = rede([
            { type: 'RUN_STARTED', runId: 'exec-1' },
            { type: 'RUN_FINISHED', runId: 'exec-1', result: { status: 'COMPLETED' } },
        ])
        const eventos: AgUiEvent[] = []
        const parar = runAgUiWorkflow('wf-1', {}, (e) => eventos.push(e))
        await vi.waitFor(() => expect(eventos.map((e) => e.type)).toContain('RUN_FINISHED'))

        await expect(parar()).resolves.toBe('stopped')
        expect(cancelamentos(fetchMock)).toHaveLength(0)
    })

    it('antes do RUN_STARTED não há id: só fecha o stream (o servidor cancela pela desconexão)', async () => {
        const fetchMock = rede([])
        const parar = runAgUiWorkflow('wf-1', {}, () => {})

        await expect(parar()).resolves.toBe('stopped')
        expect(cancelamentos(fetchMock)).toHaveLength(0)
    })
})
