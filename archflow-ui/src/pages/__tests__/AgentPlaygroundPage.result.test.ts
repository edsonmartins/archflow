/**
 * RCHFLW-80: o Agent lab respondia só "ACCEPTED" e nunca mostrava o que o agente fez. Depois de
 * Invocar/Enviar, a tela consulta GET /archflow/agents/invocations/{requestId} até o resultado ficar
 * final e mostra a saída (COMPLETED) ou o erro (FAILED).
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { entrarNoTenant } from '../../lib/__tests__/papeisDeTeste'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'

const apiMock = vi.hoisted(() => ({ invoke: vi.fn(), message: vi.fn(), invocation: vi.fn() }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn() } }))
vi.mock('../../components/PropertyPanel/useCatalog', () => ({
  useCatalog: () => ({
    agents: [{ id: 'conversational-agent', displayName: 'Conversational Agent' }],
    assistants: [{ id: 'tech-support-assistant', displayName: 'Tech Support' }],
    tools: [], embeddings: [], memories: [], vectorstores: [], chains: [], loading: false, usingFallback: false,
  }),
}))
vi.mock('../../services/agent-playground-api', async importOriginal => {
  const real = await importOriginal<typeof import('../../services/agent-playground-api')>()
  return { ...real, agentPlaygroundApi: apiMock }
})

import AgentPlaygroundPage from '../AgentPlaygroundPage'
import { InvocationNotFoundError } from '../../services/agent-playground-api'

let root: Root | null = null
let container: HTMLDivElement

const accepted = (requestId: string, agentId: string) => ({
  requestId, tenantId: 'tenant_demo', agentId, status: 'ACCEPTED', timestamp: '2026-11-24T10:00:00Z',
})
const result = (status: string, extra: Record<string, unknown> = {}) => ({
  requestId: 'req-1', tenantId: 'tenant_demo', agentId: 'conversational-agent', status, attempts: 1,
  createdAt: '2026-11-24T10:00:00Z', startedAt: '2026-11-24T10:00:00Z', finishedAt: null, ...extra,
})

beforeEach(() => {
  // As ações que acionam o servidor só aparecem para quem tem a permissão (QA2 RCHFLW-136/158).
  entrarNoTenant('tenant_demo', 'ADMIN')
  vi.clearAllMocks()
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  ;(globalThis as any).ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} }
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  Element.prototype.scrollIntoView ??= () => {}
  // O Textarea autosize do Mantine escuta `document.fonts`, que o jsdom não tem.
  if (!(document as any).fonts) {
    Object.defineProperty(document, 'fonts', {
      value: { addEventListener() {}, removeEventListener() {} }, configurable: true,
    })
  }
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function renderPage() {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, { env: 'test' }, h(AgentPlaygroundPage)))
  })
  return within(container)
}

async function pickAgent(ui: ReturnType<typeof within>, label: string) {
  const input = ui.getByTestId('pg-agent')
  await act(async () => { fireEvent.click(input) })
  const option = await waitFor(() => within(document.body).getByRole('option', { name: new RegExp(label) }))
  await act(async () => { fireEvent.click(option) })
}

describe('AgentPlaygroundPage — resultado da invocação', () => {
  it('Invocar: consulta o requestId até o fim e mostra a saída do agente', async () => {
    apiMock.invoke.mockResolvedValue(accepted('req-1', 'conversational-agent'))
    apiMock.invocation
      .mockResolvedValueOnce(result('RUNNING'))
      .mockResolvedValue(result('COMPLETED', {
        output: { response: 'Olá! Como posso ajudar?' }, finishedAt: '2026-11-24T10:00:01Z',
      }))
    const ui = await renderPage()
    await pickAgent(ui, 'Conversational Agent')

    await act(async () => { fireEvent.click(ui.getByTestId('pg-invoke-btn')) })

    await waitFor(() => expect(ui.getByTestId('pg-result-status').textContent)
      .toBe('agentPlayground.result.status.RUNNING'))
    await waitFor(() => expect(ui.getByTestId('pg-result-status').textContent)
      .toBe('agentPlayground.result.status.COMPLETED'), { timeout: 4000 })
    expect(ui.getByTestId('pg-result-output').textContent).toContain('Olá! Como posso ajudar?')
    expect(apiMock.invocation).toHaveBeenCalledWith('req-1', 'tenant_demo')
    expect(ui.queryByTestId('pg-result-loading')).toBeNull()
    // Terminou: nenhuma consulta a mais.
    const calls = apiMock.invocation.mock.calls.length
    await new Promise(r => setTimeout(r, 1200))
    expect(apiMock.invocation.mock.calls.length).toBe(calls)
  })

  it('Enviar mensagem: o erro do agente aparece na tela', async () => {
    apiMock.message.mockResolvedValue({ ...accepted('req-2', 'tech-support-assistant'), sessionId: 's-1' })
    apiMock.invocation.mockResolvedValue(result('FAILED', {
      requestId: 'req-2', agentId: 'tech-support-assistant',
      error: "operação 'foo' não existe em tech-support-assistant; operações: analyze, respond",
      finishedAt: '2026-11-24T10:00:01Z',
    }))
    const ui = await renderPage()
    await pickAgent(ui, 'Tech Support')
    await act(async () => { fireEvent.click(ui.getByRole('tab', { name: 'agentPlayground.tabs.message' })) })

    await act(async () => { fireEvent.click(ui.getByTestId('pg-message-btn')) })

    const erro = await waitFor(() => ui.getByTestId('pg-result-error'))
    expect(erro.textContent).toContain("operação 'foo' não existe em tech-support-assistant")
    expect(ui.getByTestId('pg-result-status').textContent).toBe('agentPlayground.result.status.FAILED')
    expect(apiMock.invocation).toHaveBeenCalledWith('req-2', 'tenant_demo')
    expect(ui.queryByTestId('pg-result-output')).toBeNull()
  })

  it('requestId que não existe para o tenant: para de consultar e avisa, em vez de girar para sempre', async () => {
    apiMock.invoke.mockResolvedValue(accepted('req-3', 'conversational-agent'))
    apiMock.invocation.mockRejectedValue(new InvocationNotFoundError('invocation not found: req-3'))
    const ui = await renderPage()
    await pickAgent(ui, 'Conversational Agent')

    await act(async () => { fireEvent.click(ui.getByTestId('pg-invoke-btn')) })

    const aviso = await waitFor(() => ui.getByTestId('pg-result-unknown'), { timeout: 5000 })
    expect(aviso.textContent).toContain('agentPlayground.result.notFound')
    expect(ui.queryByTestId('pg-result-loading')).toBeNull()
  })
})
