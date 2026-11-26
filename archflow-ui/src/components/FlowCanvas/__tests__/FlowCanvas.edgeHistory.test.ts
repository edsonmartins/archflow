/**
 * Edição de arestas no canvas e o histórico.
 *
 * <p>RCHFLW-188, item 10 — mudar o ramo de uma aresta não criava passo de undo: o Ctrl+Z seguinte desfazia a edição
 * anterior (o rename de um nó) junto com a da aresta, e com o histórico limpo o Desfazer ficava desabilitado com o
 * editor NÃO SALVO.</p>
 *
 * <p>RCHFLW-147 — renomear um caso do switch deixava a aresta no ramo antigo, que nenhum valor alcança. O rename sem
 * ambiguidade (mesma posição, mesma quantidade de casos) leva a aresta junto, pelo painel e pelo copiloto; qualquer
 * outra troca deixa a aresta onde estava, marcada como ramo não declarado.</p>
 *
 * <p>O jsdom não mede nada, e sem medida o React Flow não desenha aresta: o ResizeObserver e o offsetWidth/Height
 * abaixo dão tamanho aos nós. Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor } from '@testing-library/dom'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string) => k,
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn() } }))

import { FlowCanvas } from '../FlowCanvas'
import { useFlowStore } from '../store/useFlowStore'
import type { WorkflowData } from '../types'

class ResizeObserverStub {
  cb: (e: unknown[]) => void
  constructor(cb: (e: unknown[]) => void) { this.cb = cb }
  observe(target: Element) { setTimeout(() => this.cb([{ target }]), 0) }
  unobserve() {}
  disconnect() {}
}
Object.defineProperty(HTMLElement.prototype, 'offsetWidth', { configurable: true, get() { return 150 } })
Object.defineProperty(HTMLElement.prototype, 'offsetHeight', { configurable: true, get() { return 60 } })

let root: Root | null = null
let container: HTMLDivElement

const SWITCH_CASES = 'defeito\narrependimento\ndefault'

const WORKFLOW: WorkflowData = {
  steps: [
    { id: 'a', type: 'input', componentId: 'input', label: 'Entrada', category: 'io', position: { x: 100, y: 100 } },
    { id: 'd', type: 'decision', componentId: 'decision', label: 'Decisão', category: 'control',
      position: { x: 300, y: 100 } },
    { id: 'b', type: 'approval', componentId: 'approval', label: 'Aprovação', category: 'control',
      position: { x: 500, y: 100 } },
    { id: 'sw', type: 'switch', componentId: 'switch', label: 'Motivo', category: 'control',
      position: { x: 300, y: 300 }, config: { switchExpression: '${a.motivo}', switchCases: SWITCH_CASES } },
    { id: 'r', type: 'output', componentId: 'output', label: 'Resposta', category: 'io', position: { x: 500, y: 300 } },
  ] as WorkflowData['steps'],
  connections: [
    { id: 'e-ad', sourceId: 'a', targetId: 'd', isErrorPath: false },
    { id: 'e-db', sourceId: 'd', targetId: 'b', isErrorPath: false },
    { id: 'e-sr', sourceId: 'sw', targetId: 'r', isErrorPath: false, branch: 'arrependimento' },
  ] as WorkflowData['connections'],
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
  useFlowStore.getState().clearSelection()
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
  const onNodeSelect = vi.fn((id: string | null, data: unknown) => {
    useFlowStore.getState().selectNode(id, data as never)
  })
  await act(async () => {
    root!.render(h(MantineProvider, null, h(FlowCanvas, { initialWorkflow: WORKFLOW, onNodeSelect })))
  })
  await waitFor(() => expect(container.querySelector('.react-flow__node[data-id="a"]')).not.toBeNull())
}

async function key(k: string, mod = false) {
  await act(async () => { fireEvent.keyDown(document.body, { key: k, code: k, ctrlKey: mod }) })
}

function branchOf(edgeId: string) {
  return (useFlowStore.getState().edges.find(e => e.id === edgeId)?.data as { branch?: string } | undefined)?.branch
}

function labelOf(nodeId: string) {
  return useFlowStore.getState().nodes.find(n => n.id === nodeId)?.data.label
}

async function selectEdge(edgeId: string) {
  await waitFor(() => expect(container.querySelector(`.react-flow__edge[data-id="${edgeId}"]`)).not.toBeNull())
  const el = container.querySelector(`.react-flow__edge[data-id="${edgeId}"]`) as Element
  await act(async () => { fireEvent.click(el) })
  await waitFor(() => expect(container.querySelector('select[aria-label="branch"]')).not.toBeNull())
  return container.querySelector('select[aria-label="branch"]') as HTMLSelectElement
}

async function chooseBranch(select: HTMLSelectElement, value: string) {
  await act(async () => { fireEvent.change(select, { target: { value } }) })
}

describe('FlowCanvas — Ctrl+Z da edição de aresta (RCHFLW-188, item 10)', () => {
  it('desfaz só a mudança de rota, não o rename feito antes', async () => {
    await renderCanvas()
    await act(async () => { useFlowStore.getState().updateNodeLabel('a', 'Resposta ao cliente') })
    await waitFor(() => expect(labelOf('a')).toBe('Resposta ao cliente'))

    await chooseBranch(await selectEdge('e-db'), 'ESCALATE')
    await waitFor(() => expect(branchOf('e-db')).toBe('ESCALATE'))

    await act(async () => { fireEvent.click(container.querySelector('.react-flow__pane') as Element) })
    await key('z', true)

    await waitFor(() => expect(branchOf('e-db')).toBeUndefined())
    expect(labelOf('a')).toBe('Resposta ao cliente')
  })

  it('com o histórico limpo, a mudança de aresta habilita o Desfazer', async () => {
    await renderCanvas()
    const undo = container.querySelector('[data-testid="canvas-undo"]') as HTMLButtonElement
    expect(undo.disabled).toBe(true)

    await chooseBranch(await selectEdge('e-db'), 'REVIEW')

    await waitFor(() => expect(undo.disabled).toBe(false))
  })
})

describe('FlowCanvas — caso do switch renomeado (RCHFLW-147)', () => {
  it('pelo painel: a aresta acompanha o caso, e o Ctrl+Z desfaz os dois juntos', async () => {
    await renderCanvas()
    await act(async () => {
      useFlowStore.getState().updateNodeConfig('sw', 'switchCases', 'defeito\narrependido\ndefault')
    })

    await waitFor(() => expect(branchOf('e-sr')).toBe('arrependido'))

    await key('z', true)
    await waitFor(() => expect(branchOf('e-sr')).toBe('arrependimento'))
    expect(useFlowStore.getState().nodes.find(n => n.id === 'sw')?.data.config?.switchCases).toBe(SWITCH_CASES)
  })

  it('pelo copiloto (configureNode): a aresta acompanha o caso', async () => {
    await renderCanvas()
    await act(async () => {
      useFlowStore.getState().canvasApi!.configureNode('sw', { config: { switchCases: 'defeito\narrependido' } })
    })

    await waitFor(() => expect(branchOf('e-sr')).toBe('arrependido'))
  })

  // O painel entrega a edição tecla a tecla: cada valor intermediário do campo chega ao canvas.
  async function typeCases(values: string[]) {
    for (const v of values) {
      await act(async () => { useFlowStore.getState().updateNodeConfig('sw', 'switchCases', v) })
    }
  }

  it('inserir um caso acima de outro (digitar "troca" + Enter no começo da linha) não leva a aresta junto', async () => {
    await renderCanvas()
    const typed = ['t', 'tr', 'tro', 'troc', 'troca'].map(p => `defeito\n${p}arrependimento\ndefault`)
    await typeCases([...typed, 'defeito\ntroca\narrependimento\ndefault'])

    await waitFor(() => expect(branchOf('e-sr')).toBe('arrependimento'))
    expect(container.querySelector('[data-testid="edge-undeclared-branch"]')).toBeNull()
  })

  it('apagar o caso letra a letra e escrever outro: a aresta volta ao ramo ligado, não a uma letra digitada', async () => {
    await renderCanvas()
    const word = 'arrependimento'
    const erasing = Array.from({ length: word.length }, (_, i) => word.slice(0, word.length - 1 - i))
    await typeCases([...erasing, 't', 'tr', 'tro', 'troca'].map(c => `defeito\n${c}\ndefault`))

    await waitFor(() => expect(branchOf('e-sr')).toBe('arrependimento'))
  })

  it('renomear digitando dentro do caso (arrependimento → arrependido) ainda leva a aresta', async () => {
    await renderCanvas()
    const steps = ['arrependiment', 'arrependimen', 'arrependime', 'arrependim', 'arrependi', 'arrepend',
      'arrependi', 'arrependid', 'arrependido']
    await typeCases(steps.map(c => `defeito\n${c}\ndefault`))

    await waitFor(() => expect(branchOf('e-sr')).toBe('arrependido'))
  })

  it('troca que não é rename (caso apagado): a aresta fica e aparece como ramo não declarado', async () => {
    await renderCanvas()
    await act(async () => { useFlowStore.getState().updateNodeConfig('sw', 'switchCases', 'defeito\ndefault') })
    await waitFor(() => expect(container.querySelector('[data-testid="edge-undeclared-branch"]')).not.toBeNull())

    expect(branchOf('e-sr')).toBe('arrependimento')
    expect(container.querySelector('[data-testid="edge-undeclared-branch"]')?.getAttribute('title'))
      .toBe('editor.edge.undeclaredBranch')

    // O seletor da aresta mostra o ramo gravado, marcado, em vez de "—".
    const select = await selectEdge('e-sr')
    expect(select.value).toBe('arrependimento')
    expect(container.querySelector('[role="alert"]')?.textContent).toBe('editor.edge.undeclaredBranch')
  })
})
