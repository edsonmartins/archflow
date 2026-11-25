/**
 * RCHFLW-65: Labs › Dynamic Workflow e a conversa do Linktor punham o corpo HTTP cru no alerta e nos toasts
 * (`{"error":"Linktor apiBaseUrl is not configured"}`). Os testes passam pelo `request()` real e pelos
 * serviços reais — só a rede é falsa —, porque o defeito estava justamente entre o corpo e a tela.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'

const notificationsMock = vi.hoisted(() => ({ show: vi.fn() }))

vi.mock('react-i18next', () => ({
    useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('@mantine/notifications', () => ({ notifications: notificationsMock }))
// A tabela de trilha desenha em canvas; não é o que está em teste.
vi.mock('../../components/VTable', () => ({
    VxTable: () => null,
    colunaApagada: (c: unknown) => c,
    colunaStatus: (c: unknown) => c,
}))

import DynamicWorkflowPage from '../DynamicWorkflowPage'
import LinktorConversationPage from '../LinktorConversationPage'

type Rota = { metodo?: string; caminho: RegExp; status: number; corpo: unknown }

/** fetch falso por rota; corpo string vai como está, objeto vai como JSON. */
function rede(rotas: Rota[]) {
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
        const metodo = init?.method ?? 'GET'
        const rota = rotas.find(r => (r.metodo ?? 'GET') === metodo && r.caminho.test(url))
        if (!rota) return new Response('', { status: 404, statusText: 'Not Found' })
        const corpo = typeof rota.corpo === 'string' ? rota.corpo : JSON.stringify(rota.corpo)
        return new Response(corpo, { status: rota.status })
    }))
}

let root: Root | null = null
let container: HTMLDivElement

async function render(path: string, pattern: string, element: Parameters<typeof h>[0]) {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    await act(async () => {
        root!.render(
            h(MantineProvider, null,
                h(MemoryRouter, { initialEntries: [path] },
                    h(Routes, null, h(Route, { path: pattern, element: h(element) })))),
        )
    })
    return within(container)
}

beforeEach(() => {
    ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
    window.matchMedia ??= ((q: string) => ({
        matches: false, media: q, onchange: null,
        addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
        dispatchEvent: () => false,
    })) as any
    ;(globalThis as any).ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} }
    // O Textarea autosize do Mantine escuta `document.fonts`, que o jsdom não tem.
    if (!(document as any).fonts) {
        Object.defineProperty(document, 'fonts', {
            value: { addEventListener() {}, removeEventListener() {} }, configurable: true,
        })
    }
    sessionStorage.clear()
    notificationsMock.show.mockClear()
})

afterEach(async () => {
    await act(async () => { root?.unmount() })
    root = null
    container?.remove()
    vi.unstubAllGlobals()
})

describe('Dynamic Workflow — erro do backend', () => {
    it('o alerta mostra a frase, não o JSON', async () => {
        rede([{ metodo: 'POST', caminho: /\/orchestration\/run$/, status: 409,
            corpo: { error: 'Resolved LLM config has no provider; cannot build a ChatModel', status: 409 } }])
        const ui = await render('/playground/orchestration', '/playground/orchestration', DynamicWorkflowPage)

        await act(async () => {
            fireEvent.change(ui.getByTestId('dw-goal'), { target: { value: 'resumir os tickets' } })
        })
        await act(async () => { fireEvent.click(ui.getByTestId('dw-run')) })

        const alerta = await waitFor(() => ui.getByRole('alert'))
        expect(alerta.textContent).toContain('Resolved LLM config has no provider; cannot build a ChatModel')
        expect(alerta.textContent).not.toContain('{"error"')
        expect(alerta.textContent).not.toContain('"status"')
    })
})

describe('Conversa do Linktor — erro do backend', () => {
    const CONVERSA = /\/admin\/linktor\/inbox\/conversations\/c-1$/
    const MENSAGENS = /\/admin\/linktor\/inbox\/conversations\/c-1\/messages/
    const USUARIOS = /\/admin\/linktor\/inbox\/users$/

    it('o alerta do carregamento mostra a frase, não o JSON', async () => {
        rede([
            { caminho: CONVERSA, status: 503, corpo: { error: 'Linktor apiBaseUrl is not configured' } },
            { caminho: MENSAGENS, status: 503, corpo: { error: 'Linktor apiBaseUrl is not configured' } },
            // A página também pede os operadores (RCHFLW-112); aqui o que está em teste é o alerta da conversa.
            { caminho: USUARIOS, status: 200, corpo: [] },
        ])
        const ui = await render('/admin/linktor/conversation/c-1', '/admin/linktor/conversation/:id',
            LinktorConversationPage)

        const alerta = await waitFor(() => ui.getByRole('alert'))
        expect(alerta.textContent).toContain('Linktor apiBaseUrl is not configured')
        expect(alerta.textContent).not.toContain('{"error"')
    })

    it('o toast de Enviar mostra a frase, não o JSON', async () => {
        rede([
            { caminho: CONVERSA, status: 200, corpo: { id: 'c-1', status: 'open', channelType: 'whatsapp' } },
            { caminho: MENSAGENS, status: 200, corpo: [] },
            { metodo: 'POST', caminho: MENSAGENS, status: 502,
                corpo: { error: 'Connection refused: linktor.local/10.0.0.5:443' } },
        ])
        const ui = await render('/admin/linktor/conversation/c-1', '/admin/linktor/conversation/:id',
            LinktorConversationPage)

        const caixa = await waitFor(() => ui.getByTestId('lk-reply-input'))
        await act(async () => { fireEvent.change(caixa, { target: { value: 'oi' } }) })
        await act(async () => { fireEvent.click(ui.getByTestId('lk-reply-send')) })

        await waitFor(() => expect(notificationsMock.show).toHaveBeenCalled())
        const toast = notificationsMock.show.mock.calls[0][0] as { color: string; message: string }
        expect(toast.color).toBe('red')
        expect(toast.message).toBe('Connection refused: linktor.local/10.0.0.5:443')
    })
})
