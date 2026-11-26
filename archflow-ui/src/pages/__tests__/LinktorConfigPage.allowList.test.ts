/**
 * RCHFLW-69: a tela do Linktor sugeria `linktor-mcp` como comando MCP (valor padrão e placeholder), e esse
 * binário não está na allow-list padrão do backend — salvar o que a própria tela indicava dava erro. A
 * tela agora não sugere o binário bloqueado, a dica explica a allow-list e a variável, e o erro do save
 * aparece só como a frase do backend.
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

const ERRO_DA_ALLOW_LIST = "MCP command 'linktor-mcp' is not on the allow-list. Permitted: [npx, node]. "
    + "Configure via -Darchflow.mcp.allowed-commands or ARCHFLOW_MCP_ALLOWED_COMMANDS "
    + "(comma-separated; '*' disables the check)."

/** Config inicial como o servidor a devolve numa instalação padrão (`archflow.linktor.mcpCommand` vazio). */
const PADRAO_DO_SERVIDOR = {
    enabled: false, apiBaseUrl: 'http://localhost:8081/api/v1', apiKey: '', accessToken: '',
    mcpCommand: '', timeoutSeconds: 30,
}

type Resposta = { status: number; corpo: unknown }

function rede(getResp: Resposta, putResp?: Resposta) {
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
        if (!/\/admin\/linktor$/.test(url)) return new Response('', { status: 404 })
        const r = (init?.method ?? 'GET') === 'PUT' ? putResp : getResp
        if (!r) return new Response('', { status: 404 })
        return new Response(JSON.stringify(r.corpo), { status: r.status })
    }))
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
    await waitFor(() => ui.getByTestId('linktor-mcp-command'))
    return ui
}

beforeEach(() => {
    // As ações que acionam o servidor só aparecem para quem tem a permissão (QA2 RCHFLW-136/158).
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

describe('Linktor — comando MCP e a allow-list', () => {
    it('não sugere linktor-mcp nem no valor nem no placeholder', async () => {
        rede({ status: 200, corpo: PADRAO_DO_SERVIDOR })
        const ui = await render()

        const campo = ui.getByTestId('linktor-mcp-command') as HTMLInputElement
        expect(campo.value).toBe('')
        expect(campo.placeholder).toBe('linktor.config.mcpCommandPlaceholder')
    })

    it('sem a config do servidor (GET falhou), o padrão da tela também não é linktor-mcp', async () => {
        rede({ status: 500, corpo: { error: 'falhou' } })
        const ui = await render()

        expect((ui.getByTestId('linktor-mcp-command') as HTMLInputElement).value).toBe('')
    })

    it('o 400 da allow-list vira um toast com a frase do backend, sem o JSON', async () => {
        rede({ status: 200, corpo: PADRAO_DO_SERVIDOR },
            { status: 400, corpo: { error: ERRO_DA_ALLOW_LIST, status: 400 } })
        const ui = await render()

        await act(async () => {
            fireEvent.change(ui.getByTestId('linktor-mcp-command'), { target: { value: 'linktor-mcp' } })
        })
        await act(async () => { fireEvent.click(ui.getByTestId('linktor-save')) })

        await waitFor(() => expect(notificationsMock.show).toHaveBeenCalled())
        const toast = notificationsMock.show.mock.calls.at(-1)![0]
        expect(toast.color).toBe('red')
        expect(toast.message).toBe(ERRO_DA_ALLOW_LIST)
    })

    it.each([['pt-BR', ptBR], ['en', en]] as const)('%s: a dica explica a allow-list e a variável', (_, locale) => {
        const c = (locale as any).linktor.config
        // O placeholder sugere um launcher que a allow-list padrão aceita.
        expect(c.mcpCommandPlaceholder.split(' ')[0]).toBe('node')
        expect(c.mcpCommandHint).toMatch(/allow-list/)
        expect(c.installHint).toContain('<code>ARCHFLOW_MCP_ALLOWED_COMMANDS</code>')
        expect(c.installHint).toMatch(/allow-list/)
    })
})
