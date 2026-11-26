/**
 * RCHFLW-114: a configuração salva em Administração › Linktor fica só na memória do servidor, por tenant, e
 * some no restart. A tela dizia "Configuração salva — será aplicada na próxima requisição" e, depois de um
 * deploy, a integração voltava desligada sem aviso. Não há cifragem de segredo em repouso para guardar a
 * chave no banco, então a tela passou a dizer a verdade: vale até o restart, e o permanente são as
 * propriedades `archflow.linktor.*` do servidor, com os nomes de variável que o Spring de fato lê.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
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

/**
 * Os nomes conferidos contra o `@Value` do bean `linktorConfigController` por
 * `VariaveisDeAmbienteDoLinktorTest` (archflow-api). Repetidos aqui de propósito: se a tela trocar um nome,
 * este teste quebra em vez de a tela ensinar uma variável que o servidor não lê.
 */
const PROPRIEDADES = [
    ['archflow.linktor.enabled', 'ARCHFLOW_LINKTOR_ENABLED'],
    ['archflow.linktor.apiBaseUrl', 'ARCHFLOW_LINKTOR_APIBASEURL'],
    ['archflow.linktor.apiKey', 'ARCHFLOW_LINKTOR_APIKEY'],
    ['archflow.linktor.accessToken', 'ARCHFLOW_LINKTOR_ACCESSTOKEN'],
    ['archflow.linktor.mcpCommand', 'ARCHFLOW_LINKTOR_MCPCOMMAND'],
    ['archflow.linktor.timeoutSeconds', 'ARCHFLOW_LINKTOR_TIMEOUTSECONDS'],
] as const

const CONFIG = {
    enabled: true, apiBaseUrl: 'http://linktor:8081/api/v1', apiKey: 'abcd…wxyz', accessToken: '',
    mcpCommand: 'node /opt/linktor-mcp-server/dist/index.js', timeoutSeconds: 30,
}

function rede() {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
        if (!/\/admin\/linktor$/.test(url)) return new Response('', { status: 404 })
        return new Response(JSON.stringify(CONFIG), { status: 200 })
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
    await waitFor(() => ui.getByTestId('linktor-save'))
    return ui
}

beforeEach(() => {
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

describe('Linktor — a tela diz que a configuração vale até o restart', () => {
    it('mostra o aviso com cada propriedade e a variável de ambiente que o servidor lê', async () => {
        rede()
        const ui = await render()

        const aviso = within(ui.getByTestId('linktor-restart-warning'))
        aviso.getByText('linktor.config.restartTitle')
        aviso.getByText('linktor.config.restartBody')
        aviso.getByText('linktor.config.restartEnvHint')

        const linhas = within(ui.getByTestId('linktor-permanent-properties')).getAllByRole('row').slice(1)
        expect(linhas.map(l => Array.from(l.querySelectorAll('td')).map(td => td.textContent)))
            .toEqual(PROPRIEDADES.map(([p, v]) => [p, v]))
    })

    it('o Salvar notifica com o texto que fala do restart', async () => {
        rede()
        const ui = await render()

        await act(async () => { fireEvent.click(ui.getByTestId('linktor-save')) })

        await waitFor(() => expect(notificationsMock.show).toHaveBeenCalled())
        const toast = notificationsMock.show.mock.calls.at(-1)![0]
        expect(toast.color).toBe('green')
        expect(toast.message).toBe('linktor.config.saved')
    })

    it.each([['pt-BR', ptBR, /restart/, /réplica/], ['en', en, /restart/, /replica/]] as const)(
        '%s: o Salvar e o aviso dizem o que vale e até quando', (_, locale, restart, replica) => {
            const c = (locale as any).linktor.config
            expect(c.saved).toMatch(restart)
            // O mapa é por JVM: com duas réplicas, o Salvar só chega à que atendeu o PUT.
            expect(c.saved).toMatch(replica)
            expect(c.restartBody).toMatch(replica)
            expect(c.saved).toContain('ARCHFLOW_LINKTOR_')
            // O texto antigo prometia só "próxima requisição", sem dizer que o restart apaga.
            expect(c.saved).not.toMatch(/^Configura(ção|tion) saved? —/)
            expect(c.restartTitle).toMatch(restart)
            expect(c.restartBody).toMatch(/tenant/)
            // O nome "natural" com _ entre as palavras não é lido pelo Spring; a dica precisa avisar.
            expect(c.restartEnvHint).toContain('ARCHFLOW_LINKTOR_API_BASE_URL')
        })
})
