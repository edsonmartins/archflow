/**
 * RCHFLW-74: no editor só leitura (VIEWER, EXECUTOR) o painel de propriedades só mostra.
 *
 * <p>O canvas só leitura ainda seleciona o nó — o React Flow chama onNodeClick mesmo com
 * elementsSelectable=false — e o painel abria editável: o nome e os campos mudavam o canvas sem nada
 * que gravasse, e o Executar do EXECUTOR rodava a versão gravada, descartando o que ele acabara de
 * mudar. O mesmo valia para os "Padrões do fluxo" (sem nó selecionado). Copiar o id do nó continua: não
 * edita nada.</p>
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { within } from '@testing-library/dom'
import type { Edge, Node } from '@xyflow/react'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string) => k,
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))
// Os hooks do painel buscam o catálogo e a configuração no backend; aqui só importa o travamento.
vi.mock('../useWorkflowConfig', () => ({
  useWorkflowConfig: () => ({ providers: [], patterns: [], personas: [], governance: [], mcpServers: [], loading: false }),
}))
vi.mock('../useCatalog', () => ({
  useCatalog: () => ({ agents: [], assistants: [], tools: [], usingFallback: false }),
}))

import { PropertyPanel } from '../../PropertyPanel'
import { useFlowStore } from '../../FlowCanvas/store/useFlowStore'
import { useWorkflowStore } from '../../../stores/workflow-store'
import type { FlowNodeData } from '../../FlowCanvas/types'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

function node(id: string, nodeType: string, label: string): Node<FlowNodeData> {
  return { id, position: { x: 0, y: 0 }, data: { label, componentId: nodeType, nodeType, status: 'idle', config: {} } }
}

function edge(source: string, target: string): Edge {
  return { id: `${source}-${target}`, source, target }
}

let root: Root | null = null
let container: HTMLDivElement

async function renderPanel(nodeId: string | null, readOnly: boolean) {
  const data = nodeId ? useFlowStore.getState().nodes.find(n => n.id === nodeId)!.data : null
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, null, h(PropertyPanel, { nodeId, nodeData: data, readOnly })))
  })
  return within(container)
}

/** Os controles de edição do painel (inputs, textareas, botões), menos o copiar do id do nó. */
function controlesDeEdicao(): Element[] {
  const copiar = container.querySelector('[data-testid="property-panel-node-id-field"]')
  return Array.from(container.querySelectorAll('input, textarea, select, button'))
    .filter(el => !copiar?.contains(el))
    // As abas (Propriedades/Logs/Saída) só trocam a vista.
    .filter(el => el.getAttribute('role') !== 'tab')
}

describe('PropertyPanel — só leitura (RCHFLW-74)', () => {
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
    useFlowStore.setState({
      nodes: [node('node-1', 'decision', 'triagem'), node('node-2', 'condition', 'confere')],
      edges: [edge('node-1', 'node-2')],
    })
    useWorkflowStore.setState({ currentWorkflow: { configuration: { llmConfig: {} } } as any })
  })

  afterEach(async () => {
    await act(async () => { root?.unmount() })
    root = null
    container?.remove()
  })

  it('nó selecionado: nome, campos do tipo e referências travados; copiar o id continua', async () => {
    const ui = await renderPanel('node-2', true)
    const controles = controlesDeEdicao()
    // nome + expressão + a referência ao nó anterior, no mínimo
    expect(controles.length).toBeGreaterThanOrEqual(3)
    expect(ui.getAllByTestId('upstream-ref').length).toBeGreaterThan(0)
    for (const el of controles) {
      expect(el.matches(':disabled'), el.outerHTML.slice(0, 120)).toBe(true)
    }
    const copiar = within(ui.getByTestId('property-panel-node-id-field')).getByRole('button')
    expect(copiar.matches(':disabled')).toBe(false)
  })

  it('sem nó selecionado: os padrões do fluxo (modelo, temperatura, tokens, timeout) travados', async () => {
    await renderPanel(null, true)
    const controles = controlesDeEdicao()
    expect(controles.length).toBeGreaterThanOrEqual(4)
    for (const el of controles) {
      expect(el.matches(':disabled'), el.outerHTML.slice(0, 120)).toBe(true)
    }
  })

  it('com workflow:update tudo continua editável', async () => {
    await renderPanel('node-2', false)
    for (const el of controlesDeEdicao()) {
      expect(el.matches(':disabled'), el.outerHTML.slice(0, 120)).toBe(false)
    }
    await act(async () => { root?.unmount() })
    container.remove()
    await renderPanel(null, false)
    for (const el of controlesDeEdicao()) {
      expect(el.matches(':disabled'), el.outerHTML.slice(0, 120)).toBe(false)
    }
  })
})
