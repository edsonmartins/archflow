/**
 * RCHFLW-183: escolher uma Persona no LLM Chat gravava o texto dela (que no servidor é só o nome, "Customer Support")
 * no prompt de sistema, por cima do prompt do autor, e travava o campo; voltar para "Personalizado" reabilitava o
 * campo com "Customer Support" — o prompt original tinha se perdido, e o modelo respondia de forma genérica.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import type { PersonaInfo } from '../../../services/workflow-config-api'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string) => k,
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))

type Item = { value: string; label: string }
type Group = { group: string; items: Item[] }

// O Select do Mantine não abre no jsdom: um <select> nativo com as mesmas opções e o mesmo onChange.
vi.mock('@mantine/core', async (importOriginal) => {
  const real = await importOriginal<typeof import('@mantine/core')>()
  const { createElement } = await import('react')
  const Select = (props: {
    label?: string; value?: string | null; data?: (Item | Group | string)[]; onChange?: (v: string | null) => void
    description?: string
  }) => {
    const items = (props.data ?? []).flatMap(d => (typeof d === 'string' ? [{ value: d, label: d }]
      : 'group' in d ? d.items : [d]))
    return createElement('div', null, createElement('select', {
      'data-label': props.label, value: props.value ?? '',
      onChange: (e: { target: { value: string } }) => props.onChange?.(e.target.value),
    }, items.map(i => createElement('option', { key: i.value, value: i.value }, i.label))), props.description)
  }
  return { ...real, Select }
})

const PERSONAS: PersonaInfo[] = [
  // Como o servidor manda hoje (Persona.of: description = label).
  { id: 'customer_support', label: 'Customer Support', description: 'Customer Support', promptId: 'prompts/cs' },
  // Uma persona com prompt de verdade (deployment que as cadastra com texto).
  { id: 'revisor', label: 'Revisor', description: 'Você revisa textos com rigor.', promptId: 'prompts/rev' },
]

vi.mock('../useWorkflowConfig', () => ({
  useWorkflowConfig: () => ({
    providers: [], patterns: [], personas: PERSONAS, governance: [], mcpServers: [], loading: false,
  }),
}))
vi.mock('../useChatProviders', () => ({ useChatProviders: () => null }))
vi.mock('../useCatalog', () => ({
  useCatalog: () => ({ agents: [], assistants: [], tools: [], usingFallback: false }),
}))

import { AgentFields } from '../fields/AgentFields'
import { personaPrompt, systemPromptAfterPersona } from '../fields/helpers'
import type { FlowNodeData } from '../../FlowCanvas/types'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const AUTOR = 'Você é atendente de reembolso de uma loja. Responda em uma frase curta.'

let root: Root | null = null
let container: HTMLDivElement
let config: Record<string, unknown> = {}

function renderNode() {
  const nodeData: FlowNodeData = { label: 'Atendente 1', componentId: 'llm-chat', nodeType: 'llm-chat', status: 'idle', config }
  const update = (key: string, value: unknown) => {
    config = { ...config, [key]: value }
    if (value === undefined) delete config[key]
    renderNode()
  }
  act(() => {
    root!.render(h(MantineProvider, null, h(AgentFields, { nodeData, update, isAssistant: false })))
  })
}

function personaSelect() {
  return container.querySelector('select[data-label="editor.properties.fields.persona"]') as HTMLSelectElement
}

function prompt() {
  return container.querySelector('textarea[placeholder="editor.properties.fields.systemPromptPlaceholder"]') as
    HTMLTextAreaElement
}

function choose(value: string) {
  act(() => {
    const select = personaSelect()
    select.value = value
    select.dispatchEvent(new Event('change', { bubbles: true }))
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
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
})

afterEach(() => {
  act(() => { root?.unmount() })
  root = null
  container?.remove()
})

describe('AgentFields — Persona não destrói o prompt de sistema (RCHFLW-183)', () => {
  it('o caminho do ticket: escolher a persona, voltar para Personalizado e escolher de novo mantém o prompt do autor', () => {
    config = { systemPrompt: AUTOR }
    renderNode()

    choose('customer_support')
    expect(config).toEqual({ systemPrompt: AUTOR, personaId: 'customer_support' })
    expect(prompt().value).toBe(AUTOR)
    expect(prompt().disabled).toBe(false)
    // A persona sem prompt próprio diz isso, em vez de fingir que mudou alguma coisa.
    expect(container.textContent).toContain('editor.properties.fields.personaNoPrompt')

    choose('__custom__')
    choose('customer_support')
    expect(config.systemPrompt).toBe(AUTOR)
  })

  it('persona com prompt próprio preenche o campo vazio, e voltar para Personalizado o esvazia de novo', () => {
    config = {}
    renderNode()

    choose('revisor')
    expect(config.systemPrompt).toBe('Você revisa textos com rigor.')
    expect(prompt().disabled).toBe(false)

    choose('__custom__')
    expect(config).not.toHaveProperty('systemPrompt')
  })
})

describe('systemPromptAfterPersona', () => {
  const [cs, rev] = PERSONAS
  const outra: PersonaInfo = { id: 'o', label: 'Outra', description: 'Você é outra.', promptId: '' }

  it('o nome não é prompt', () => {
    expect(personaPrompt(cs)).toBe('')
    expect(personaPrompt(rev)).toBe('Você revisa textos com rigor.')
  })

  it('o prompt do autor nunca é tocado', () => {
    expect(systemPromptAfterPersona(AUTOR, undefined, rev)).toBeUndefined()
    expect(systemPromptAfterPersona(AUTOR, rev, undefined)).toBeUndefined()
    expect(systemPromptAfterPersona(AUTOR, cs, rev)).toBeUndefined()
  })

  it('o texto que a persona anterior pôs, sem edição, troca pelo da nova ou sai', () => {
    expect(systemPromptAfterPersona(rev.description, rev, outra)).toBe('Você é outra.')
    expect(systemPromptAfterPersona(rev.description, rev, cs)).toBe('')
    expect(systemPromptAfterPersona(`${rev.description} E seja breve.`, rev, undefined)).toBeUndefined()
  })
})
