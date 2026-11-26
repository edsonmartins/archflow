/**
 * Live events — Pausar (RCHFLW-195 item 2): com a tela pausada nenhum evento novo entra no feed. O
 * ouvinte do stream lia o `paused` do render em que a conexão abriu (sempre false), então "Pausar" só
 * trocava o botão. Base: RCHFLW-50: a tela escolhe a execução e assina o canal dela
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
    eventListener: null as null | ((e: unknown) => void),
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
        onEvent(l: (e: unknown) => void) { streamMock.eventListener = l; return () => {} }
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

let seq = 0
async function chega() {
    seq++
    await act(async () => {
        streamMock.eventListener!({
            envelope: { domain: 'system', type: 'flow_started', id: `evt-${seq}`, timestamp: new Date().toISOString() },
            data: {},
        })
    })
}

const noFeed = () => Number(byTestId('live-events')?.textContent ?? '0')

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
    streamMock.eventListener = null
    apiMock.list.mockResolvedValue(execucoes)
})

afterEach(async () => {
    await act(async () => { root?.unmount() })
    root = null
    container?.remove()
})

describe('LiveEventsPage — Pausar', () => {
    it('pausado, o feed não recebe eventos novos; retomado, volta a receber', async () => {
        container = document.createElement('div')
        document.body.appendChild(container)
        root = createRoot(container)
        await act(async () => { root!.render(h(MantineProvider, null, h(LiveEventsPage))) })

        await click(byTestId('live-execution'))
        const option = await waitFor(() => {
            const o = Array.from(document.body.querySelectorAll('[role="option"]'))
                .find((el) => el.textContent?.includes('exec-aaa111'))
            expect(o).toBeTruthy()
            return o!
        })
        await click(option)
        await waitFor(() => expect((byTestId('live-attach') as HTMLButtonElement).disabled).toBe(false))
        await click(byTestId('live-attach'))
        expect(streamMock.eventListener).not.toBeNull()

        await chega()
        await waitFor(() => expect(noFeed()).toBe(1))

        await click(byTestId('live-toggle'))
        await chega()
        await chega()
        expect(noFeed()).toBe(1)

        await click(byTestId('live-toggle'))
        await chega()
        await waitFor(() => expect(noFeed()).toBe(2))
    })
})
