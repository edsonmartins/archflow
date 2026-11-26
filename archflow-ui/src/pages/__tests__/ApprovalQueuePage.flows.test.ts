/**
 * RCHFLW-129: a fila mostra o nome do fluxo, não "exec-249a1515 · ap", e o card "Fluxos" conta fluxos —
 * três pendências do mesmo fluxo contavam 3 "fluxos distintos".
 *
 * <p>A tabela desenha em canvas; o teste troca só o componente por uma tabela HTML que aplica as
 * mesmas colunas (o {@code fieldFormat} de cada uma) — as colunas em si são as reais.</p>
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'

const approvalApiMock = vi.hoisted(() => ({ listPending: vi.fn() }))

vi.mock('react-i18next', () => ({
    useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('../../services/approval-api', () => ({ approvalApi: approvalApiMock }))
vi.mock('../../components/VTable', async (importOriginal) => {
    const real = await importOriginal<Record<string, unknown>>()
    type Linha = Record<string, unknown>
    type Coluna = { field: string; fieldFormat?: (r: Linha) => string }
    return {
        ...real,
        VxTable: ({ records, columns, rowTestId }: {
            records: Linha[]; columns: Coluna[]; rowTestId?: (r: Linha) => string
        }) => h('table', null, h('tbody', null, records.map((r, i) =>
            h('tr', { key: i, 'data-testid': rowTestId?.(r) }, columns.map(c =>
                h('td', { key: c.field, 'data-col': c.field }, c.fieldFormat ? c.fieldFormat(r) : String(r[c.field]))))))),
    }
})

import ApprovalQueuePage from '../ApprovalQueuePage'

const pedido = (requestId: string, flowId: string, flowName: string | null) => ({
    requestId, tenantId: 'acme', flowId, stepId: 'ap', status: 'PENDING', title: null,
    description: 'Reembolso', proposal: { valor: 800 }, createdAt: '2026-11-25T12:00:00Z', expiresAt: null,
    flowName,
})

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
    vi.clearAllMocks()
    ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
    window.matchMedia ??= ((q: string) => ({
        matches: false, media: q, onchange: null,
        addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
        dispatchEvent: () => false,
    })) as any
    ;(globalThis as any).ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} }
})

afterEach(async () => {
    await act(async () => { root?.unmount() })
    root = null
    container?.remove()
})

async function render() {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    await act(async () => {
        root!.render(h(MantineProvider, null, h(MemoryRouter, null, h(ApprovalQueuePage))))
    })
}

/** O valor do card cujo rótulo é `label`. */
function statValue(label: string): string {
    const labelEl = Array.from(container.querySelectorAll('*'))
        .find((el) => el.children.length === 0 && el.textContent === label)
    expect(labelEl, `card ${label}`).toBeTruthy()
    let card: Element | null = labelEl!
    while (card && !card.textContent!.replace(label, '').match(/\d/)) card = card.parentElement
    return card!.textContent!.replace(label, '').replace('approvals.stats.flowsHint', '').trim()
}

describe('ApprovalQueuePage — fluxos na fila', () => {
    it('mostra o nome do fluxo, com a execução embaixo', async () => {
        approvalApiMock.listPending.mockResolvedValue([pedido('req-1', 'exec-249a1515', 'QA Aprovação')])
        await render()

        const cell = container.querySelector('[data-testid="approval-row-req-1"] [data-col="flowId"]')
        expect(cell?.textContent).toBe('QA Aprovação · exec-249a1515')
    })

    it('três pendências do mesmo fluxo contam um fluxo', async () => {
        approvalApiMock.listPending.mockResolvedValue([
            pedido('req-1', 'exec-1', 'QA Aprovação'),
            pedido('req-2', 'exec-2', 'QA Aprovação'),
            pedido('req-3', 'exec-3', 'QA Aprovação'),
        ])
        await render()

        expect(statValue('approvals.stats.flows')).toBe('1')
        expect(statValue('approvals.stats.pending')).toContain('3')
    })

    it('sem nome do servidor, cai no id da execução, como antes', async () => {
        approvalApiMock.listPending.mockResolvedValue([pedido('req-1', 'exec-1', null)])
        await render()

        const cell = container.querySelector('[data-testid="approval-row-req-1"] [data-col="flowId"]')
        expect(cell?.textContent).toBe('exec-1 · ap')
    })
})

/**
 * RCHFLW-188, item 14: a fila só carregava ao abrir. Pedidos vencidos seguiam com contagem regressiva e "Revisar",
 * e os cards discordavam do badge da barra lateral, que se atualiza a cada 30 s.
 */
describe('ApprovalQueuePage — a fila se atualiza sozinha', () => {
    afterEach(() => { vi.useRealTimers() })

    it('relê a lista no ritmo do badge: o pedido que venceu (e o servidor tirou) sai da tela', async () => {
        vi.useFakeTimers({ shouldAdvanceTime: true })
        approvalApiMock.listPending.mockResolvedValue([
            pedido('req-1', 'exec-1', 'QA Aprovação'), pedido('req-2', 'exec-2', 'QA Aprovação'),
        ])
        await render()
        expect(container.querySelector('[data-testid="approval-row-req-2"]')).not.toBeNull()

        approvalApiMock.listPending.mockResolvedValue([pedido('req-1', 'exec-1', 'QA Aprovação')])
        await act(async () => { await vi.advanceTimersByTimeAsync(30_000) })

        expect(container.querySelector('[data-testid="approval-row-req-2"]')).toBeNull()
        expect(statValue('approvals.stats.pending')).toContain('1')
    })

    it('entre uma leitura e outra, o "vence em" passa a "vencido" quando o prazo chega', async () => {
        vi.useFakeTimers({ shouldAdvanceTime: true })
        const expiresAt = new Date(Date.now() + 10_000).toISOString()
        approvalApiMock.listPending.mockResolvedValue([{ ...pedido('req-1', 'exec-1', 'QA'), expiresAt }])
        await render()
        const cell = () => container.querySelector('[data-testid="approval-row-req-1"] [data-col="expiresAt"]')
        expect(cell()?.textContent).toBe('approvals.time.inFuture')

        await act(async () => { await vi.advanceTimersByTimeAsync(16_000) })

        expect(cell()?.textContent).toBe('approvals.time.expired')
    })
})
