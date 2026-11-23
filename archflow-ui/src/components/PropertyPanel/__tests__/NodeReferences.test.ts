/**
 * O id do nó tem de aparecer no painel — é por ele, não pelo nome, que o motor grava a saída.
 *
 * <p>Antes o painel só editava o rótulo; quem escrevia `${triagem.route}` (o nome) via o switch
 * cair em `default` sem erro. Aqui: o id aparece só leitura com botão de copiar e a dica
 * `${<id>.campo}`, e o switch/condition oferece as referências dos nós anteriores.</p>
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'
import type { Edge, Node } from '@xyflow/react'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string) => k,
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))
// Os hooks do painel buscam o catálogo e a configuração no backend; aqui só importa o layout.
vi.mock('../useWorkflowConfig', () => ({
  useWorkflowConfig: () => ({ providers: [], patterns: [], personas: [], governance: [], mcpServers: [], loading: false }),
}))
vi.mock('../useCatalog', () => ({
  useCatalog: () => ({ agents: [], assistants: [], tools: [], usingFallback: false }),
}))

import { PropertyPanel } from '../../PropertyPanel'
import { useFlowStore } from '../../FlowCanvas/store/useFlowStore'
import type { FlowNodeData } from '../../FlowCanvas/types'
import { upstreamRefs } from '../fields/nodeRefs'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

function node(id: string, nodeType: string, label: string, config: Record<string, unknown> = {}): Node<FlowNodeData> {
  return {
    id,
    position: { x: 0, y: 0 },
    data: { label, componentId: nodeType, nodeType, status: 'idle', config },
  }
}

function edge(source: string, target: string): Edge {
  return { id: `${source}-${target}`, source, target }
}

let root: Root | null = null
let container: HTMLDivElement

async function renderPanel(nodeId: string) {
  const data = useFlowStore.getState().nodes.find(n => n.id === nodeId)!.data
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, null, h(PropertyPanel, { nodeId, nodeData: data })))
  })
  return within(container)
}

function configOf(nodeId: string) {
  return useFlowStore.getState().nodes.find(n => n.id === nodeId)!.data.config
}

describe('PropertyPanel — id do nó e referências', () => {
  const writeText = vi.fn(async () => {})

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
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    writeText.mockClear()
    useFlowStore.setState({
      nodes: [
        node('node-1712345678901', 'decision', 'triagem'),
        node('node-1712345678902', 'switch', 'rota'),
        node('node-1712345678903', 'condition', 'confere', { conditionExpression: "${x} == 1 &&" }),
      ],
      edges: [edge('node-1712345678901', 'node-1712345678902'), edge('node-1712345678902', 'node-1712345678903')],
    })
  })

  afterEach(async () => {
    await act(async () => { root?.unmount() })
    root = null
    container?.remove()
  })

  it('mostra o id só leitura, com a dica ${id.campo} e o botão de copiar', async () => {
    const ui = await renderPanel('node-1712345678902')

    expect(ui.getByTestId('property-panel-node-id').textContent).toBe('node-1712345678902')
    expect(ui.getByTestId('property-panel-node-id-hint').textContent).toBe('${node-1712345678902.campo}')
    // Não é um input: o id não se edita (as arestas e as expressões dependem dele).
    expect(ui.getByTestId('property-panel-node-id').tagName).toBe('CODE')

    const field = within(ui.getByTestId('property-panel-node-id-field'))
    await act(async () => { fireEvent.click(field.getByRole('button')) })
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('node-1712345678902'))
  })

  it('switch: lista os nós anteriores e a referência clicada substitui a expressão', async () => {
    const ui = await renderPanel('node-1712345678902')

    const refs = ui.getAllByTestId('upstream-ref')
    expect(refs.map(r => r.textContent)).toEqual(['triagem →${node-1712345678901.route}'])

    await act(async () => { fireEvent.click(refs[0]) })
    expect(configOf('node-1712345678902').switchExpression).toBe('${node-1712345678901.route}')
  })

  it('condition: a referência entra no fim da expressão já escrita', async () => {
    const ui = await renderPanel('node-1712345678903')

    const refs = ui.getAllByTestId('upstream-ref')
    // O mais próximo primeiro; o switch expõe `branch`, a decisão `route`.
    expect(refs.map(r => r.textContent)).toEqual([
      'rota →${node-1712345678902.branch}',
      'triagem →${node-1712345678901.route}',
    ])

    await act(async () => { fireEvent.click(refs[1]) })
    expect(configOf('node-1712345678903').conditionExpression).toBe("${x} == 1 && ${node-1712345678901.route}")
  })

  it('nó sem anteriores não mostra o bloco de referências', async () => {
    const ui = await renderPanel('node-1712345678901')
    expect(ui.queryByTestId('property-panel-upstream-refs')).toBeNull()
    expect(ui.getByTestId('property-panel-node-id').textContent).toBe('node-1712345678901')
  })
})

describe('upstreamRefs', () => {
  it('nó comum referencia a saída inteira; ciclos não travam a busca', () => {
    const nodes = [node('a', 'llm-chat', ''), node('b', 'switch', 'b')]
    const refs = upstreamRefs('b', nodes, [edge('a', 'b'), edge('b', 'a')])
    expect(refs).toEqual([{ id: 'a', label: 'a', snippet: '${a}' }])
  })
})
