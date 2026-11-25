/**
 * Seletor de modelo do nó = catálogo da plataforma ∩ modelos permitidos do tenant (RCHFLW-79).
 *
 * <p>Antes, o seletor mostrava a lista fixa do enum do servidor, que não conversava com o catálogo
 * global (desligar um modelo não o tirava do painel) nem com a restrição do tenant (nada a aplicava), e
 * não deixava usar um id que não estivesse nela. Aqui: a lista é a que o servidor manda em
 * /workflow/providers (já recortada), o id livre só aparece com `allowCustomModel` e um modelo gravado
 * que o tenant não pode usar aparece marcado, com aviso.</p>
 *
 * <p>O Select do Mantine vira um {@code <select>} nativo mais um {@code <input>} de busca que chama o
 * onSearchChange — o que se testa é a lista de opções e o que o onChange grava.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import type { ProviderInfo } from '../../../services/workflow-config-api'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, o?: Record<string, unknown>) => {
      const arg = o?.provider ?? o?.model ?? o?.value
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
    searchable?: boolean; searchValue?: string; onSearchChange?: (v: string) => void
  }) => {
    const items = (props.data ?? []).flatMap(d => ('group' in d ? d.items : [d]))
    return createElement('label', null, props.label,
      createElement('select', {
        'data-label': props.label,
        'data-searchable': props.searchable ? 'sim' : 'nao',
        value: props.value ?? '',
        onChange: (e: { target: { value: string } }) => props.onChange?.(e.target.value),
      }, items.map(i => createElement('option', { key: i.value, value: i.value }, i.label))),
      props.onSearchChange && createElement('input', {
        'data-search': props.label,
        value: props.searchValue ?? '',
        onChange: (e: { target: { value: string } }) => props.onSearchChange?.(e.target.value),
      }))
  }
  return { ...real, Select }
})

let providers: ProviderInfo[] = []
vi.mock('../useWorkflowConfig', () => ({
  useWorkflowConfig: () => ({
    providers,
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

/** Como o servidor responde com ARCHFLOW_LLM_MODELS do OpenRouter (só a plataforma tem catálogo). */
function catalogo(allowCustomModel: boolean, openrouterModels: string[]): ProviderInfo[] {
  return [
    { id: 'openai', displayName: 'OpenAI', group: 'Cloud', requiresApiKey: true, supportsStreaming: true,
      models: [], allowCustomModel, platformDefault: false },
    { id: 'anthropic', displayName: 'Anthropic', group: 'Cloud', requiresApiKey: true, supportsStreaming: true,
      models: [], allowCustomModel, platformDefault: false },
    { id: 'openrouter', displayName: 'OpenRouter', group: 'Cloud', requiresApiKey: true, supportsStreaming: true,
      models: openrouterModels.map(id => ({ id, name: id, contextWindow: 0, maxTemperature: 0 })),
      allowCustomModel, platformDefault: true },
  ]
}

const CATALOGO = ['openai/gpt-6-luna', 'google/gemma-4-31b-it:free', 'nvidia/nemotron-3-ultra-550b-a55b:free']

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
  await act(async () => { await Promise.resolve(); await Promise.resolve() })
}

function select(label: string): HTMLSelectElement {
  return container.querySelector(`select[data-label="editor.properties.fields.${label}"]`) as HTMLSelectElement
}

function options(label: string): string[] {
  return Array.from(select(label).options).map(o => o.value)
}

function optionText(label: string, value: string): string {
  return Array.from(select(label).options).find(o => o.value === value)?.textContent ?? ''
}

async function search(label: string, value: string) {
  await act(async () => {
    const input = container.querySelector(`input[data-search="editor.properties.fields.${label}"]`) as HTMLInputElement
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value)
    input.dispatchEvent(new Event('input', { bubbles: true }))
  })
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
    { id: 'openai', displayName: 'OpenAI', kind: 'provider' },
    { id: 'anthropic', displayName: 'Anthropic', kind: 'provider' },
    { id: 'openrouter', displayName: 'OpenRouter', kind: 'provider' },
  ])
  useWorkflowStore.setState({ currentWorkflow: null })
  providers = catalogo(true, CATALOGO)
})

afterEach(() => {
  act(() => { root?.unmount() })
  root = null
  container?.remove()
})

describe('AgentFields — seletor de modelo do catálogo', () => {
  it('nó sem provider herda o da plataforma: o rótulo diz qual, e os modelos são os do catálogo dele', async () => {
    await render('llm-chat')

    expect(optionText('provider', INHERIT)).toBe('editor.properties.fields.inheritPlatformNamed:OpenRouter')
    expect(options('model')).toEqual([INHERIT, ...CATALOGO])
    expect(select('model').dataset.searchable).toBe('sim')
  })

  it('escolher um modelo do catálogo sem escolher provider grava só o modelo (herda o provider)', async () => {
    await render('llm-chat')

    await choose('model', 'google/gemma-4-31b-it:free')

    expect(calls).toEqual([['model', 'google/gemma-4-31b-it:free']])
  })

  it('sem restrição, digitar um id fora do catálogo oferece usá-lo — e grava', async () => {
    await render('llm-chat', { provider: 'openrouter' })

    await search('model', 'meta-llama/llama-5-405b')

    expect(options('model')).toContain('meta-llama/llama-5-405b')
    expect(optionText('model', 'meta-llama/llama-5-405b'))
      .toBe('editor.properties.fields.useCustomModel:meta-llama/llama-5-405b')
    await choose('model', 'meta-llama/llama-5-405b')
    expect(calls).toEqual([['model', 'meta-llama/llama-5-405b']])
  })

  it('digitar um id que já está no catálogo não cria opção repetida', async () => {
    await render('llm-chat', { provider: 'openrouter' })

    await search('model', 'openai/gpt-6-luna')

    expect(options('model').filter(v => v === 'openai/gpt-6-luna')).toHaveLength(1)
  })

  it('tenant restrito: só a lista, sem id livre, com o aviso de restrição', async () => {
    providers = catalogo(false, ['google/gemma-4-31b-it:free'])
    await render('llm-chat', { provider: 'openrouter' })

    await search('model', 'meta-llama/llama-5-405b')

    expect(options('model')).toEqual([INHERIT, 'google/gemma-4-31b-it:free'])
    expect(container.textContent).toContain('editor.properties.fields.modelRestrictedHint')
  })

  it('tenant restrito: provider sem nenhum modelo permitido sai do dropdown', async () => {
    providers = catalogo(false, ['google/gemma-4-31b-it:free'])
    await render('llm-chat')

    expect(options('provider')).toEqual([INHERIT, 'openrouter'])
  })

  it('tenant restrito: um adapter de chat de plugin (sem modelos no catálogo) também não entra', async () => {
    providers = catalogo(false, ['google/gemma-4-31b-it:free'])
    listProviders.mockResolvedValue([
      { id: 'openrouter', displayName: 'OpenRouter', kind: 'provider' },
      { id: 'plugin-x', displayName: 'Plugin X', kind: 'provider' },
    ])
    await render('llm-chat')

    expect(options('provider')).toEqual([INHERIT, 'openrouter'])
  })

  it('tenant restrito: modelo gravado fora da lista aparece marcado, com aviso de que vai falhar', async () => {
    providers = catalogo(false, ['google/gemma-4-31b-it:free'])
    await render('llm-chat', { provider: 'openrouter', model: 'openai/gpt-6-luna' })

    expect(select('model').value).toBe('openai/gpt-6-luna')
    expect(optionText('model', 'openai/gpt-6-luna'))
      .toBe('editor.properties.fields.modelNotAllowed:openai/gpt-6-luna')
    expect(container.textContent).toContain('editor.properties.fields.modelNotAllowedHint')
  })

  it('sem restrição, modelo gravado fora do catálogo aparece como está, sem aviso', async () => {
    await render('llm-chat', { provider: 'openrouter', model: 'meta-llama/llama-5-405b' })

    expect(optionText('model', 'meta-llama/llama-5-405b')).toBe('meta-llama/llama-5-405b')
    expect(container.textContent).not.toContain('modelNotAllowedHint')
  })

  // RCHFLW-79: "Padrão do modelo" num tenant restrito herda o do fluxo ou o da plataforma — fora da lista,
  // a execução recusa com [LLM-MODELO]. O painel precisa dizer isso, não parecer válido.
  function restritoComPadrao(defaultModel: string | null): ProviderInfo[] {
    return catalogo(false, ['google/gemma-4-31b-it:free'])
      .map(p => (p.platformDefault ? { ...p, defaultModel } : p))
  }

  it('tenant restrito: nó sem modelo herdando o da plataforma fora da lista mostra o aviso', async () => {
    providers = restritoComPadrao('openai/gpt-6-luna')
    await render('llm-chat')

    expect(select('model').value).toBe(INHERIT)
    expect(container.textContent)
      .toContain('editor.properties.fields.modelInheritedNotAllowed:openai/gpt-6-luna')
  })

  it('tenant restrito: herdar o modelo do fluxo fora da lista também avisa (o fluxo vence a plataforma)', async () => {
    providers = restritoComPadrao('google/gemma-4-31b-it:free')
    useWorkflowStore.setState({
      currentWorkflow: { configuration: { llmConfig: { model: 'openai/gpt-6-luna' } } } as any,
    })
    await render('mcp-agent')

    expect(container.textContent)
      .toContain('editor.properties.fields.modelInheritedNotAllowed:openai/gpt-6-luna')
  })

  it('tenant restrito: nó de chat com outro provider e sem modelo não herda nenhum — avisa', async () => {
    providers = restritoComPadrao('google/gemma-4-31b-it:free')
    await render('llm-chat', { provider: 'anthropic' })

    expect(container.textContent).toContain('editor.properties.fields.modelInheritedMissing')
  })

  it('tenant restrito: herdar um modelo permitido não avisa', async () => {
    providers = restritoComPadrao('google/gemma-4-31b-it:free')
    await render('llm-chat')

    expect(container.textContent).not.toContain('modelInherited')
    expect(container.textContent).toContain('editor.properties.fields.modelRestrictedHint')
  })

  it('sem restrição, herdar qualquer modelo não avisa', async () => {
    providers = catalogo(true, CATALOGO).map(p => (p.platformDefault ? { ...p, defaultModel: 'x/fora' } : p))
    await render('llm-chat')

    expect(container.textContent).not.toContain('modelInherited')
  })

  it('o padrão do fluxo manda na lista de modelos do nó sem provider', async () => {
    providers = [
      ...catalogo(true, CATALOGO).slice(0, 2).map(p => p.id === 'anthropic'
        ? { ...p, models: [{ id: 'claude-x', name: 'Claude X', contextWindow: 200000, maxTemperature: 1 }] }
        : p),
      catalogo(true, CATALOGO)[2],
    ]
    useWorkflowStore.setState({
      currentWorkflow: { configuration: { llmConfig: { provider: 'anthropic' } } } as any,
    })
    await render('llm-chat')

    expect(options('model')).toEqual([INHERIT, 'claude-x'])
  })
})
