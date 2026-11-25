/**
 * RCHFLW-77 — a fila do copiloto (createFlow → addNode, antes de o canvas existir) precisa ser drenada
 * no canvas do fluxo da ROTA, não no do fluxo que o store ainda guarda.
 *
 * <p>O useWorkflowStore mantém o currentWorkflow depois que o editor desmonta. Abrir /editor/B com A
 * na memória montava o canvas de A até o GET de B responder: a fila era drenada nele, os nós sumiam
 * quando B carregava e o fluxo novo ficava vazio, sem aviso. Um Salvar nessa janela gravava em A.</p>
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { waitFor, within } from '@testing-library/dom'

const workflowApiMock = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn() }))
const notificationsMock = vi.hoisted(() => ({ show: vi.fn(), hide: vi.fn(), update: vi.fn(), clean: vi.fn() }))

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
vi.mock('../../components/copilot/CopilotGenerateBridge', () => ({
  requestCopilotGeneration: vi.fn(),
  newCopilotGenerateNotificationId: vi.fn(() => 'id'),
}))
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

const meta = (name: string) => ({ name, description: '', version: '1.0.0', category: '', tags: [] })
const OLD = {
  id: 'wf-old',
  metadata: meta('Antigo'),
  configuration: {},
  steps: [{ id: 'a', type: 'input', componentId: 'input', label: 'A', position: { x: 0, y: 0 }, connections: [] }],
}
const NEW = { id: 'wf-new', metadata: meta('Novo'), configuration: {}, steps: [] }

beforeEach(() => {
  vi.clearAllMocks()
  ;(globalThis as any).ResizeObserver = ResizeObserverStub
  ;(globalThis as any).DOMMatrixReadOnly ??= class {
    m22 = 1
    constructor() {}
  }
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  ;(document as any).fonts ??= { addEventListener() {}, removeEventListener() {} }
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  // O fluxo aberto antes (o usuário saiu do editor; nada limpa o currentWorkflow).
  useWorkflowStore.setState({ currentWorkflow: OLD as any, error: null, loading: false })
  useFlowStore.setState({
    nodes: [], edges: [], canvasApi: null, pendingCanvasOps: [], pendingCanvasTarget: null, isExecuting: false,
  })
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function renderEditorAt(path: string) {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(
      h(MantineProvider, null,
        h(MemoryRouter, { initialEntries: [path] },
          h(Routes, null, h(Route, { path: '/editor/:id', element: h(WorkflowEditor) })))),
    )
  })
  return within(container)
}

describe('WorkflowEditor — fila do copiloto com outro fluxo ainda no store', () => {
  it('os nós enfileirados para o fluxo novo esperam o GET dele e vão para o canvas dele', async () => {
    let resolveGet!: (v: unknown) => void
    workflowApiMock.get.mockImplementation(() => new Promise(r => { resolveGet = r }))
    // createFlow → addNode ×2 → connectNodes, com o copiloto fora do editor.
    useFlowStore.getState().awaitCanvasFor('wf-new')
    useFlowStore.getState().enqueueCanvasOp({ kind: 'add', id: 'q1', nodeType: 'input' })
    useFlowStore.getState().enqueueCanvasOp({ kind: 'add', id: 'q2', nodeType: 'output' })
    useFlowStore.getState().enqueueCanvasOp({ kind: 'connect', sourceId: 'q1', targetId: 'q2' })

    const ui = await renderEditorAt('/editor/wf-new')
    // Deixa passar o setTimeout da drenagem: antes, o canvas de wf-old montava aqui e esvaziava a fila.
    await act(async () => { await new Promise(r => setTimeout(r, 20)) })

    expect(useFlowStore.getState().canvasApi).toBeNull()
    expect(useFlowStore.getState().pendingCanvasOps).toHaveLength(3)
    // Nada do fluxo antigo editável na tela: nem o canvas dele, nem o Salvar (gravaria em wf-old).
    expect(ui.queryByText('Antigo')).toBeNull()

    await act(async () => { resolveGet(NEW) })
    await waitFor(() => expect(useFlowStore.getState().nodes.map(n => n.id)).toEqual(['q1', 'q2']))
    const { connections } = useFlowStore.getState().getCanvasSnapshot()
    expect(connections.map(e => [e.sourceId, e.targetId])).toEqual([['q1', 'q2']])
    expect(useFlowStore.getState().pendingCanvasOps).toEqual([])
    expect(workflowApiMock.update).not.toHaveBeenCalled()
  })

  it('reabrir o MESMO fluxo guardado: a fila espera a cópia nova (a velha remontaria e a perderia)', async () => {
    let resolveGet!: (v: unknown) => void
    workflowApiMock.get.mockImplementation(() => new Promise(r => { resolveGet = r }))
    useFlowStore.getState().enqueueCanvasOp({ kind: 'add', id: 'q1', nodeType: 'output' })

    await renderEditorAt('/editor/wf-old')
    await act(async () => { await new Promise(r => setTimeout(r, 20)) })
    expect(useFlowStore.getState().pendingCanvasOps).toHaveLength(1)

    await act(async () => { resolveGet(OLD) })
    await waitFor(() => expect(useFlowStore.getState().nodes.map(n => n.id)).toEqual(['a', 'q1']))
    // E continuam lá depois que tudo assenta (nenhuma remontagem posterior).
    await act(async () => { await new Promise(r => setTimeout(r, 20)) })
    expect(useFlowStore.getState().nodes.map(n => n.id)).toEqual(['a', 'q1'])
  })
})
