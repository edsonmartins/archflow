import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

import { ArchflowEventStream, type StreamStatus } from '../event-stream'

/**
 * O stream SSE só abre no tenant da própria requisição (RCHFLW-49). Do lado da UI isso exige três
 * coisas, conferidas aqui na requisição real (o fetch global): sem tenant informado, a URL não
 * inventa nenhum — o servidor resolve; a personificação ativa vai no header, senão o superadmin
 * abria o stream no próprio tenant; e a recusa (403) encerra, em vez de reconectar contra a mesma
 * recusa.
 */
describe('ArchflowEventStream — tenant do canal', () => {
    const fetchMock = vi.fn()

    beforeEach(() => {
        sessionStorage.clear()
        fetchMock.mockReset()
        vi.stubGlobal('fetch', fetchMock)
    })

    afterEach(() => {
        vi.unstubAllGlobals()
        vi.useRealTimers()
        sessionStorage.clear()
    })

    function abre(config: ConstructorParameters<typeof ArchflowEventStream>[0]) {
        const stream = new ArchflowEventStream(config)
        const status: StreamStatus[] = []
        stream.onStatus((s) => status.push(s))
        stream.open()
        return { stream, status }
    }

    function chamada(i = 0): { url: string; headers: Headers } {
        const [url, init] = fetchMock.mock.calls[i]
        return { url: String(url), headers: new Headers((init as RequestInit).headers) }
    }

    it('sem tenantId, assina /stream/{sessionId} — não inventa "default"', async () => {
        fetchMock.mockResolvedValue(new Response(null, { status: 403 }))
        sessionStorage.setItem('archflow_token', 'tok')

        const { stream } = abre({ sessionId: 'conv-1', baseUrl: '/api' })
        await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled())
        stream.close()

        const { url } = chamada()
        expect(new URL(url).pathname).toBe('/api/stream/conv-1')
        expect(url).not.toContain('default')
    })

    it('com tenantId, mantém a rota /stream/{tenantId}/{sessionId}', async () => {
        fetchMock.mockResolvedValue(new Response(null, { status: 403 }))

        const { stream } = abre({ tenantId: '__admin__', sessionId: 'tenant-a', baseUrl: '/api' })
        await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled())
        stream.close()

        expect(new URL(chamada().url).pathname).toBe('/api/stream/__admin__/tenant-a')
    })

    it('manda Authorization e o X-Impersonate-Tenant da personificação ativa', async () => {
        fetchMock.mockResolvedValue(new Response(null, { status: 403 }))
        sessionStorage.setItem('archflow_token', 'tok')
        sessionStorage.setItem('archflow_impersonate_tenant', 'tenant-b')

        const { stream } = abre({ sessionId: 'conv-1' })
        await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled())
        stream.close()

        const { headers } = chamada()
        expect(headers.get('Authorization')).toBe('Bearer tok')
        expect(headers.get('X-Impersonate-Tenant')).toBe('tenant-b')
        expect(headers.has('X-Tenant-Id')).toBe(false)
    })

    it('403 do servidor encerra com erro e não reconecta', async () => {
        vi.useFakeTimers()
        fetchMock.mockResolvedValue(new Response(null, { status: 403 }))

        const { stream, status } = abre({ tenantId: 'tenant-b', sessionId: 'conv-1' })
        await vi.waitFor(() => expect(status).toContain('error'))
        await vi.advanceTimersByTimeAsync(10_000)
        stream.close()

        expect(fetchMock).toHaveBeenCalledTimes(1)
        expect(status).not.toContain('reconnecting')
    })
})
