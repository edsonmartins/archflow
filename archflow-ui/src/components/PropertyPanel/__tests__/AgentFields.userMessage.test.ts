/**
 * RCHFLW-120 — o LLM Chat depois de uma Decisão ou Condição recebia o JSON da classificação como mensagem do usuário.
 * O painel dos nós de chat ganha a "Mensagem do usuário" (userMessage), com a dica do que vale vazio e de como citar
 * a Entrada, e as referências dos nós anteriores para clicar em vez de digitar o id.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { fireEvent } from '@testing-library/dom'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string) => k,
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))
vi.mock('../useWorkflowConfig', () => ({
  useWorkflowConfig: () => ({
    providers: [], patterns: [], personas: [], governance: [], mcpServers: [], loading: false,
  }),
}))
vi.mock('../useChatProviders', () => ({ useChatProviders: () => null }))
vi.mock('../useCatalog', () => ({
  useCatalog: () => ({ agents: [], assistants: [], tools: [], usingFallback: false }),
}))

import { AgentFields } from '../fields/AgentFields'
import { useFlowStore } from '../../FlowCanvas/store/useFlowStore'
import type { FlowNodeData } from '../../FlowCanvas/types'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

let root: Root | null = null
let container: HTMLDivElement

function render(nodeType: string, config: Record<string, unknown>, update = vi.fn()) {
  const nodeData: FlowNodeData = { label: 'Responder', componentId: nodeType, nodeType, status: 'idle', config }
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  act(() => {
    root!.render(h(MantineProvider, null,
      h(AgentFields, { nodeData, update, isAssistant: false, nodeId: 'responder' })))
  })
  return update
}

const field = () => container.querySelector('[data-testid="panel-user-message"]')
const textarea = () => field()!.querySelector('textarea') as HTMLTextAreaElement

const node = (id: string, nodeType: string) => ({
  id, type: 'custom', position: { x: 0, y: 0 },
  data: { label: id, componentId: nodeType, nodeType, status: 'idle', config: {} } as FlowNodeData,
})

beforeEach(() => {
  ;(globalThis as any).ResizeObserver = ResizeObserverStub
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  if (!(document as any).fonts) {
    Object.defineProperty(document, 'fonts', {
      value: { addEventListener() {}, removeEventListener() {} }, configurable: true,
    })
  }
  useFlowStore.setState({ nodes: [], edges: [] })
})

afterEach(() => {
  act(() => { root?.unmount() })
  root = null
  container?.remove()
})

describe('AgentFields — Mensagem do usuário (RCHFLW-120)', () => {
  it.each(['llm-chat', 'llm-streaming'])('%s mostra o campo gravado, com a dica', (nodeType) => {
    render(nodeType, { userMessage: 'Cliente: ${receber}' })

    expect(field()).not.toBeNull()
    expect(field()!.textContent).toContain('editor.properties.fields.userMessage')
    expect(container.querySelector('[data-testid="panel-user-message-hint"]')!.textContent)
      .toBe('editor.properties.fields.userMessageHint')
    expect(textarea().value).toBe('Cliente: ${receber}')
  })

  it('vazio não mostra valor de exemplo: só o placeholder (o motor usa a saída do passo anterior)', () => {
    render('llm-chat', {})

    expect(textarea().value).toBe('')
    expect(textarea().placeholder).toBe('editor.properties.fields.userMessagePlaceholder')
  })

  it('o que se digita é gravado em userMessage', () => {
    const update = render('llm-chat', {})

    act(() => { fireEvent.change(textarea(), { target: { value: '${receber}' } }) })

    expect(update).toHaveBeenCalledWith('userMessage', '${receber}')
  })

  it('clicar na referência da Entrada a insere — no fim do que já está escrito', () => {
    useFlowStore.setState({
      nodes: [node('receber', 'input'), node('triagem', 'decision'), node('responder', 'llm-chat')] as any,
      edges: [{ id: 'e1', source: 'receber', target: 'triagem' }, { id: 'e2', source: 'triagem', target: 'responder' }],
    })
    const vazio = render('llm-chat', {})
    const refs = [...field()!.querySelectorAll('[data-testid="upstream-ref"]')]
    expect(refs.map(r => r.textContent)).toEqual(['triagem →${triagem.route}', 'receber →${receber}'])

    act(() => { (refs[1] as HTMLElement).click() })
    expect(vazio).toHaveBeenCalledWith('userMessage', '${receber}')

    act(() => { root?.unmount() })
    container.remove()
    const escrito = render('llm-chat', { userMessage: 'Cliente: ' })
    act(() => {
      (field()!.querySelectorAll('[data-testid="upstream-ref"]')[1] as HTMLElement).click()
    })
    expect(escrito).toHaveBeenCalledWith('userMessage', 'Cliente: ${receber}')
  })

  it('o nó de agente (MCP) não tem o campo: ele lê a entrada por outro caminho', () => {
    render('agent', { userMessage: '${receber}' })
    expect(field()).toBeNull()
  })
})
