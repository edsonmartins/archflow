/**
 * "Padrões do fluxo": o modelo gravado em configuration.llmConfig.model tem que continuar visível.
 *
 * <p>A lista do campo passou a ser o catálogo ativo ∩ os permitidos do tenant (RCHFLW-79). Um padrão
 * gravado antes — id do enum antigo, fora do ARCHFLOW_LLM_MODELS, ou fora da restrição do tenant —
 * sumia do campo ("—"), enquanto todo nó que herda rodava com ele (ou falhava com [LLM-MODELO]).</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import type { ProviderInfo } from '../../../services/workflow-config-api'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, o?: Record<string, unknown>) => (o?.model !== undefined ? `${k}:${o.model}` : k),
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))

type Item = { value: string; label: string }
type Group = { group: string; items: Item[] }

vi.mock('@mantine/core', async (importOriginal) => {
  const real = await importOriginal<typeof import('@mantine/core')>()
  const { createElement } = await import('react')
  const Select = (props: { label?: string; value?: string | null; data?: (Item | Group)[] }) => {
    const items = (props.data ?? []).flatMap(d => ('group' in d ? d.items : [d]))
    return createElement('select', { 'data-label': props.label, value: props.value ?? '', onChange: () => {} },
      items.map(i => createElement('option', { key: i.value, value: i.value }, i.label)))
  }
  return { ...real, Select }
})

let providers: ProviderInfo[] = []
vi.mock('../useWorkflowConfig', () => ({
  useWorkflowConfig: () => ({ providers, patterns: [], personas: [], governance: [], mcpServers: [], loading: false }),
}))

import { FlowDefaultsPanel } from '../FlowDefaultsPanel'
import { useWorkflowStore } from '../../../stores/workflow-store'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

function catalogo(allowCustomModel: boolean, models: string[]): ProviderInfo[] {
  return [{
    id: 'openrouter', displayName: 'OpenRouter', group: 'Cloud', requiresApiKey: true, supportsStreaming: true,
    models: models.map(id => ({ id, name: id, contextWindow: 0, maxTemperature: 0 })),
    allowCustomModel, platformDefault: true,
  }]
}

let root: Root | null = null
let container: HTMLDivElement

async function render(model?: string) {
  useWorkflowStore.setState({
    currentWorkflow: { configuration: { llmConfig: model ? { model } : {} } } as any,
  })
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, null, h(FlowDefaultsPanel)))
  })
}

function modelSelect(): HTMLSelectElement {
  return container.querySelector('select[data-label="editor.properties.fields.model"]') as HTMLSelectElement
}

function optionText(value: string): string {
  return Array.from(modelSelect().options).find(o => o.value === value)?.textContent ?? ''
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
  providers = catalogo(true, ['openai/gpt-6-luna', 'google/gemma-4-31b-it:free'])
})

afterEach(() => {
  act(() => { root?.unmount() })
  root = null
  container?.remove()
  useWorkflowStore.setState({ currentWorkflow: null })
})

describe('FlowDefaultsPanel — modelo gravado fora da lista', () => {
  it('sem restrição, um padrão fora do catálogo aparece como está, sem aviso', async () => {
    await render('anthropic/claude-sonnet-4-20250514')

    expect(modelSelect().value).toBe('anthropic/claude-sonnet-4-20250514')
    expect(optionText('anthropic/claude-sonnet-4-20250514')).toBe('anthropic/claude-sonnet-4-20250514')
    expect(container.textContent).not.toContain('modelNotAllowedHint')
  })

  it('tenant restrito: o padrão fora dos permitidos aparece marcado, com aviso de que vai falhar', async () => {
    providers = catalogo(false, ['google/gemma-4-31b-it:free'])
    await render('openai/gpt-6-luna')

    expect(modelSelect().value).toBe('openai/gpt-6-luna')
    expect(optionText('openai/gpt-6-luna')).toBe('editor.properties.fields.modelNotAllowed:openai/gpt-6-luna')
    expect(container.textContent).toContain('editor.properties.fields.modelNotAllowedHint')
  })

  it('um padrão que está na lista não vira opção repetida nem avisa', async () => {
    providers = catalogo(false, ['google/gemma-4-31b-it:free'])
    await render('google/gemma-4-31b-it:free')

    expect(Array.from(modelSelect().options).filter(o => o.value === 'google/gemma-4-31b-it:free')).toHaveLength(1)
    expect(container.textContent).not.toContain('modelNotAllowedHint')
  })
})

/**
 * RCHFLW-182: o campo Modelo oferecia os modelos de todos os providers num grupo só e gravava só o id. Escolher
 * "Claude 3 Haiku" gravava claude-3-haiku-20240307, que os nós (que herdam o OpenRouter) mandavam ao OpenRouter —
 * "not a valid model ID". Agora só os modelos do provider herdado: o do fluxo, senão o da plataforma.
 */
describe('FlowDefaultsPanel — só os modelos do provider que os nós herdam (RCHFLW-182)', () => {
  const model = (id: string) => ({ id, name: id, contextWindow: 0, maxTemperature: 0 })
  const dois = (): ProviderInfo[] => [
    {
      id: 'openrouter', displayName: 'OpenRouter', group: 'Cloud', requiresApiKey: true, supportsStreaming: true,
      models: [model('openai/gpt-4o'), model('anthropic/claude-sonnet-4')], allowCustomModel: true,
      platformDefault: true,
    },
    {
      id: 'anthropic', displayName: 'Anthropic', group: 'Cloud', requiresApiKey: true, supportsStreaming: true,
      models: [model('claude-3-haiku-20240307')], allowCustomModel: true,
    },
  ]

  async function renderCom(llmConfig: Record<string, unknown>) {
    useWorkflowStore.setState({ currentWorkflow: { configuration: { llmConfig } } as any })
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    await act(async () => {
      root!.render(h(MantineProvider, null, h(FlowDefaultsPanel)))
    })
  }
  const values = () => Array.from(modelSelect().options).map(o => o.value)

  it('sem provider no fluxo: os modelos do provider da plataforma, e não os da Anthropic', async () => {
    providers = dois()
    await renderCom({})

    expect(values()).toEqual(['openai/gpt-4o', 'anthropic/claude-sonnet-4'])
    expect(container.querySelector('[data-testid="flow-model-provider-hint"]')?.textContent)
      .toBe('editor.properties.flowDefaults.modelProviderHint')
  })

  it('com provider no fluxo: os modelos dele', async () => {
    providers = dois()
    await renderCom({ provider: 'anthropic' })

    expect(values()).toEqual(['claude-3-haiku-20240307'])
  })

  it('padrão já gravado com o id de outro provider: aparece como está, com o aviso de que os nós vão mandá-lo ao herdado', async () => {
    providers = dois()
    await renderCom({ model: 'claude-3-haiku-20240307' })

    expect(modelSelect().value).toBe('claude-3-haiku-20240307')
    expect(container.querySelector('[data-testid="flow-model-other-provider"]')?.textContent)
      .toBe('editor.properties.flowDefaults.modelOtherProvider:claude-3-haiku-20240307')
  })

  it('RCHFLW-188 (23): temperatura, tokens e timeout não gravados aparecem vazios, não com 0.2 / 1024 / 30', async () => {
    providers = dois()
    await renderCom({ model: 'openai/gpt-4o' })

    // Os três campos numéricos (o Modelo é um <select> no mock).
    const numeros = Array.from(container.querySelectorAll('input'))
    expect(numeros).toHaveLength(3)
    for (const input of numeros) expect(input.value).toBe('')
    expect(container.textContent).not.toMatch(/\b(0\.2|1024)\b/)
    // RCHFLW-188 (22): o timeout diz que só vale para os agentes.
    expect(container.textContent).toContain('editor.properties.flowDefaults.timeoutAgentsOnly')
  })

  it('o que está gravado aparece', async () => {
    providers = dois()
    await renderCom({ temperature: 0.7, maxTokens: 2048, timeout: 12 })

    const valores = Array.from(container.querySelectorAll('input')).map(i => i.value)
    expect(valores).toEqual(expect.arrayContaining(['0.7', '2048', '12']))
  })
})
