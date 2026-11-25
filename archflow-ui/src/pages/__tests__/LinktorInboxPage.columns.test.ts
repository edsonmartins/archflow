/**
 * RCHFLW-112: as colunas do Inbox do Linktor com a resposta real do Linktor (snake_case).
 *
 * <p>Na homologação o nome "Maria Souza" estava no JSON e a tela mostrava "—" em contato, última
 * mensagem, canal e atualizada: as colunas liam camelCase que o Linktor nunca mandou.</p>
 *
 * <p>A tabela desenha em canvas; o teste troca só o componente por uma tabela HTML que aplica as
 * mesmas colunas (o {@code fieldFormat} de cada uma) — as colunas em si são as reais.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { waitFor, within } from '@testing-library/dom'

vi.mock('react-i18next', () => ({
    useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
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

import LinktorInboxPage from '../LinktorInboxPage'

/** Duas conversas como `GET /conversations` do Linktor as lista (o backend tira só o envelope). */
const CONVERSAS = [
    {
        id: 'conv-1', status: 'open', unread_count: 2, priority: 'normal',
        created_at: '2026-11-25T08:00:00Z', updated_at: '2026-11-25T09:00:00Z',
        contact: { id: 'c-1', name: 'Maria Souza' },
        channel: { id: 'ch-1', type: 'webchat', name: 'Site', connection_status: 'connected' },
        last_message: { id: 'm-9', content: 'Meu pedido não chegou', content_type: 'text',
                        sender_type: 'contact', created_at: '2026-11-25T09:00:00Z' },
    },
    {
        id: 'conv-2', status: 'open', unread_count: 0, assigned_user_id: 'u-1',
        created_at: '2026-11-24T08:00:00Z', updated_at: '2026-11-24T18:00:00Z',
        contact: { id: 'c-2', name: 'João Lima' },
        channel: { id: 'ch-2', type: 'whatsapp', name: 'WhatsApp Loja' },
    },
    {
        id: 'conv-3', status: 'resolved', unread_count: 0,
        created_at: '2026-11-20T08:00:00Z', updated_at: '2026-11-20T18:00:00Z',
    },
]

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
    ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
    window.matchMedia ??= ((q: string) => ({
        matches: false, media: q, onchange: null,
        addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
        dispatchEvent: () => false,
    })) as any
    ;(globalThis as any).ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} }
    sessionStorage.clear()
    vi.stubGlobal('fetch', vi.fn(async (url: string) => (url.includes('/admin/linktor/inbox/conversations')
        ? new Response(JSON.stringify(CONVERSAS), { status: 200 })
        : new Response('[]', { status: 200 }))))
})

afterEach(async () => {
    await act(async () => { root?.unmount() })
    root = null
    container?.remove()
    vi.unstubAllGlobals()
})

async function render() {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    await act(async () => {
        root!.render(h(MantineProvider, null, h(MemoryRouter, null, h(LinktorInboxPage))))
    })
    const ui = within(container)
    await waitFor(() => ui.getByTestId('lk-conv-conv-1'))
    return ui
}

const celula = (linha: HTMLElement, coluna: string) =>
    linha.querySelector(`[data-col="${coluna}"]`)?.textContent

describe('Inbox do Linktor — colunas', () => {
    it('mostra contato, última mensagem, canal, não lidas e atualização da resposta real', async () => {
        const ui = await render()
        const linha = ui.getByTestId('lk-conv-conv-1')

        expect(celula(linha, 'contato')).toContain('Maria Souza')
        expect(celula(linha, 'ultimaMensagem')).toBe('Meu pedido não chegou')
        expect(celula(linha, 'canalNome')).toBe('Site')
        expect(celula(linha, 'naoLidas')).toBe('2')
        expect(celula(linha, 'atualizadaEm')).toBe(new Date('2026-11-25T09:00:00Z').toLocaleString('pt-BR'))
        expect(celula(linha, 'status')).toBe('linktor.status.open')
    })

    it('sem a última mensagem (o Linktor não mandou), a coluna mostra "—" — não inventa', async () => {
        const ui = await render()

        expect(celula(ui.getByTestId('lk-conv-conv-2'), 'ultimaMensagem')).toBe('—')
        expect(celula(ui.getByTestId('lk-conv-conv-3'), 'contato')).toContain('—')
    })

    it('"atribuídas" conta as conversas em andamento com usuário atribuído (não é status no Linktor)', async () => {
        const ui = await render()

        // O número do StatCard é o texto que vem logo depois do grupo do rótulo.
        const valor = (rotulo: string) =>
            ui.getByText(rotulo).closest('.af-stat')?.children[1]?.textContent
        expect(valor('linktor.inbox.stats.open')).toBe('1')
        expect(valor('linktor.inbox.stats.assigned')).toBe('1')
        expect(valor('linktor.inbox.stats.resolved')).toBe('1')
    })
})
