/**
 * RCHFLW-112: a conversa do Linktor — transferência para um operador e o histórico.
 *
 * <ul>
 *   <li>O Linktor atribui por `user_id`, um UUID que o operador não sabe. A tela lista os usuários do
 *   Linktor (nome e e-mail à vista, id enviado). Uma chave de API não pode listá-los (o Linktor exige
 *   admin/owner): a tela diz isso e mantém o campo de id. Não há "vazio devolve à fila".</li>
 *   <li>O autor de cada mensagem vem do `sender_type` (antes toda mensagem aparecia como "user") e a
 *   ordem é cronológica (o Linktor devolve a mais nova primeiro).</li>
 * </ul>
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { entrarComo } from '../../lib/__tests__/papeisDeTeste'
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

import LinktorConversationPage from '../LinktorConversationPage'
import ptBR from '../../i18n/locales/pt-BR.json'
import en from '../../i18n/locales/en.json'

/** O detalhe como o backend o repassa: a conversa do Linktor, já sem o envelope {success, data}. */
const CONVERSA = {
    id: 'conv-1', status: 'open', unread_count: 1, updated_at: '2026-11-25T09:01:00Z',
    contact: { id: 'c-1', name: 'Maria Souza' },
    channel: { id: 'ch-1', type: 'webchat', name: 'Site' },
}

/** A mais nova primeiro, como `GET /conversations/:id/messages` do Linktor. */
const MENSAGENS = [
    { id: 'm3', sender_type: 'bot', content: 'Posso ajudar em algo mais?', created_at: '2026-11-25T09:02:00Z' },
    { id: 'm2', sender_type: 'user', content: 'Já verifico seu pedido', created_at: '2026-11-25T09:01:00Z' },
    { id: 'm1', sender_type: 'contact', content: 'Meu pedido não chegou', created_at: '2026-11-25T09:00:00Z' },
]

const USUARIOS = [
    { id: 'u-ana', name: 'Ana Operadora', email: 'ana@loja.com', role: 'agent', status: 'active' },
    { id: 'u-bruno', name: 'Bruno Supervisor', email: 'bruno@loja.com', role: 'supervisor', status: 'active' },
]

type Resposta = { status: number; corpo: unknown }
const atribuicoes: unknown[] = []

function rede(usuarios: Resposta, conversa: Record<string, unknown> = CONVERSA) {
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
        const metodo = init?.method ?? 'GET'
        const json = (r: Resposta) => new Response(JSON.stringify(r.corpo), { status: r.status })
        if (url.endsWith('/admin/linktor/inbox/users')) return json(usuarios)
        if (metodo === 'POST' && url.endsWith('/conversations/conv-1/assign')) {
            atribuicoes.push(JSON.parse(String(init?.body)))
            return json({ status: 200, corpo: { ...conversa, assigned_user_id: 'u-ana' } })
        }
        if (url.includes('/conversations/conv-1/messages')) return json({ status: 200, corpo: MENSAGENS })
        if (url.endsWith('/conversations/conv-1')) return json({ status: 200, corpo: conversa })
        return new Response('', { status: 404 })
    }))
}

let root: Root | null = null
let container: HTMLDivElement

async function render() {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    await act(async () => {
        root!.render(h(MantineProvider, null,
            h(MemoryRouter, { initialEntries: ['/admin/linktor/inbox/conv-1'] },
                h(Routes, null, h(Route, { path: '/admin/linktor/inbox/:id', element: h(LinktorConversationPage) })))))
    })
    const ui = within(container)
    await waitFor(() => expect(ui.getAllByTestId('lk-message')).toHaveLength(3))
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
    // O Textarea autosize do Mantine escuta `document.fonts`, que o jsdom não tem.
    if (!(document as any).fonts) {
        Object.defineProperty(document, 'fonts', {
            value: { addEventListener() {}, removeEventListener() {} }, configurable: true,
        })
    }
    sessionStorage.clear()
    notificationsMock.show.mockClear()
    atribuicoes.length = 0
})

afterEach(async () => {
    await act(async () => { root?.unmount() })
    root = null
    container?.remove()
    vi.unstubAllGlobals()
})

describe('Linktor — transferir para um operador', () => {
    it('escolhe o operador pelo nome e e-mail, e o id é o que vai para o backend', async () => {
        rede({ status: 200, corpo: USUARIOS })
        const ui = await render()

        const campo = await waitFor(() => {
            const el = ui.getByTestId('lk-assignee')
            expect(el.tagName).toBe('SELECT')
            return el as HTMLSelectElement
        })
        const rotulos = Array.from(campo.options).map(o => o.textContent)
        expect(rotulos).toEqual(['linktor.conversation.assigneeChoose',
            'Ana Operadora · ana@loja.com', 'Bruno Supervisor · bruno@loja.com'])
        // Sem operador escolhido não há o que transferir: o Linktor não tem "devolver à fila".
        expect((ui.getByTestId('lk-assign') as HTMLButtonElement).disabled).toBe(true)

        await act(async () => { fireEvent.change(campo, { target: { value: 'u-ana' } }) })
        await act(async () => { fireEvent.click(ui.getByTestId('lk-assign')) })

        await waitFor(() => expect(atribuicoes).toEqual([{ userId: 'u-ana' }]))
        await waitFor(() => expect(notificationsMock.show).toHaveBeenCalled())
        expect(notificationsMock.show.mock.calls.at(-1)![0].color).toBe('green')
    })

    it('chave de API sem permissão para listar usuários: a tela diz isso e mantém o campo de id', async () => {
        rede({ status: 502, corpo: {
            error: 'Linktor /users?page_size=250 returned HTTP 403 — insufficient permissions', linktorStatus: 403 } })
        const ui = await render()

        await waitFor(() => ui.getByTestId('lk-users-forbidden'))
        expect(ui.getByTestId('lk-users-forbidden').textContent).toBe('linktor.conversation.usersForbidden')
        const campo = ui.getByTestId('lk-assignee') as HTMLInputElement
        expect(campo.tagName).toBe('INPUT')

        await act(async () => { fireEvent.change(campo, { target: { value: 'u-123' } }) })
        await act(async () => { fireEvent.click(ui.getByTestId('lk-assign')) })

        await waitFor(() => expect(atribuicoes).toEqual([{ userId: 'u-123' }]))
    })

    it('usuário inativo ou suspenso aparece com o status e não pode ser escolhido', async () => {
        rede({ status: 200, corpo: [...USUARIOS,
            { id: 'u-caio', name: 'Caio', email: 'caio@loja.com', role: 'agent', status: 'suspended' }] })
        const ui = await render()

        const campo = await waitFor(() => {
            const el = ui.getByTestId('lk-assignee')
            expect(el.tagName).toBe('SELECT')
            return el as HTMLSelectElement
        })
        const caio = Array.from(campo.options).find(o => o.value === 'u-caio')!
        expect(caio.textContent).toBe('Caio · caio@loja.com (linktor.conversation.userStatus.suspended)')
        expect(caio.disabled).toBe(true)
        expect(Array.from(campo.options).find(o => o.value === 'u-ana')!.disabled).toBe(false)
    })

    it('credenciais recusadas (401) não viram "falta papel admin"', async () => {
        rede({ status: 502, corpo: {
            error: 'Linktor /users?page_size=250 returned HTTP 401 — invalid api key', linktorStatus: 401 } })
        const ui = await render()

        await waitFor(() => ui.getByTestId('lk-users-unauthorized'))
        expect(ui.getByTestId('lk-users-unauthorized').textContent).toBe('linktor.conversation.usersUnauthorized')
        expect(ui.queryByTestId('lk-users-forbidden')).toBeNull()
        expect((ui.getByTestId('lk-assignee') as HTMLInputElement).tagName).toBe('INPUT')
    })

    it.each([['pt-BR', ptBR], ['en', en]] as const)('%s: sem a promessa de "vazio devolve à fila"', (_, locale) => {
        const c = (locale as any).linktor.conversation
        expect(c.assigneePlaceholder).not.toMatch(/fila|queue|vazio|blank/i)
        expect(c.usersForbidden).toMatch(/admin/)
        expect(c.usersUnauthorized).toMatch(/401/)
        expect(c.usersUnauthorized).not.toMatch(/admin/)
        expect(Object.keys(c.userStatus).sort()).toEqual(['active', 'inactive', 'suspended'])
        expect(Object.keys(c.sender).sort()).toEqual(['bot', 'contato', 'desconhecido', 'operador', 'sistema'])
    })
})

describe('Linktor — histórico da conversa', () => {
    it('em ordem cronológica, com o remetente do sender_type', async () => {
        rede({ status: 200, corpo: USUARIOS })
        const ui = await render()

        const mensagens = ui.getAllByTestId('lk-message')
        expect(mensagens.map(m => m.getAttribute('data-sender'))).toEqual(['contato', 'operador', 'bot'])
        expect(mensagens[0].textContent).toContain('Meu pedido não chegou')
        expect(mensagens[0].textContent).toContain('linktor.conversation.sender.contato')
        expect(mensagens[2].textContent).toContain('Posso ajudar em algo mais?')
    })

    it.each(['resolved', 'closed'])('conversa %s está encerrada: sem resposta e sem "marcar resolvida"',
        async (status) => {
            rede({ status: 200, corpo: USUARIOS }, { ...CONVERSA, status })
            const ui = await render()

            await waitFor(() => expect((ui.getByTestId('lk-reply-input') as HTMLTextAreaElement).disabled).toBe(true))
            expect((ui.getByTestId('lk-resolve') as HTMLButtonElement).disabled).toBe(true)
        })

    it('o painel de detalhes lê contato e canal da conversa do Linktor', async () => {
        rede({ status: 200, corpo: USUARIOS })
        const ui = await render()

        expect(ui.getAllByText('Maria Souza').length).toBeGreaterThan(0)
        expect(ui.getAllByText('Site').length).toBeGreaterThan(0)
    })
})
