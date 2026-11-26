/**
 * QA2 RCHFLW-158: as telas que põem um agente/LLM para rodar no tenant só oferecem a ação a quem tem
 * `workflow:execute` — o que o servidor exige em `/archflow/agents/**` (Agent lab), `/api/orchestration/**`
 * (orquestração), `/ag-ui/workflows/{id}` (AG-UI runner), `/api/conversations` (nova conversa) e no
 * handshake do `/api/realtime/{tenant}/{persona}` (voz). Antes um VIEWER clicava e levava 403 (ou, na voz,
 * o handshake recusado). O botão fica desabilitado com o aviso da permissão que falta.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { entrarComo } from '../../lib/__tests__/papeisDeTeste'
import { createElement as h, act, type ReactElement } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor } from '@testing-library/dom'

const playgroundMock = vi.hoisted(() => ({ invoke: vi.fn(), message: vi.fn(), invocation: vi.fn() }))
const orchestrationMock = vi.hoisted(() => ({ run: vi.fn() }))
const runAgUi = vi.hoisted(() => vi.fn(() => () => {}))
const conversationMock = vi.hoisted(() => ({ listConversations: vi.fn(), createConversation: vi.fn() }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn() } }))
vi.mock('../../components/PropertyPanel/useCatalog', () => ({
  useCatalog: () => ({
    agents: [{ id: 'conversational-agent', displayName: 'Conversational Agent' }],
    assistants: [], tools: [], embeddings: [], memories: [], vectorstores: [], chains: [],
    loading: false, usingFallback: false,
  }),
}))
vi.mock('../../services/agent-playground-api', async importOriginal => {
  const real = await importOriginal<typeof import('../../services/agent-playground-api')>()
  return { ...real, agentPlaygroundApi: playgroundMock }
})
vi.mock('../../services/orchestration-api', async importOriginal => {
  const real = await importOriginal<typeof import('../../services/orchestration-api')>()
  return { ...real, orchestrationApi: orchestrationMock }
})
vi.mock('../../services/agui-client', () => ({ runAgUiWorkflow: runAgUi }))
vi.mock('../../services/conversation-api', () => ({ conversationApi: conversationMock }))
vi.mock('../../services/workflow-config-api', () => ({
  workflowConfigApi: { getPersonas: vi.fn(async () => []) },
}))

import AgentPlaygroundPage from '../AgentPlaygroundPage'
import DynamicWorkflowPage from '../DynamicWorkflowPage'
import AgUiRunnerPage from '../AgUiRunnerPage'
import ConversationsListPage from '../ConversationsListPage'
import VoicePlaygroundPage from '../VoicePlaygroundPage'

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
  vi.clearAllMocks()
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  ;(globalThis as any).ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} }
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  Element.prototype.scrollTo ??= function () {}
  Element.prototype.scrollIntoView ??= () => {}
  if (!(document as any).fonts) {
    Object.defineProperty(document, 'fonts', {
      value: { addEventListener() {}, removeEventListener() {} }, configurable: true,
    })
  }
  conversationMock.listConversations.mockResolvedValue({ items: [], total: 0 })
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function render(page: () => ReactElement) {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, { env: 'test' }, h(MemoryRouter, null, page())))
  })
}

function botao(testId: string): HTMLButtonElement {
  const el = container.querySelector(`[data-testid="${testId}"]`)
  expect(el, testId).not.toBeNull()
  return el as HTMLButtonElement
}

function falta(testId: string): string | null {
  return botao(testId).closest('[data-permission-missing]')?.getAttribute('data-permission-missing') ?? null
}

/** As telas e o botão que aciona o agente em cada uma, já pronto para clicar (campos obrigatórios cheios). */
const TELAS: { nome: string; page: () => ReactElement; botao: string; preparar?: () => Promise<void> }[] = [
  { nome: 'Agent lab (invocar)', page: () => h(AgentPlaygroundPage), botao: 'pg-invoke-btn' },
  {
    nome: 'Orquestração', page: () => h(DynamicWorkflowPage), botao: 'dw-run',
    preparar: async () => {
      await act(async () => { fireEvent.change(botao('dw-goal'), { target: { value: 'Resumir o pedido' } }) })
    },
  },
  { nome: 'AG-UI runner', page: () => h(AgUiRunnerPage), botao: 'agui-run' },
  { nome: 'Conversas (nova)', page: () => h(ConversationsListPage), botao: 'conversation-new' },
  { nome: 'Voz (microfone)', page: () => h(VoicePlaygroundPage), botao: 'mic-button' },
]

describe('Telas que acionam agente — exigem workflow:execute (QA2 RCHFLW-158)', () => {
  for (const tela of TELAS) {
    it(`${tela.nome}: VIEWER vê o botão desabilitado, com o aviso de workflow:execute`, async () => {
      entrarComo('VIEWER')
      await render(tela.page)
      await waitFor(() => botao(tela.botao))
      await tela.preparar?.()

      expect(botao(tela.botao).disabled).toBe(true)
      expect(falta(tela.botao)).toBe('workflow:execute')
    })

    it(`${tela.nome}: EXECUTOR pode (botão habilitado, sem aviso)`, async () => {
      entrarComo('EXECUTOR')
      await render(tela.page)
      await waitFor(() => botao(tela.botao))
      await tela.preparar?.()

      expect(botao(tela.botao).disabled).toBe(false)
      expect(falta(tela.botao)).toBeNull()
    })
  }

  it('Agent lab: Enviar mensagem também fica desabilitado para o VIEWER', async () => {
    entrarComo('VIEWER')
    await render(() => h(AgentPlaygroundPage))
    const aba = [...container.querySelectorAll('[role="tab"]')]
      .find(t => t.textContent?.includes('agentPlayground.tabs.message')) as HTMLElement | undefined
    if (aba) await act(async () => { fireEvent.click(aba) })
    await waitFor(() => botao('pg-message-btn'))

    expect(botao('pg-message-btn').disabled).toBe(true)
    expect(falta('pg-message-btn')).toBe('workflow:execute')
  })
})
