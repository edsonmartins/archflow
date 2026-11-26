/**
 * RCHFLW-188 (item 7): deep links obsoletos, com a UI em pt-BR, mostravam o erro cru do backend em inglês ou
 * uma página "fantasma":
 * a) /approvals/<inexistente> — `Approval not found or already decided: <id>`;
 * b) /conversations/<inexistente> — `Conversation not found: <id>`, com o chat ativo embaixo;
 * c) /admin/mcp/<inexistente> — um servidor OFFLINE com Reconectar (a API respondia 200; agora 404);
 * d) /admin/linktor/inbox/<conversa> com a integração desligada — a frase do backend em inglês.
 *
 * <p>A rede é simulada no fetch, então as respostas passam pelo request() real (ApiError com o status).</p>
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act, type ReactElement } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { waitFor, within } from '@testing-library/dom'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn() } }))
vi.mock('../../components/chat/ChatPanel', () => ({
  default: () => 'chat-panel',
}))

import ApprovalDetailPage from '../ApprovalDetailPage'
import ConversationPage from '../ConversationPage'
import McpServerDetailPage from '../McpServerDetailPage'
import LinktorConversationPage from '../LinktorConversationPage'

type Rota = { status: number; corpo: unknown }

function rede(respostas: Record<string, Rota>) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    const caminho = String(url).replace(/^\/api/, '').split('?')[0]
    const r = respostas[caminho] ?? { status: 404, corpo: { error: `no route ${caminho}` } }
    return new Response(JSON.stringify(r.corpo), { status: r.status })
  }))
}

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
  sessionStorage.clear()
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  ;(globalThis as any).ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} }
  ;(document as any).fonts ??= { addEventListener() {}, removeEventListener() {} }
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
  vi.unstubAllGlobals()
})

async function render(url: string, path: string, page: ReactElement) {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, null,
      h(MemoryRouter, { initialEntries: [url] }, h(Routes, null, h(Route, { path, element: page })))))
  })
  return within(container)
}

describe('Deep links obsoletos (RCHFLW-188, item 7)', () => {
  it('a) aprovação inexistente: a frase do app, não a do backend', async () => {
    rede({ '/approvals/does-not-exist': { status: 404,
      corpo: { error: 'Approval not found or already decided: does-not-exist', status: 404 } } })

    const ui = await render('/approvals/does-not-exist?flowId=nope', '/approvals/:id', h(ApprovalDetailPage))

    await waitFor(() => expect(ui.getByText('approvals.notFound')).toBeTruthy())
    expect(container.textContent).not.toContain('Approval not found')
  })

  it('a) outra falha ao abrir a aprovação continua mostrando o motivo', async () => {
    rede({ '/approvals/a-1': { status: 500, corpo: { error: 'banco fora do ar' } } })

    const ui = await render('/approvals/a-1', '/approvals/:id', h(ApprovalDetailPage))

    await waitFor(() => expect(ui.getByText('banco fora do ar')).toBeTruthy())
  })

  it('b) conversa inexistente: cartão "não encontrada", sem o chat', async () => {
    rede({ '/conversations/does-not-exist': { status: 404,
      corpo: { error: 'Conversation not found: does-not-exist' } } })

    const ui = await render('/conversations/does-not-exist', '/conversations/:id', h(ConversationPage))

    await waitFor(() => expect(ui.getByTestId('conversation-not-found')).toBeTruthy())
    expect(ui.getByText('conversationDetail.notFoundTitle')).toBeTruthy()
    expect(container.textContent).not.toContain('Conversation not found')
    expect(container.textContent).not.toContain('chat-panel')
  })

  it('c) servidor MCP fora da configuração (404): cartão "não encontrado", sem Reconectar nem OFFLINE', async () => {
    rede({ '/admin/mcp/servers/nope-server': { status: 404,
      corpo: { error: 'MCP server not registered: nope-server', status: 404 } } })

    const ui = await render('/admin/mcp/nope-server', '/admin/mcp/:name', h(McpServerDetailPage))

    await waitFor(() => expect(ui.getByTestId('mcp-not-found')).toBeTruthy())
    expect(ui.getByText('mcp.detail.notFoundTitle')).toBeTruthy()
    expect(ui.queryByText('mcp.detail.reconnect')).toBeNull()
    expect(ui.queryByText('mcp.detail.offline')).toBeNull()
    expect(container.textContent).not.toContain('not registered')
  })

  it('c) servidor configurado e offline continua com a tela do servidor', async () => {
    rede({ '/admin/mcp/servers/linktor': { status: 200, corpo: {
      serverName: 'linktor', connected: false, tools: [], prompts: [], resources: [],
      error: 'connect/initialize failed: recusado' } } })

    const ui = await render('/admin/mcp/linktor', '/admin/mcp/:name', h(McpServerDetailPage))

    await waitFor(() => expect(ui.getAllByText('mcp.detail.offline').length).toBeGreaterThan(0))
    expect(ui.queryByTestId('mcp-not-found')).toBeNull()
    expect(ui.getByText('mcp.detail.reconnect')).toBeTruthy()
  })

  it('d) conversa do Linktor com a integração desligada: o aviso traduzido, com o atalho', async () => {
    const desligada = { status: 503,
      corpo: { error: 'Linktor integration is disabled; enable it in /admin/linktor first' } }
    rede({
      '/admin/linktor/inbox/conversations/nope-conv': desligada,
      '/admin/linktor/inbox/conversations/nope-conv/messages': desligada,
      '/admin/linktor/inbox/users': desligada,
    })

    const ui = await render('/admin/linktor/inbox/nope-conv', '/admin/linktor/inbox/:id',
      h(LinktorConversationPage))

    await waitFor(() => expect(ui.getByTestId('linktor-conversation-disabled')).toBeTruthy())
    expect(ui.getByText('linktor.inbox.disabledTitle')).toBeTruthy()
    expect(ui.getByText('linktor.inbox.openConfig')).toBeTruthy()
    expect(container.textContent).not.toContain('Linktor integration is disabled')
  })
})
