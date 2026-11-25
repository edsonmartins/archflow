/**
 * O portão de aprovação à espera aparece como "aguardando aprovação" no canvas — pílula violeta,
 * ampulheta, sem duração e sem pulso —, não como concluído com ✓ (RCHFLW-32). E o chip de prazo só
 * mostra o prazo que o nó tem (RCHFLW-60): o "timeout 30m" de antes não chegava ao motor.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { ReactFlowProvider } from '@xyflow/react'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string) => k,
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))

import { ShapedNode } from '../nodes/ShapedNode'
import { EXECUTION_STATUS_COLORS } from '../constants'
import type { FlowNodeData } from '../types'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

let root: Root | null = null
let container: HTMLDivElement

function approvalNode(extra: Partial<FlowNodeData>, config: Record<string, unknown> = {}): FlowNodeData {
  return {
    label: 'Aprovação', componentId: 'approval', nodeType: 'approval', status: 'idle', config, ...extra,
  } as FlowNodeData
}

async function render(data: FlowNodeData) {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, null,
      h(ReactFlowProvider, null, h(ShapedNode, { id: 'gate', data, selected: false, category: 'control' }))))
  })
}

function pill(): HTMLElement | null {
  return container.querySelector('[data-testid="node-exec-status"]')
}

describe('ShapedNode — portão aguardando aprovação', () => {
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

  it('pílula própria: rótulo de aguardando, sem duração e sem pulso', async () => {
    await render(approvalNode({ executionStatus: 'awaiting', executionMs: 12 }))

    const el = pill()!
    expect(el.dataset.status).toBe('awaiting')
    expect(el.textContent).toContain('editor.outline.status.awaiting')
    expect(el.textContent).not.toContain('ms')
    expect(el.style.animation).toBe('none')
    // Cor própria (violeta), não a de concluído
    expect(EXECUTION_STATUS_COLORS.awaiting.border).not.toBe(EXECUTION_STATUS_COLORS.success.border)
    expect(el.style.border).toContain('var(--purple)')
  })

  it('concluído continua com a duração', async () => {
    await render(approvalNode({ executionStatus: 'success', executionMs: 12 }))

    expect(pill()!.textContent).toContain('12ms')
  })

  it('o chip de prazo só mostra o prazo gravado — sem ele, não inventa "timeout 30m"', async () => {
    await render(approvalNode({}))
    expect(container.textContent).not.toContain('timeout')

    await act(async () => { root?.unmount() })
    container.remove()
    await render(approvalNode({}, { approvalTimeoutMinutes: 5 }))
    expect(container.textContent).toContain('timeout 5m')
  })
})
