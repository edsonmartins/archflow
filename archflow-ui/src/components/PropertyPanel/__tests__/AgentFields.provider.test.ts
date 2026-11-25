/**
 * Provider e modelo do painel de nós de chat/agente (RCHFLW-16, RCHFLW-43).
 *
 * <p>Antes, um nó novo (config {}) mostrava "OpenAI / GPT-4o" sem gravar nada, e o dropdown de um
 * llm-chat oferecia os 16 providers do provider-hub — 13 deles sem adapter de chat, que só falhavam na
 * execução. Aqui: o que aparece é o que está gravado, "herdar" é uma opção explícita que remove as
 * chaves, e o llm-chat só oferece o que o servidor executa (GET /api/catalog/chat-providers).</p>
 *
 * <p>O Select do Mantine é trocado por um {@code <select>} nativo: o que se testa é a lista de opções
 * e o que o onChange grava, não o combobox do Mantine (que abre num portal).</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, o?: Record<string, unknown>) => {
      const arg = o?.provider ?? o?.value
      return arg !== undefined ? `${k}:${arg}` : k
    },
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))

type Item = { value: string; label: string }
type Group = { group: string; items: Item[] }

vi.mock('@mantine/core', async (importOriginal) => {
  const real = await importOriginal<typeof import('@mantine/core')>()
  const { createElement } = await import('react')
  const Select = (props: {
    label?: string; value?: string | null; data?: (Item | Group)[]; onChange?: (v: string | null) => void
  }) => {
    const items = (props.data ?? []).flatMap(d => ('group' in d ? d.items : [d]))
    return createElement('label', null, props.label,
      createElement('select', {
        'data-label': props.label,
        value: props.value ?? '',
        onChange: (e: { target: { value: string } }) => props.onChange?.(e.target.value),
      }, items.map(i => createElement('option', { key: i.value, value: i.value }, i.label))))
  }
  // NumberInput nativo: o que importa é o valor mostrado, o placeholder e o que o onChange grava
  // (o do Mantine entrega number, ou '' quando o campo é apagado).
  const NumberInput = (props: {
    label?: string; value?: number | string; placeholder?: string; onChange?: (v: number | string) => void
  }) => createElement('input', {
    'data-label': props.label,
    value: props.value ?? '',
    placeholder: props.placeholder,
    onChange: (e: { target: { value: string } }) =>
      props.onChange?.(e.target.value === '' ? '' : Number(e.target.value)),
  })
  return { ...real, Select, NumberInput }
})

vi.mock('../useWorkflowConfig', () => ({
  useWorkflowConfig: () => ({
    providers: [
      { id: 'openai', displayName: 'OpenAI', group: 'Cloud', requiresApiKey: true, supportsStreaming: true,
        models: [{ id: 'gpt-4o', name: 'GPT-4o', contextWindow: 128000, maxTemperature: 2 }] },
      { id: 'anthropic', displayName: 'Anthropic', group: 'Cloud', requiresApiKey: true, supportsStreaming: true,
        models: [{ id: 'claude-sonnet-4-6', name: 'Claude Sonnet', contextWindow: 200000, maxTemperature: 1 }] },
      { id: 'gemini', displayName: 'Gemini', group: 'Cloud', requiresApiKey: true, supportsStreaming: true,
        models: [{ id: 'gemini-2.5', name: 'Gemini 2.5', contextWindow: 1000000, maxTemperature: 2 }] },
      { id: 'openrouter', displayName: 'OpenRouter', group: 'Cloud', requiresApiKey: true, supportsStreaming: true,
        models: [{ id: 'openai/gpt-6-luna', name: 'GPT-6 Luna', contextWindow: 128000, maxTemperature: 2 }] },
    ],
    patterns: [{ id: 'react', label: 'ReAct', description: '' }],
    personas: [],
    governance: [],
    mcpServers: [],
    loading: false,
  }),
}))
vi.mock('../useCatalog', () => ({
  useCatalog: () => ({ agents: [], assistants: [], tools: [], usingFallback: false }),
}))
const listProviders = vi.fn()
vi.mock('../../../services/catalog-api', () => ({
  catalogApi: { listProviders: () => listProviders() },
}))

import { AgentFields } from '../fields/AgentFields'
import { INHERIT } from '../fields/helpers'
import { resetChatProvidersCache } from '../useChatProviders'
import { useWorkflowStore } from '../../../stores/workflow-store'
import type { FlowNodeData } from '../../FlowCanvas/types'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

let root: Root | null = null
let container: HTMLDivElement
let calls: [string, unknown][]

async function render(nodeType: string, config: Record<string, unknown> = {}) {
  calls = []
  const nodeData: FlowNodeData = { label: 'Nó', componentId: nodeType, nodeType, status: 'idle', config }
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, null, h(AgentFields, {
      nodeData, update: (k: string, v: unknown) => { calls.push([k, v]) }, isAssistant: false,
    })))
  })
  // Deixa a promessa do catálogo de chat resolver.
  await act(async () => { await Promise.resolve(); await Promise.resolve() })
}

function select(label: string): HTMLSelectElement {
  return container.querySelector(`select[data-label="editor.properties.fields.${label}"]`) as HTMLSelectElement
}

function numberInput(label: string): HTMLInputElement {
  return container.querySelector(`input[data-label="editor.properties.fields.${label}"]`) as HTMLInputElement
}

async function typeNumber(label: string, value: string) {
  await act(async () => {
    const input = numberInput(label)
    // React só vê a mudança pelo setter nativo do protótipo.
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value)
    input.dispatchEvent(new Event('input', { bubbles: true }))
  })
}

function options(label: string): string[] {
  return Array.from(select(label).options).map(o => o.value)
}

function selectedText(label: string): string {
  const s = select(label)
  return s.options[s.selectedIndex]?.textContent ?? ''
}

async function choose(label: string, value: string) {
  await act(async () => {
    const s = select(label)
    s.value = value
    s.dispatchEvent(new Event('change', { bubbles: true }))
  })
}

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
  resetChatProvidersCache()
  listProviders.mockReset()
  listProviders.mockResolvedValue([
    { id: 'anthropic', displayName: 'Anthropic', kind: 'provider' },
    { id: 'openrouter', displayName: 'OpenRouter', kind: 'provider' },
  ])
  useWorkflowStore.setState({ currentWorkflow: null })
})

afterEach(() => {
  act(() => { root?.unmount() })
  root = null
  container?.remove()
})

describe('AgentFields — provider e modelo', () => {
  it('nó novo (config {}) mostra "Padrão da plataforma", não providers[0]', async () => {
    await render('llm-chat')

    expect(select('provider').value).toBe(INHERIT)
    expect(selectedText('provider')).toBe('editor.properties.fields.inheritPlatform')
    expect(select('model').value).toBe(INHERIT)
    expect(container.textContent).toContain('editor.properties.fields.inheritHint')
    expect(calls).toEqual([])
  })

  it('com configuration.llmConfig.provider no fluxo, a opção diz "Padrão do fluxo"', async () => {
    useWorkflowStore.setState({
      currentWorkflow: { configuration: { llmConfig: { provider: 'openrouter' } } } as any,
    })
    await render('llm-chat')

    expect(selectedText('provider')).toBe('editor.properties.fields.inheritFlow:openrouter')
  })

  it('llm-chat lista só os providers com adapter de chat no servidor, mais o herdar', async () => {
    await render('llm-chat')

    expect(options('provider')).toEqual([INHERIT, 'anthropic', 'openrouter'])
  })

  it('agente continua com a lista inteira (o provider-hub fala com todos)', async () => {
    await render('agent')

    expect(options('provider')).toEqual([INHERIT, 'openai', 'anthropic', 'gemini', 'openrouter'])
    expect(selectedText('provider')).toBe('editor.properties.fields.inheritPlatform')
  })

  it('escolher um provider GRAVA provider e modelo', async () => {
    await render('llm-chat')

    await choose('provider', 'openrouter')

    expect(calls).toEqual([['provider', 'openrouter'], ['model', 'openai/gpt-6-luna']])
  })

  it('escolher um modelo grava o modelo', async () => {
    await render('llm-chat', { provider: 'openrouter' })

    await choose('model', 'openai/gpt-6-luna')

    expect(calls).toEqual([['model', 'openai/gpt-6-luna']])
  })

  it('voltar para o padrão remove provider e modelo', async () => {
    await render('llm-chat', { provider: 'openrouter', model: 'openai/gpt-6-luna' })
    expect(select('provider').value).toBe('openrouter')
    expect(select('model').value).toBe('openai/gpt-6-luna')

    await choose('provider', INHERIT)

    expect(calls).toEqual([['provider', undefined], ['model', undefined]])
  })

  it('provider gravado sem adapter de chat aparece marcado, com aviso', async () => {
    await render('llm-chat', { provider: 'gemini' })

    expect(select('provider').value).toBe('gemini')
    expect(selectedText('provider')).toBe('editor.properties.fields.noChatAdapter:gemini')
    expect(container.textContent).toContain('editor.properties.fields.noChatAdapterHint')
  })

  it('temperatura e maxTokens não gravados aparecem vazios, com o padrão no placeholder', async () => {
    await render('llm-chat')

    expect(numberInput('temperature').value).toBe('')
    expect(numberInput('maxTokens').value).toBe('')
    expect(numberInput('temperature').placeholder).toBe('editor.properties.fields.modelDefault')
    expect(numberInput('maxTokens').placeholder).toBe('editor.properties.fields.modelDefault')
  })

  it('com llmConfig no fluxo, o placeholder mostra o valor que vai rodar', async () => {
    useWorkflowStore.setState({
      currentWorkflow: { configuration: { llmConfig: { temperature: 0.2, maxTokens: 512 } } } as any,
    })
    await render('llm-chat')

    expect(numberInput('temperature').placeholder).toBe('editor.properties.fields.inheritFlowValue:0.2')
    expect(numberInput('maxTokens').placeholder).toBe('editor.properties.fields.inheritFlowValue:512')
  })

  it('o valor gravado aparece; apagar o campo remove a chave em vez de gravar ""', async () => {
    await render('llm-chat', { temperature: 0.4, maxTokens: 1000 })
    expect(numberInput('temperature').value).toBe('0.4')
    expect(numberInput('maxTokens').value).toBe('1000')

    await typeNumber('maxTokens', '')
    await typeNumber('temperature', '0.9')

    expect(calls).toEqual([['maxTokens', undefined], ['temperature', 0.9]])
  })

  it('sem resposta do catálogo de chat, não inventa lista: mostra a inteira', async () => {
    listProviders.mockRejectedValue(new Error('offline'))
    await render('llm-chat')

    expect(options('provider')).toEqual([INHERIT, 'openai', 'anthropic', 'gemini', 'openrouter'])
  })
})
