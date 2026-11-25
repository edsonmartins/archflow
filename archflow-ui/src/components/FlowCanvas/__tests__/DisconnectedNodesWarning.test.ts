/**
 * Aviso de nós sem conexão no editor (RCHFLW-88): sem arestas o motor roda os nós na ordem de criação,
 * e com arestas um nó solto roda à parte — nenhum dos dois aparece no canvas.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import type { Edge, Node } from '@xyflow/react'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, o?: Record<string, unknown>) => (o?.names ? `${k}|${o.names}` : k),
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))

import { DisconnectedNodesWarning } from '../DisconnectedNodesWarning'
import { disconnectedNodes } from '../connectivity'
import type { FlowNodeData } from '../types'

function node(id: string, nodeType = 'llm-chat', label = id.toUpperCase()): Node<FlowNodeData> {
  return {
    id, position: { x: 0, y: 0 }, type: 'agent',
    data: { label, componentId: nodeType, nodeType, status: 'idle', config: {} } as unknown as FlowNodeData,
  }
}

function edge(source: string, target: string): Edge {
  return { id: `${source}-${target}`, source, target }
}

describe('disconnectedNodes', () => {
  it('flow without edges: every node is loose', () => {
    expect(disconnectedNodes([node('a'), node('b'), node('c')], []).map(n => n.id)).toEqual(['a', 'b', 'c'])
  })

  it('flow with edges: only the node with no edge at all', () => {
    expect(disconnectedNodes([node('a'), node('b'), node('c')], [edge('a', 'b')]).map(n => n.id))
      .toEqual(['c'])
  })

  it('a single node needs no edge', () => {
    expect(disconnectedNodes([node('a')], [])).toEqual([])
  })

  it('annotations do not count, neither as loose nor towards "more than one node"', () => {
    expect(disconnectedNodes([node('a'), node('n', 'sticky-note'), node('g', 'group-frame')], [])).toEqual([])
    expect(disconnectedNodes([node('a'), node('b'), node('n', 'sticky-note')], [edge('a', 'b')])).toEqual([])
  })
})

describe('DisconnectedNodesWarning', () => {
  let root: Root | null = null
  let container: HTMLDivElement

  beforeEach(() => {
    ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
    window.matchMedia ??= ((q: string) => ({
      matches: false, media: q, onchange: null,
      addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
      dispatchEvent: () => false,
    })) as any
    container = document.createElement('div')
    document.body.appendChild(container)
  })

  afterEach(() => {
    act(() => root?.unmount())
    root = null
    container.remove()
  })

  async function render(nodes: Node<FlowNodeData>[], edges: Edge[]) {
    root = createRoot(container)
    await act(async () => {
      root!.render(h(MantineProvider, null, h(DisconnectedNodesWarning, { nodes, edges })))
    })
  }

  function warning(): HTMLElement | null {
    return container.querySelector('[data-testid="canvas-disconnected-warning"]')
  }

  it('names the loose nodes', async () => {
    await render([node('a'), node('b'), node('c', 'llm-chat', 'Resumo')], [edge('a', 'b')])
    expect(warning()).not.toBeNull()
    expect(warning()!.textContent).toContain('editor.disconnectedWarning.title')
    expect(warning()!.textContent).toContain('editor.disconnectedWarning.message|Resumo')
  })

  it('stays hidden when every node is connected', async () => {
    await render([node('a'), node('b')], [edge('a', 'b')])
    expect(warning()).toBeNull()
  })
})
