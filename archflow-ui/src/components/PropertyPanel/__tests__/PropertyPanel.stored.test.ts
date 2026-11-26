/**
 * RCHFLW-92 — o painel de propriedades só mostra o que está gravado no nó.
 *
 * <p>Os campos usavam getConfig(nó, chave, exemplo) só para exibir: a Condição aparecia com
 * `${agent.confidence} > 0.8`, gravava `{}` e a execução falhava com "'conditionExpression' é obrigatória"; a
 * Decisão mostrava um provedor escolhido sem nada no nó. Agora o campo vazio é vazio, o exemplo vai no
 * placeholder ("ex.: …") e "padrão: X" só aparece com o valor que o motor usa de fato.</p>
 *
 * <p>Também RCHFLW-11 (aviso de tipo sem execução, Entrada/Saída como passagem) e RCHFLW-57 (llm-streaming roda
 * como o LLM Chat).</p>
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { fireEvent, within } from '@testing-library/dom'

// O t devolve a chave e, quando há, o valor interpolado — é o que deixa conferir o texto do placeholder.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, o?: Record<string, unknown>) => (o && 'value' in o ? `${k}:${String(o.value)}` : k),
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))
vi.mock('../useWorkflowConfig', () => ({
  useWorkflowConfig: () => ({
    providers: [{ id: 'openai', displayName: 'OpenAI', group: 'Cloud', models: [] }],
    patterns: [{ id: 'react', label: 'ReAct', description: 'raciocina e age' }],
    personas: [], governance: [], mcpServers: [], loading: false,
  }),
}))
vi.mock('../useChatProviders', () => ({ useChatProviders: () => null }))
vi.mock('../useCatalog', () => ({
  useCatalog: () => ({
    agents: [{ id: 'research-agent', displayName: 'Research Agent', kind: 'agent', description: '' }],
    assistants: [], tools: [], embeddings: [], memories: [], vectorstores: [], chains: [],
    usingFallback: false,
  }),
}))

import { PropertyPanel } from '../../PropertyPanel'
import { DecisionFields } from '../fields/DecisionFields'
import type { FlowNodeData } from '../../FlowCanvas/types'
import ptBR from '../../../i18n/locales/pt-BR.json'
import en from '../../../i18n/locales/en.json'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

let root: Root | null = null
let container: HTMLDivElement

function data(nodeType: string, config: Record<string, unknown> = {}): FlowNodeData {
  return { label: 'Nó', componentId: nodeType, nodeType, status: 'idle', config } as FlowNodeData
}

async function mount(element: ReturnType<typeof h>) {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => { root!.render(h(MantineProvider, null, element)) })
  return within(container)
}

const panel = (nodeData: FlowNodeData) => mount(h(PropertyPanel, { nodeId: 'n1', nodeData }))

/** O campo pelo rótulo — o asterisco do obrigatório fica fora da comparação. */
function field(view: ReturnType<typeof within>, key: string): HTMLInputElement {
  return view.getByLabelText(new RegExp(`^editor\\.properties\\.fields\\.${key}(\\s*\\*)?$`)) as HTMLInputElement
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

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

describe('PropertyPanel — só o que está gravado (RCHFLW-92)', () => {
  it('Condição nova: expressão vazia, exemplo no placeholder e marcada como obrigatória', async () => {
    const view = await panel(data('condition'))

    const expr = field(view, 'expression')
    expect(expr.value).toBe('')
    expect(expr.placeholder).toBe("editor.properties.fields.exampleValue:${<nodeId>.route} == 'AUTO'")
    expect(expr.required || container.textContent!.includes('*')).toBe(true)
  })

  it('Condição gravada mostra a expressão gravada', async () => {
    const view = await panel(data('condition', { conditionExpression: "${triagem.route} == 'AUTO'" }))

    expect(field(view, 'expression').value).toBe("${triagem.route} == 'AUTO'")
  })

  it('Switch novo: expressão e casos vazios, com os exemplos no placeholder', async () => {
    const view = await panel(data('switch'))

    expect(field(view, 'switchExpression').value).toBe('')
    expect(field(view, 'switchExpression').placeholder).toBe('editor.properties.fields.exampleValue:${<nodeId>.decision}')
    expect(field(view, 'switchCases').value).toBe('')
    expect(field(view, 'switchCases').placeholder).toContain('order_tracking')
  })

  it('Orquestrar: vazio, com o padrão real do DynamicWorkflowService no placeholder', async () => {
    const view = await panel(data('orchestrate'))

    const subtasks = view.getByLabelText('dynamicWorkflow.maxSubtasks') as HTMLInputElement
    expect(subtasks.value).toBe('')
    expect(subtasks.placeholder).toBe('editor.properties.fields.defaultValue:8')
    expect((view.getByLabelText('dynamicWorkflow.concurrency') as HTMLInputElement).placeholder)
      .toBe('editor.properties.fields.defaultValue:4')
  })

  it('nó sem execução não mostra valor fingido: Paralelo com fail-fast desligado e sem estratégia', async () => {
    const view = await panel(data('parallel'))

    expect((view.getByLabelText('editor.properties.fields.failFast') as HTMLInputElement).checked).toBe(false)
    expect(field(view, 'waitStrategy').value).toBe('')
    expect(field(view, 'timeoutSec').placeholder).toBe('editor.properties.fields.exampleValue:60')
  })

  it('Agente: tipo e estratégia vazios, sem escolher o primeiro do catálogo', async () => {
    const view = await panel(data('agent'))

    expect(field(view, 'agentType').value).toBe('')
    expect(field(view, 'executionStrategy').value).toBe('')
  })
})

describe('PropertyPanel — avisos de execução', () => {
  it('tipo sem execução (RCHFLW-11) avisa no painel; LLM Chat não', async () => {
    const view = await panel(data('agent'))
    expect(view.getByTestId('panel-not-executable').textContent).toBe('editor.properties.fields.notExecutableHint')

    await act(async () => { root?.unmount() }); container.remove()
    const chat = await panel(data('llm-chat'))
    expect(chat.queryByTestId('panel-not-executable')).toBeNull()
    expect(chat.queryByTestId('panel-llm-streaming-alias')).toBeNull()
  })

  it('llm-streaming (RCHFLW-57) diz que executa como o LLM Chat', async () => {
    const view = await panel(data('llm-streaming'))

    expect(view.getByTestId('panel-llm-streaming-alias').textContent).toBe('editor.properties.fields.llmStreamingAlias')
    expect(view.queryByTestId('panel-not-executable')).toBeNull()
  })

  it('Entrada diz que repassa a entrada e não liga "rejeitar se ausente" sem estar gravado', async () => {
    const view = await panel(data('input'))

    expect(view.getByTestId('io-pass-through-hint').textContent).toBe('editor.properties.fields.inputPassThroughHint')
    expect((view.getByLabelText('editor.properties.fields.rejectIfMissing') as HTMLInputElement).checked).toBe(false)
  })
})

describe('DecisionFields — padrões reais do motor (RCHFLW-92)', () => {
  it('provedor, limiares, onError e acceptAt vazios com o padrão do DecisionComponent no placeholder', async () => {
    const view = await mount(h(DecisionFields, { nodeData: data('decision'), update: vi.fn() }))

    expect(field(view, 'decisionProvider').value).toBe('')
    expect(field(view, 'decisionProvider').placeholder).toBe('editor.properties.fields.defaultValue:http-decisions')
    expect(field(view, 'thresholdAuto').placeholder).toBe('editor.properties.fields.defaultValue:0.9')
    expect(field(view, 'thresholdReview').placeholder).toBe('editor.properties.fields.defaultValue:0.5')
    expect(field(view, 'decisionOnError').placeholder).toBe('editor.properties.fields.defaultValue:ESCALATE')
    expect(field(view, 'decisionAcceptAt').placeholder).toBe('editor.properties.fields.defaultValue:0.9')
    expect(field(view, 'decisionOnDisagree').placeholder).toBe('editor.properties.fields.defaultValue:REVIEW')
  })

  it('acceptAt herda o limiar auto gravado, como no motor', async () => {
    const view = await mount(h(DecisionFields, { nodeData: data('decision', { thresholds: { auto: 0.8 } }), update: vi.fn() }))

    expect(field(view, 'thresholdAuto').value).toBe('0.8')
    expect(field(view, 'decisionAcceptAt').placeholder).toBe('editor.properties.fields.defaultValue:0.8')
  })

  it('apagar o limiar tira a chave, em vez de gravar 0', async () => {
    const update = vi.fn()
    const view = await mount(h(DecisionFields, {
      nodeData: data('decision', { thresholds: { auto: 0.8, review: 0.4 } }), update,
    }))

    await act(async () => { fireEvent.change(field(view, 'thresholdAuto'), { target: { value: '' } }) })

    expect(update).toHaveBeenCalledWith('thresholds', { review: 0.4 })
  })
})

describe('i18n', () => {
  it('as chaves novas existem em pt-BR e en', () => {
    const fields = ['exampleValue', 'defaultValue', 'notExecutableHint', 'llmStreamingAlias',
      'inputPassThroughHint', 'outputPassThroughHint']
    for (const locale of [ptBR, en] as any[]) {
      for (const key of fields) expect(locale.editor.properties.fields[key]).toBeTruthy()
      expect(locale.editor.palette.notExecutable).toBeTruthy()
      expect(locale.editor.notif.runBlocked).toBeTruthy()
      expect(locale.editor.notif.runBlockedNotExecutable).toContain('{{nodes}}')
      expect(locale.editor.notif.runBlockedMissing).toContain('{{fields}}')
    }
    expect(ptBR.editor.properties.fields.defaultValue).toBe('padrão: {{value}}')
  })
})
