/**
 * RCHFLW-11 — nós que o motor ainda não executa ficam marcados "ainda não suportado na execução" na paleta e no
 * card do canvas. Continuam na paleta (fluxos existentes os têm e precisam editá-los), mas o usuário vê antes de
 * montar o fluxo — não só ao executar.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { ReactFlowProvider } from '@xyflow/react'
import { within } from '@testing-library/dom'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, o?: { defaultValue?: string }) => o?.defaultValue ?? k,
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))

import { NodePalette } from '../../NodePalette'
import { ShapedNode } from '../nodes/ShapedNode'
import type { FlowNodeData } from '../types'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

let root: Root | null = null
let container: HTMLDivElement

async function mount(element: ReturnType<typeof h>) {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => { root!.render(h(MantineProvider, null, element)) })
  return within(container)
}

function node(nodeType: string, componentId = nodeType): FlowNodeData {
  return { label: 'Nó', componentId, nodeType, status: 'idle', config: {} } as FlowNodeData
}

beforeEach(() => {
  ;(globalThis as any).ResizeObserver = ResizeObserverStub
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

describe('marcador "ainda não suportado na execução"', () => {
  it('a paleta marca os tipos sem execução, não marca os que rodam e não oferece llm-streaming', async () => {
    const view = await mount(h(NodePalette))

    expect(view.getByTestId('palette-not-executable-agent').textContent).toBe('editor.palette.notExecutable')
    expect(view.getByTestId('palette-not-executable-transform')).toBeTruthy()
    expect(view.queryByTestId('palette-not-executable-llm-chat')).toBeNull()
    expect(view.queryByTestId('palette-not-executable-input')).toBeNull()
    // O nó continua arrastável para quem precisa editar um fluxo existente.
    expect(view.getByRole('button', { name: 'Agent' })).toBeTruthy()
    // RCHFLW-57: o LLM Streaming não é mais oferecido; o LLM Chat é.
    expect(view.queryByRole('button', { name: 'LLM Streaming' })).toBeNull()
    expect(view.getByRole('button', { name: 'LLM Chat' })).toBeTruthy()
  })

  it.each([
    ['agent', 'agent', true],
    ['loop', 'loop', true],
    ['llm-chat', 'llm-chat', false],
    ['input', 'input', false],
    // Passo escrito à mão com type "agent" e componentId de plugin: o motor resolve pelo componentId.
    ['agent', 'research-agent', false],
  ])('o card do nó %s (componentId %s) marcado: %s', async (nodeType, componentId, marked) => {
    await mount(h(ReactFlowProvider, null,
      h(ShapedNode, { id: 'n', data: node(nodeType, componentId), selected: false, category: 'agent' })))

    const marker = container.querySelector('[data-testid="node-not-executable"]')
    expect(marker !== null).toBe(marked)
    if (marked) expect(marker!.textContent).toBe('editor.palette.notExecutable')
  })
})
