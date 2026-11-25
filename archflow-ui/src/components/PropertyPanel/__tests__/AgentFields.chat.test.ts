/**
 * Nós llm-chat / llm-streaming são uma chamada de chat a um provider, não um agente MCP: o painel não
 * pode oferecer tipo de agente, estratégia ReAct, iterações, governança nem servidores MCP — o nó
 * ignora tudo isso. Nós de agente continuam com o painel completo.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string) => k,
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))
vi.mock('../useWorkflowConfig', () => ({
  useWorkflowConfig: () => ({
    providers: [{
      id: 'anthropic', displayName: 'Anthropic', group: 'Cloud',
      models: [{ id: 'claude-sonnet-4-6', name: 'Claude Sonnet', contextWindow: 200000, maxTemperature: 1 }],
    }],
    patterns: [{ id: 'react', label: 'ReAct', description: 'raciocina e age' }],
    personas: [],
    governance: [],
    mcpServers: [],
    loading: false,
  }),
}))
// Sem rede no teste: a lista de providers com adapter de chat é coberta em AgentFields.provider.test.ts.
vi.mock('../useChatProviders', () => ({ useChatProviders: () => null }))
vi.mock('../useCatalog', () => ({
  useCatalog: () => ({
    agents: [{ id: 'mcp-agent', displayName: 'MCP Agent', kind: 'agent', description: '' }],
    assistants: [],
    tools: [],
    usingFallback: false,
  }),
}))

import { AgentFields } from '../fields/AgentFields'
import type { FlowNodeData } from '../../FlowCanvas/types'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

let root: Root | null = null
let container: HTMLDivElement

function render(nodeType: string) {
  const nodeData: FlowNodeData = { label: 'Nó', componentId: nodeType, nodeType, status: 'idle', config: {} }
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  act(() => {
    root!.render(h(MantineProvider, null, h(AgentFields, { nodeData, update: () => {}, isAssistant: false })))
  })
  return container.textContent ?? ''
}

beforeEach(() => {
  ;(globalThis as any).ResizeObserver = ResizeObserverStub
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  // O Textarea autosize do Mantine escuta `document.fonts`, que o jsdom não tem.
  if (!(document as any).fonts) {
    Object.defineProperty(document, 'fonts', {
      value: { addEventListener() {}, removeEventListener() {} }, configurable: true,
    })
  }
})

afterEach(() => {
  act(() => { root?.unmount() })
  root = null
  container?.remove()
})

const AGENT_ONLY = [
  'editor.properties.fields.agentType',
  'editor.properties.fields.executionStrategy',
  'editor.properties.fields.maxIterations',
  'editor.properties.fields.governance',
  'editor.properties.fields.mcpServers',
]

const CHAT_FIELDS = [
  'editor.properties.fields.provider',
  'editor.properties.fields.model',
  'editor.properties.fields.systemPrompt',
  'editor.properties.fields.temperature',
  'editor.properties.fields.maxTokens',
]

describe('AgentFields — nós de chat', () => {
  it.each(['llm-chat', 'llm-streaming'])('%s mostra só os campos de chat', (nodeType) => {
    const text = render(nodeType)
    for (const label of CHAT_FIELDS) expect(text).toContain(label)
    for (const label of AGENT_ONLY) expect(text).not.toContain(label)
  })

  it('nó de agente continua com tipo, estratégia, governança e MCP', () => {
    const text = render('agent')
    for (const label of [...CHAT_FIELDS, ...AGENT_ONLY]) expect(text).toContain(label)
  })
})
