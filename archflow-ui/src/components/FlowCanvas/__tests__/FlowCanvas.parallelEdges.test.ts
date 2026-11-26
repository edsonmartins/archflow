/**
 * RCHFLW-149 / RCHFLW-155 — o connectNodes do canvas devolvia true para a aresta que o addEdge do React Flow
 * descartava: a 2ª aresta do mesmo par origem/destino, porque o ramo não entra na comparação (um handle de saída só).
 * Um switch com {@code media} e {@code baixa} para a mesma Resposta Automática ficava com uma aresta, e o
 * {@code default} (onde cai a decisão que falhou) não chegava à aprovação que o {@code alta} já usava.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { waitFor } from '@testing-library/dom'
import type { Edge } from '@xyflow/react'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string) => k,
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))
const notificationsMock = vi.hoisted(() => ({ show: vi.fn() }))
vi.mock('@mantine/notifications', () => ({ notifications: notificationsMock }))
// O onConnect que o canvas entrega ao React Flow — é ele que o arrastar à mão chama.
const reactFlowProps = vi.hoisted(() => ({ current: null as null | { onConnect?: (c: unknown) => void } }))
vi.mock('@xyflow/react', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@xyflow/react')>()
  const { createElement } = await import('react')
  return {
    ...actual,
    ReactFlow: (props: Record<string, unknown>) => {
      reactFlowProps.current = props as { onConnect?: (c: unknown) => void }
      return createElement(actual.ReactFlow as any, props)
    },
  }
})

import { FlowCanvas } from '../FlowCanvas'
import { useFlowStore } from '../store/useFlowStore'
import { conflictingEdge, withEdge } from '../parallelEdges'
import type { WorkflowData } from '../types'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

let root: Root | null = null
let container: HTMLDivElement

const step = (id: string, componentId: string, label: string, config: Record<string, unknown> = {}) => ({
  id, type: componentId, componentId, label, category: 'control', position: { x: 0, y: 0 }, config,
})

// O fluxo do ticket (wf-07f28a28): switch de urgência, aprovação do coordenador e resposta automática.
const WORKFLOW: WorkflowData = {
  steps: [
    step('sw', 'switch', 'Urgência', { switchExpression: '${d.decision}', switchCases: 'alta\nmedia\nbaixa' }),
    step('appr', 'approval', 'Aprovação Coordenador'),
    step('auto', 'llm-chat', 'Resposta Automática'),
  ] as WorkflowData['steps'],
  connections: [],
}

beforeEach(() => {
  ;(globalThis as any).ResizeObserver = ResizeObserverStub
  ;(globalThis as any).DOMMatrixReadOnly ??= class {
    m22 = 1
    constructor() {}
  }
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  useFlowStore.setState({ nodes: [], edges: [], canvasApi: null, pendingCanvasOps: [], pendingCanvasTarget: null })
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function renderCanvas() {
  container = document.createElement('div')
  container.style.width = '1000px'
  container.style.height = '600px'
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, null, h(FlowCanvas, { initialWorkflow: WORKFLOW, workflowId: 'wf-1' })))
  })
  await waitFor(() => expect(useFlowStore.getState().canvasApi).not.toBeNull())
}

const branches = () => useFlowStore.getState().getCanvasSnapshot().connections
  .map(c => [c.sourceId, c.targetId, c.branch])

describe('FlowCanvas.connectNodes — ramos para o mesmo destino (RCHFLW-149)', () => {
  it('cada caso do switch ganha a sua aresta, também quando o destino se repete', async () => {
    await renderCanvas()
    const api = useFlowStore.getState().canvasApi!
    const results: boolean[] = []
    await act(async () => {
      results.push(api.connectNodes('sw', 'appr', 'alta'))
      results.push(api.connectNodes('sw', 'appr', 'default'))
      results.push(api.connectNodes('sw', 'auto', 'media'))
      results.push(api.connectNodes('sw', 'auto', 'baixa'))
    })

    expect(results).toEqual([true, true, true, true])
    // O documento salvo (steps[].connections sai daqui) tem as quatro — o motor segue cada uma.
    await waitFor(() => expect(branches()).toEqual([
      ['sw', 'appr', 'alta'], ['sw', 'appr', 'default'], ['sw', 'auto', 'media'], ['sw', 'auto', 'baixa'],
    ]))
    const ids = useFlowStore.getState().edges.map(e => e.id)
    expect(new Set(ids).size).toBe(4)
  })

  it('a ligação que já existe não é "ligada" de novo: devolve false e o canvas fica como estava', async () => {
    await renderCanvas()
    const api = useFlowStore.getState().canvasApi!
    let again = true
    await act(async () => {
      api.connectNodes('sw', 'appr', 'alta')
      again = api.connectNodes('sw', 'appr', 'alta')
    })
    expect(again).toBe(false)
    await waitFor(() => expect(branches()).toEqual([['sw', 'appr', 'alta']]))
  })

  it('sem ramo, o par repetido continua recusado (a aresta sem ramo sempre segue)', async () => {
    await renderCanvas()
    const api = useFlowStore.getState().canvasApi!
    let again = true
    await act(async () => {
      api.connectNodes('appr', 'auto')
      again = api.connectNodes('appr', 'auto')
    })
    expect(again).toBe(false)
    await waitFor(() => expect(useFlowStore.getState().edges).toHaveLength(1))
  })
})

describe('FlowCanvas — ligar à mão um 2º caso ao mesmo destino (revisão do RCHFLW-149)', () => {
  const drag = async (source: string, target: string) => {
    await act(async () => {
      reactFlowProps.current!.onConnect!({ source, target, sourceHandle: null, targetHandle: null })
    })
  }

  it('o 2º arraste do switch para o mesmo nó entra com o caso livre seguinte, ao lado do 1º', async () => {
    await renderCanvas()
    await drag('sw', 'auto')
    await drag('sw', 'auto')
    await waitFor(() => expect(branches()).toEqual([['sw', 'auto', 'alta'], ['sw', 'auto', 'media']]))
    expect(new Set(useFlowStore.getState().edges.map(e => e.id)).size).toBe(2)
  })

  it('a ligação que já existe (nó sem ramos) é recusada com aviso, não some calada', async () => {
    await renderCanvas()
    await drag('appr', 'auto')
    notificationsMock.show.mockClear()
    await drag('appr', 'auto')
    await waitFor(() => expect(useFlowStore.getState().edges).toHaveLength(1))
    expect(notificationsMock.show).toHaveBeenCalledWith(expect.objectContaining({
      message: 'editor.edgeAlreadyExists',
    }))
  })
})

describe('parallelEdges', () => {
  const edge = (id: string, source: string, target: string, branch?: string): Edge => ({
    id, source, target, data: branch ? { branch } : {},
  })

  it('aceita ramo novo num par que já tem outro ramo, e recusa o mesmo ramo ou a aresta sem ramo', () => {
    const edges = [edge('e1', 's', 't', 'alta')]
    expect(withEdge(edges, edge('e2', 's', 't', 'default'))?.map(e => e.id)).toEqual(['e1', 'e2'])
    expect(withEdge(edges, edge('e3', 's', 't', 'alta'))).toBeNull()
    expect(withEdge(edges, edge('e4', 's', 't'))).toBeNull()
    expect(withEdge([edge('e5', 's', 't')], edge('e6', 's', 't', 'alta'))).toBeNull()
    expect(withEdge(edges, edge('e7', 's', 'u', 'alta'))?.map(e => e.id)).toEqual(['e1', 'e7'])
  })

  it('conflictingEdge devolve a aresta que ocupa a ligação', () => {
    const edges = [edge('e1', 's', 't', 'alta'), edge('e2', 's', 't', 'media')]
    expect(conflictingEdge(edges, 's', 't', 'media')?.id).toBe('e2')
    expect(conflictingEdge(edges, 's', 't', 'baixa')).toBeUndefined()
    expect(conflictingEdge(edges, 's', 't', undefined)?.id).toBe('e1')
  })
})
