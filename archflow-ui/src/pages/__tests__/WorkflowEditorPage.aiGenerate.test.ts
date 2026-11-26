/**
 * "Gerar com IA" no editor: o toast de "montando seu fluxo" precisa sair com um id que a ponte do
 * copilot conheça — é por ele que a ponte o fecha quando a execução falha (ex.: sem chave de LLM).
 *
 * <p>Um id novo por geração, entregue junto com o prompt, e mostrado ANTES do pedido: um erro
 * imediato precisa encontrar o toast para fechá-lo. Reaproveitar um id fixo exigiria hide + show no
 * mesmo tick, que o Mantine/React juntam num render e deixam o timer do toast anterior.</p>
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { entrarComo } from '../../lib/__tests__/papeisDeTeste'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { Route, Routes } from 'react-router-dom'
import { MemoryDataRouter } from '../../lib/__tests__/roteadorDeTeste'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'

const workflowApiMock = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn() }))
const calls = vi.hoisted(() => [] as string[])
const notificationsMock = vi.hoisted(() => ({
  show: vi.fn((n: { id?: string }) => { calls.push(`show:${n.id}`); return n.id ?? '' }),
  hide: vi.fn((id: string) => { calls.push(`hide:${id}`); return id }),
  update: vi.fn(),
  clean: vi.fn(),
}))
const idSeq = vi.hoisted(() => ({ n: 0 }))
const bridgeMock = vi.hoisted(() => ({
  requestCopilotGeneration: vi.fn((_prompt: string, id?: string) => { calls.push(`request:${id}`) }),
  newCopilotGenerateNotificationId: vi.fn(() => `archflow-copilot-generate-${++idSeq.n}`),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string) => k,
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))
vi.mock('@mantine/notifications', () => ({ notifications: notificationsMock }))
vi.mock('../../services/api', () => ({ workflowApi: workflowApiMock, executionApi: {} }))
vi.mock('../../services/workflow-yaml-api', () => ({ workflowYamlApi: { get: vi.fn(), update: vi.fn() } }))
vi.mock('../../services/agui-client', () => ({ runAgUiWorkflow: vi.fn(() => () => {}) }))
vi.mock('../../components/copilot/CopilotGenerateBridge', () => bridgeMock)
vi.mock('../../components/PublishModal', () => ({ PublishModal: () => null }))
vi.mock('../../components/NodePalette', () => ({ NodePalette: () => null }))
vi.mock('../../components/PropertyPanel', () => ({ PropertyPanel: () => null }))

import { WorkflowEditor } from '../WorkflowEditorPage'
import { useFlowStore } from '../../components/FlowCanvas/store/useFlowStore'
import { useWorkflowStore } from '../../stores/workflow-store'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
  // Papel que concede editar e executar: sem ele as ações somem (RCHFLW-74).
  entrarComo('DESIGNER')
  vi.clearAllMocks()
  calls.length = 0
  idSeq.n = 0
  ;(globalThis as any).ResizeObserver = ResizeObserverStub
  ;(globalThis as any).DOMMatrixReadOnly ??= class {
    m22 = 1
    constructor() {}
  }
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  // O Textarea autosize do Mantine escuta document.fonts, que o jsdom não tem.
  ;(document as any).fonts ??= { addEventListener() {}, removeEventListener() {} }
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  useWorkflowStore.setState({ currentWorkflow: null, error: null, loading: false })
  useFlowStore.setState({ nodes: [], edges: [], canvasApi: null, pendingCanvasOps: [], isExecuting: false })
  workflowApiMock.get.mockResolvedValue({
    id: 'wf-1',
    metadata: { name: 'Fluxo', description: '', version: '1.0.0', category: '', tags: [] },
    configuration: {},
    steps: [],
  })
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function renderEditor() {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(
      h(MantineProvider, null,
        h(MemoryDataRouter, { initialEntries: ['/editor/wf-1'] },
          h(Routes, null, h(Route, { path: '/editor/:id', element: h(WorkflowEditor) })))),
    )
  })
  await waitFor(() => expect(useFlowStore.getState().canvasApi).not.toBeNull())
  return within(container)
}

async function generate(ui: ReturnType<typeof within>, goal: string) {
  await waitFor(() => expect((ui.getByTestId('editor-ai') as HTMLButtonElement).disabled).toBe(false))
  await act(async () => { fireEvent.click(ui.getByTestId('editor-ai')) })
  const modal = within(document.body)
  const textarea = await waitFor(() => modal.getByTestId('editor-ai-goal') as HTMLTextAreaElement)
  await act(async () => { fireEvent.change(textarea, { target: { value: goal } }) })
  await act(async () => { fireEvent.click(modal.getByTestId('editor-ai-generate')) })
}

describe('WorkflowEditor — Gerar com IA', () => {
  it('mostra o toast de "montando" com um id e entrega esse mesmo id à ponte, nessa ordem', async () => {
    const ui = await renderEditor()

    await generate(ui, 'triagem de chamados')

    expect(notificationsMock.show).toHaveBeenCalledWith(expect.objectContaining({
      id: 'archflow-copilot-generate-1',
      title: 'editor.ai.started',
      color: 'grape',
    }))
    expect(bridgeMock.requestCopilotGeneration).toHaveBeenCalledWith(
      'editor.ai.promptTemplate', 'archflow-copilot-generate-1')
    expect(calls).toEqual(['show:archflow-copilot-generate-1', 'request:archflow-copilot-generate-1'])
  })

  it('cada geração usa um id novo, sem hide + show do mesmo id', async () => {
    const ui = await renderEditor()

    await generate(ui, 'primeira')
    await generate(ui, 'segunda')

    expect(notificationsMock.hide).not.toHaveBeenCalled()
    const ids = notificationsMock.show.mock.calls.map(([n]) => n.id)
    expect(ids).toEqual(['archflow-copilot-generate-1', 'archflow-copilot-generate-2'])
  })
})
