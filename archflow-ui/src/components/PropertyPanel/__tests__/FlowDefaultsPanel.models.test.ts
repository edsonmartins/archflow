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
