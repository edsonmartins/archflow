/**
 * O provedor `rules` precisa de onde escrever as regras no painel da Decisão.
 *
 * <p>O motor entrega ao provedor `providerOptions.<id>` e o `RulesDecisionProvider` lê dali `rules`
 * — sem o campo, escolher `rules` no painel dava sempre "sem regras para a pergunta". Aqui: o campo
 * só aparece para `rules`, grava em providerOptions.rules.rules e preserva as outras chaves.</p>
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { fireEvent, within } from '@testing-library/dom'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string) => k,
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))

import { DecisionFields } from '../fields/DecisionFields'
import type { FlowNodeData } from '../../FlowCanvas/types'
import ptBR from '../../../i18n/locales/pt-BR.json'
import en from '../../../i18n/locales/en.json'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const RULES_LABEL = 'editor.properties.fields.decisionRules'

let root: Root | null = null
let container: HTMLDivElement

function data(config: Record<string, unknown>): FlowNodeData {
  return { label: 'Triagem', componentId: 'decision', nodeType: 'decision', status: 'idle', config } as FlowNodeData
}

async function render(config: Record<string, unknown>, update = vi.fn()) {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, null, h(DecisionFields, { nodeData: data(config), update })))
  })
  return { view: within(container), update }
}

describe('DecisionFields — regras do provedor rules', () => {
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

  afterEach(async () => {
    await act(async () => { root?.unmount() })
    root = null
    container?.remove()
  })

  it('grava em providerOptions.rules.rules preservando as outras opções', async () => {
    const { view, update } = await render({
      provider: 'rules',
      providerOptions: { rules: { extra: 1 }, llm: { maxConfidence: 0.8 } },
    })
    const field = view.getByLabelText(RULES_LABEL) as HTMLTextAreaElement
    const regras = { time: { match: { pagamento: ['boleto', 're:pix|cobran'] }, otherwise: 'conta' } }

    await act(async () => {
      fireEvent.change(field, { target: { value: JSON.stringify(regras) } })
    })

    expect(update).toHaveBeenLastCalledWith('providerOptions', {
      rules: { extra: 1, rules: regras },
      llm: { maxConfidence: 0.8 },
    })
  })

  it('mostra as regras já gravadas', async () => {
    const regras = { e_bug: { matchAny: ['erro'] } }
    const { view } = await render({ provider: 'rules', providerOptions: { rules: { rules: regras } } })
    const field = view.getByLabelText(RULES_LABEL) as HTMLTextAreaElement
    expect(JSON.parse(field.value)).toEqual(regras)
  })

  it('JSON inválido não grava', async () => {
    const { view, update } = await render({ provider: 'rules' })
    const field = view.getByLabelText(RULES_LABEL) as HTMLTextAreaElement
    await act(async () => {
      fireEvent.change(field, { target: { value: '{ "time": ' } })
    })
    expect(update).not.toHaveBeenCalledWith('providerOptions', expect.anything())
  })

  it('esvaziar o campo remove a chave sem apagar as outras opções', async () => {
    const { view, update } = await render({
      provider: 'rules',
      providerOptions: { rules: { rules: { a: { matchAny: ['x'] } }, extra: 1 }, llm: { maxConfidence: 0.8 } },
    })
    const field = view.getByLabelText(RULES_LABEL) as HTMLTextAreaElement
    await act(async () => {
      fireEvent.change(field, { target: { value: '' } })
    })
    expect(update).toHaveBeenLastCalledWith('providerOptions', {
      rules: { extra: 1 },
      llm: { maxConfidence: 0.8 },
    })
  })

  it('esvaziar o campo não deixa `rules: {}` no documento', async () => {
    const { view, update } = await render({
      provider: 'rules',
      providerOptions: { rules: { rules: { a: { matchAny: ['x'] } } }, llm: { maxConfidence: 0.8 } },
    })
    const field = view.getByLabelText(RULES_LABEL) as HTMLTextAreaElement
    await act(async () => {
      fireEvent.change(field, { target: { value: '' } })
    })
    expect(update).toHaveBeenLastCalledWith('providerOptions', { llm: { maxConfidence: 0.8 } })
  })

  // O score do RulesDecisionProvider casa por índice do nível ("0", "1", ...) e só aceita `otherwise`
  // numérico; uma dica que o ensinasse como o choice levaria a "nenhuma regra casou" em toda execução.
  it.each([['pt-BR', ptBR], ['en', en]])('a dica (%s) ensina o score por índice com otherwise numérico', (_, loc) => {
    const hint = (loc as any).editor.properties.fields.decisionRulesHint as string
    expect(hint).toMatch(/score:[^]*"match": \{ "2": \[[^]*"otherwise": 0 \}/)
    expect(hint).toMatch(/choice: \{[^]*"otherwise": "<op/)
  })

  it.each(['http-decisions', 'llm', undefined])('fica oculto para o provedor %s', async provider => {
    const { view } = await render(provider ? { provider } : {})
    expect(view.queryByLabelText(RULES_LABEL)).toBeNull()
  })
})
