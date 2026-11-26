/**
 * RCHFLW-193 (revisão): o PUT do Linktor passou a entender chave e token em branco como "remover". Se o GET
 * falha, o formulário fica nos padrões — `apiKey: ''`, `accessToken: ''` — e um Salvar nesse estado apagava
 * os segredos guardados (e trocava o resto pelos padrões). Sem a configuração atual a tela não oferece o
 * Salvar e diz por quê.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { entrarComo } from '../../lib/__tests__/papeisDeTeste'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'

const notificationsMock = vi.hoisted(() => ({ show: vi.fn() }))

vi.mock('react-i18next', () => ({
    useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('@mantine/notifications', () => ({ notifications: notificationsMock }))

import LinktorConfigPage from '../LinktorConfigPage'
import ptBR from '../../i18n/locales/pt-BR.json'
import en from '../../i18n/locales/en.json'

const CONFIG = {
    enabled: true, apiBaseUrl: 'http://linktor:8081/api/v1', apiKey: 'abcd…wxyz', accessToken: '',
    mcpCommand: 'node /opt/linktor-mcp-server/dist/index.js', timeoutSeconds: 30,
}

let fetchMock: ReturnType<typeof vi.fn>

/** O GET responde `statusDoGet`; o PUT, se chegar, devolve a mesma configuração. */
function rede(statusDoGet: number) {
    fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
        if (!/\/admin\/linktor$/.test(url)) return new Response('', { status: 404 })
        if ((init?.method ?? 'GET') === 'GET' && statusDoGet !== 200) {
            return new Response(JSON.stringify({ message: 'indisponível' }), { status: statusDoGet })
        }
        return new Response(JSON.stringify(CONFIG), { status: 200 })
    })
    vi.stubGlobal('fetch', fetchMock)
}

function puts() {
    return fetchMock.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === 'PUT')
}

let root: Root | null = null
let container: HTMLDivElement

async function render() {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    await act(async () => {
        root!.render(h(MantineProvider, null, h(MemoryRouter, null, h(LinktorConfigPage))))
    })
    const ui = within(container)
    await waitFor(() => ui.getByTestId('linktor-save'))
    return ui
}

beforeEach(() => {
    entrarComo('ADMIN')
    ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
    window.matchMedia ??= ((q: string) => ({
        matches: false, media: q, onchange: null,
        addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
        dispatchEvent: () => false,
    })) as any
    ;(globalThis as any).ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} }
    sessionStorage.clear()
    notificationsMock.show.mockClear()
})

afterEach(async () => {
    await act(async () => { root?.unmount() })
    root = null
    container?.remove()
    vi.unstubAllGlobals()
})

describe('Linktor — sem a configuração atual não há Salvar', () => {
    it('GET falhou: Salvar desabilitado, aviso na tela e nenhum PUT com os segredos em branco', async () => {
        rede(503)
        const ui = await render()

        const salvar = ui.getByTestId('linktor-save') as HTMLButtonElement
        expect(salvar.disabled).toBe(true)
        within(ui.getByTestId('linktor-load-failed')).getByText('linktor.config.loadFailedBody')

        await act(async () => { fireEvent.click(salvar) })
        expect(puts()).toHaveLength(0)
    })

    it('GET respondeu: o Salvar segue habilitado e sem o aviso', async () => {
        rede(200)
        const ui = await render()

        const salvar = ui.getByTestId('linktor-save') as HTMLButtonElement
        expect(salvar.disabled).toBe(false)
        expect(ui.queryByTestId('linktor-load-failed')).toBeNull()

        await act(async () => { fireEvent.click(salvar) })
        await waitFor(() => expect(puts()).toHaveLength(1))
    })

    it.each([['pt-BR', ptBR], ['en', en]] as const)('%s: o aviso existe e cita a chave', (_, locale) => {
        const c = (locale as any).linktor.config
        expect(c.loadFailedTitle).toBeTruthy()
        expect(c.loadFailedBody).toMatch(/API/)
    })
})
