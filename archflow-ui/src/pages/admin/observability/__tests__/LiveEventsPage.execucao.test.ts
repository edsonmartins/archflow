/**
 * Live events (Admin › Observabilidade), RCHFLW-50: a tela escolhe a execução e assina o canal dela
 * sem informar tenant — o servidor usa o tenant de quem está logado. Antes pedia "tenant" e "sessão"
 * livres, e nenhuma combinação que um usuário digitaria recebia os eventos de fluxo e de passo.
 *
 * <p>O stream é trocado por um dublê que guarda a configuração com que foi aberto; a tabela desenha
 * em canvas e vira uma lista simples.</p>
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { waitFor } from '@testing-library/dom'

const apiMock = vi.hoisted(() => ({ list: vi.fn() }))
const streamMock = vi.hoisted(() => ({
    configs: [] as Array<Record<string, unknown>>,
    statusListener: null as null | ((s: string) => void),
    opened: 0,
}))

vi.mock('react-i18next', () => ({
    useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('../../../../services/api', async (importOriginal) => ({
    ...(await importOriginal<Record<string, unknown>>()),
    executionApi: apiMock,
}))
vi.mock('../../../../services/event-stream', () => ({
    ArchflowEventStream: class {
        constructor(config: Record<string, unknown>) { streamMock.configs.push(config) }
        onStatus(l: (s: string) => void) { streamMock.statusListener = l; l('idle'); return () => {} }
        onEvent() { return () => {} }
        open() { streamMock.opened++ }
        close() {}
    },
}))
vi.mock('../../../../components/VTable', async () => {
    const { createElement } = await import('react')
    return {
        VxTable: (props: { records: unknown[] }) => createElement('div', { 'data-testid': 'live-events' },
            String(props.records.length)),
        colunaApagada: (c: unknown) => c,
        colunaStatus: (c: unknown) => c,
    }
})

import LiveEventsPage from '../LiveEventsPage'

const execucoes = [
    { id: 'exec-aaa111', workflowId: 'wf-1', workflowName: 'Triagem', status: 'RUNNING',
      startedAt: new Date().toISOString(), completedAt: null, duration: null, error: null },
    { id: 'exec-bbb222', workflowId: 'wf-2', workflowName: 'Cobrança', status: 'COMPLETED',
      startedAt: new Date().toISOString(), completedAt: null, duration: null, error: null },
]

let root: Root | null = null
let container: HTMLDivElement

const byTestId = (id: string) => document.body.querySelector(`[data-testid="${id}"]`) as HTMLElement | null

async function click(el: Element | null) {
    expect(el).not.toBeNull()
    await act(async () => {
        el!.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
        el!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    })
}

/** Digita num input controlado do React: o setter nativo e o evento 'input' que o React escuta. */
async function digitar(el: HTMLInputElement, valor: string) {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!
    await act(async () => {
        setter.call(el, valor)
        el.dispatchEvent(new Event('input', { bubbles: true }))
    })
}

async function render() {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    await act(async () => { root!.render(h(MantineProvider, null, h(LiveEventsPage))) })
}

async function escolher(label: string) {
    await click(byTestId('live-execution'))
    const option = await waitFor(() => {
        const o = Array.from(document.body.querySelectorAll('[role="option"]'))
            .find((el) => el.textContent?.includes(label))
        expect(o).toBeTruthy()
        return o!
    })
    await click(option)
}

beforeEach(() => {
    ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
    window.matchMedia ??= ((q: string) => ({
        matches: false, media: q, onchange: null,
        addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
        dispatchEvent: () => false,
    })) as any
    ;(globalThis as any).ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} }
    Element.prototype.scrollIntoView ??= () => {}
    vi.clearAllMocks()
    streamMock.configs = []
    streamMock.statusListener = null
    streamMock.opened = 0
    apiMock.list.mockResolvedValue(execucoes)
})

afterEach(async () => {
    await act(async () => { root?.unmount() })
    root = null
    container?.remove()
})

describe('LiveEventsPage — escolher a execução', () => {
    it('não pede tenant nem sessão livres; Conectar fica desligado até escolher a execução', async () => {
        await render()

        expect(byTestId('live-tenant')).toBeNull()
        expect(byTestId('live-session')).toBeNull()
        expect((byTestId('live-attach') as HTMLButtonElement).disabled).toBe(true)
        expect(apiMock.list).toHaveBeenCalledWith({ limit: 50 })
    })

    it('escolher a execução e conectar abre o stream dela, sem tenant (o servidor usa o do usuário)', async () => {
        await render()

        await escolher('exec-aaa111')
        await waitFor(() => expect((byTestId('live-attach') as HTMLButtonElement).disabled).toBe(false))
        await click(byTestId('live-attach'))

        expect(streamMock.configs).toHaveLength(1)
        expect(streamMock.configs[0].sessionId).toBe('exec-aaa111')
        expect(streamMock.configs[0].tenantId).toBeUndefined()
        expect(streamMock.opened).toBe(1)
    })

    it('a opção mostra o fluxo, o id e o status, para achar a execução certa', async () => {
        await render()
        await click(byTestId('live-execution'))

        const textos = await waitFor(() => {
            const t = Array.from(document.body.querySelectorAll('[role="option"]')).map((o) => o.textContent)
            expect(t).toHaveLength(2)
            return t
        })
        expect(textos).toEqual(['Triagem · exec-aaa111 · RUNNING', 'Cobrança · exec-bbb222 · COMPLETED'])
    })

    it('atualizar busca a lista de novo (a execução que acabou de começar aparece)', async () => {
        await render()
        apiMock.list.mockResolvedValue([
            { ...execucoes[0], id: 'exec-novo999', workflowName: 'Nova' }, ...execucoes])

        await click(byTestId('live-refresh'))
        await escolher('exec-novo999')
        await click(byTestId('live-attach'))

        expect(apiMock.list).toHaveBeenCalledTimes(2)
        expect(streamMock.configs[0].sessionId).toBe('exec-novo999')
    })

    it('a lista não carregou → diz por quê', async () => {
        apiMock.list.mockRejectedValue(new Error('500'))

        await render()

        await waitFor(() => expect(container.textContent).toContain('admin.observability.live.executionsFailed'))
    })

    it('execução já terminada → avisa que o stream é só ao vivo; uma que roda não tem aviso', async () => {
        await render()

        await escolher('exec-aaa111')
        expect(byTestId('live-finished')).toBeNull()

        await escolher('exec-bbb222')
        await waitFor(() => expect(byTestId('live-finished')?.textContent)
            .toContain('admin.observability.live.finished'))
    })

    it('canal livre: tenant e sessão digitados, para o canal administrativo ou uma conversa', async () => {
        await render()
        expect(byTestId('live-tenant')).toBeNull()

        await click(byTestId('live-free-channel'))
        await waitFor(() => expect(byTestId('live-tenant')).not.toBeNull())
        expect(byTestId('live-execution')).toBeNull()
        expect((byTestId('live-attach') as HTMLButtonElement).disabled).toBe(true)

        await digitar(byTestId('live-tenant') as HTMLInputElement, '__admin__')
        await digitar(byTestId('live-session') as HTMLInputElement, 'tenant-a')
        await waitFor(() => expect((byTestId('live-attach') as HTMLButtonElement).disabled).toBe(false))
        await click(byTestId('live-attach'))

        expect(streamMock.configs).toHaveLength(1)
        expect(streamMock.configs[0].tenantId).toBe('__admin__')
        expect(streamMock.configs[0].sessionId).toBe('tenant-a')
    })

    it('o servidor recusou o stream (403) → aviso, em vez de um feed vazio que parece esperar', async () => {
        await render()
        await escolher('exec-aaa111')
        await click(byTestId('live-attach'))

        await act(async () => { streamMock.statusListener!('error') })

        expect(byTestId('live-rejected')?.textContent).toContain('admin.observability.live.rejected')
    })
})
