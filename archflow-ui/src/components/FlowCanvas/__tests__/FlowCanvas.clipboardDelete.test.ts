/**
 * Editar no canvas como o usuário edita: pelo teclado.
 *
 * <p>RCHFLW-93 — Ctrl+C e Ctrl+V duas vezes empilhava as duas cópias na mesma posição (o deslocamento
 * era fixo em relação ao original), e parecia que só uma tinha sido criada. Cada colagem cai um passo
 * adiante da anterior, como a duplicata do Ctrl+D.</p>
 *
 * <p>RCHFLW-90 — Delete no nó selecionado tirava o nó do canvas, mas o painel de propriedades seguia
 * mostrando-o, editável. A seleção do store (que o painel lê) precisa sair junto.</p>
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
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
  observe() {}
  unobserve() {}
  disconnect() {}
}

let root: Root | null = null
let container: HTMLDivElement

const WORKFLOW: WorkflowData = {
  steps: [
    { id: 'a', type: 'input', componentId: 'input', label: 'Entrada', category: 'io', position: { x: 100, y: 100 } },
    { id: 'b', type: 'output', componentId: 'output', label: 'Saída', category: 'io', position: { x: 400, y: 100 } },
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

async function clickNode(id: string) {
  const el = container.querySelector(`.react-flow__node[data-id="${id}"]`) as HTMLElement
  await act(async () => { fireEvent.click(el) })
}

async function key(k: string, mod = false) {
  await act(async () => { fireEvent.keyDown(document.body, { key: k, code: k, ctrlKey: mod }) })
}

function positionsOf(label: string) {
  return useFlowStore.getState().nodes
    .filter(n => n.data.label === label)
    .map(n => n.position)
}

describe('FlowCanvas — colar o mesmo nó várias vezes (RCHFLW-93)', () => {
  it('cada colagem fica um passo adiante da anterior, nunca em cima dela', async () => {
    await renderCanvas()
    await clickNode('a')

    await key('c', true)
    await key('v', true)
    await key('v', true)

    await waitFor(() => expect(positionsOf('Entrada')).toHaveLength(3))
    // Original, 1ª colagem (+32) e 2ª colagem (+64): o mesmo passo do Ctrl+D, acumulado.
    expect(positionsOf('Entrada')).toEqual([{ x: 100, y: 100 }, { x: 132, y: 132 }, { x: 164, y: 164 }])
    const ids = useFlowStore.getState().nodes.map(n => n.id)
    expect(new Set(ids).size).toBe(ids.length)
  })

  it('copiar de novo recomeça a conta: a próxima colagem fica a um passo do que foi copiado', async () => {
    await renderCanvas()
    await clickNode('a')
    await key('c', true)
    await key('v', true)
    await key('v', true)

    await clickNode('b')
    await key('c', true)
    await key('v', true)

    await waitFor(() => expect(positionsOf('Saída')).toHaveLength(2))
    expect(positionsOf('Saída')[1]).toEqual({ x: 432, y: 132 })
  })
})

describe('FlowCanvas — apagar o nó selecionado (RCHFLW-90)', () => {
  it('Delete tira o nó e a seleção: o painel volta aos padrões do fluxo', async () => {
    await renderCanvas()
    await clickNode('a')
    expect(useFlowStore.getState().selectedNodeId).toBe('a')

    await key('Delete')

    await waitFor(() => expect(useFlowStore.getState().nodes.map(n => n.id)).toEqual(['b']))
    expect(useFlowStore.getState().selectedNodeId).toBeNull()
    expect(useFlowStore.getState().selectedNodeData).toBeNull()
  })

  it('apagar OUTRO nó não mexe na seleção', async () => {
    await renderCanvas()
    await clickNode('b')
    // Seleção do painel no nó a; o React Flow tem o b marcado — é o que o Delete apaga.
    await act(async () => {
      useFlowStore.getState().selectNode('a', useFlowStore.getState().nodes.find(n => n.id === 'a')!.data)
    })

    await key('Delete')

    await waitFor(() => expect(useFlowStore.getState().nodes.map(n => n.id)).toEqual(['a']))
    expect(useFlowStore.getState().selectedNodeId).toBe('a')
    expect(useFlowStore.getState().selectedNodeData?.label).toBe('Entrada')
  })
})
