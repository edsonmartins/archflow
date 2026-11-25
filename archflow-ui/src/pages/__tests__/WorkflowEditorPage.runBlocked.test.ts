/**
 * O Executar do editor não começa um run que o motor vai recusar (RCHFLW-11, RCHFLW-92): um nó de tipo sem
 * execução ou um campo obrigatório vazio para o run antes de salvar e de abrir o pedido de entrada, com um aviso
 * que nomeia cada nó (e o campo).
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'

const workflowApiMock = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn() }))
const yamlApiMock = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn() }))
const runMock = vi.hoisted(() => vi.fn(() => () => {}))
const showMock = vi.hoisted(() => vi.fn())

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, o?: Record<string, string>) => (o ? `${k}[${Object.values(o).join('|')}]` : k),
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))
vi.mock('../../services/api', () => ({ workflowApi: workflowApiMock, executionApi: { get: vi.fn() } }))
vi.mock('../../services/workflow-yaml-api', () => ({ workflowYamlApi: yamlApiMock }))
vi.mock('../../services/agui-client', () => ({ runAgUiWorkflow: runMock }))
vi.mock('../../components/copilot/CopilotGenerateBridge', () => ({
  requestCopilotGeneration: vi.fn(),
  newCopilotGenerateNotificationId: () => 'archflow-copilot-generate-test',
}))
vi.mock('../../components/PublishModal', () => ({ PublishModal: () => null }))
vi.mock('../../components/NodePalette', () => ({ NodePalette: () => null }))
vi.mock('../../components/PropertyPanel', () => ({ PropertyPanel: () => null }))
vi.mock('@mantine/notifications', () => ({ notifications: { show: showMock } }))

import { WorkflowEditor } from '../WorkflowEditorPage'
import { useFlowStore } from '../../components/FlowCanvas/store/useFlowStore'
import { useWorkflowStore } from '../../stores/workflow-store'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

function step(id: string, type: string, label: string, configuration: Record<string, unknown> = {}) {
  return { id, type: type.toUpperCase(), componentId: type, label, position: { x: 0, y: 0 }, configuration, connections: [] }
}

function doc(steps: unknown[]) {
  return {
    id: 'wf-1',
    metadata: { name: 'Fluxo', description: '', version: '1.0.0', category: '', tags: [] },
    configuration: {},
    steps,
  }
}

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
  vi.clearAllMocks()
  ;(globalThis as any).ResizeObserver = ResizeObserverStub
  ;(globalThis as any).DOMMatrixReadOnly ??= class { m22 = 1 }
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  useWorkflowStore.setState({ currentWorkflow: null, error: null, loading: false })
  useFlowStore.setState({
    nodes: [], edges: [], canvasApi: null, pendingCanvasOps: [],
    isExecuting: false, executionId: null, executionState: {},
  })
  workflowApiMock.update.mockImplementation(async (_id: string, body: unknown) => body)
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function clickRun(steps: unknown[]) {
  workflowApiMock.get.mockResolvedValue(doc(steps))
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(
      h(MantineProvider, null,
        h(MemoryRouter, { initialEntries: ['/editor/wf-1'] },
          h(Routes, null, h(Route, { path: '/editor/:id', element: h(WorkflowEditor) })))),
    )
  })
  await waitFor(() => expect(useFlowStore.getState().nodes).toHaveLength(steps.length))
  await act(async () => { fireEvent.click(within(container).getByTestId('editor-run')) })
}

describe('WorkflowEditor — Executar impedido', () => {
  it('nó de tipo sem execução: não salva, não abre a entrada e o aviso nomeia o nó', async () => {
    await clickRun([step('a', 'input', 'Entrada'), step('b', 'agent', 'Agente de vendas'), step('c', 'output', 'Saída')])

    expect(showMock).toHaveBeenCalledWith(expect.objectContaining({
      title: 'editor.notif.runBlocked',
      message: 'editor.notif.runBlockedNotExecutable["Agente de vendas" (agent)]',
      color: 'red',
    }))
    expect(within(document.body).queryByTestId('run-input-confirm')).toBeNull()
    expect(workflowApiMock.update).not.toHaveBeenCalled()
    expect(runMock).not.toHaveBeenCalled()
  })

  it('Condição sem expressão: o aviso nomeia o nó e o campo', async () => {
    await clickRun([step('a', 'input', 'Entrada'), step('c', 'condition', 'Urgente?')])

    expect(showMock).toHaveBeenCalledWith(expect.objectContaining({
      title: 'editor.notif.runBlocked',
      message: 'editor.notif.runBlockedMissing["Urgente?" → editor.properties.fields.expression]',
    }))
    expect(within(document.body).queryByTestId('run-input-confirm')).toBeNull()
    expect(runMock).not.toHaveBeenCalled()
  })

  it('fluxo que executa (Entrada → Condição preenchida → Saída) segue para o pedido de entrada', async () => {
    await clickRun([
      step('a', 'input', 'Entrada'),
      step('c', 'condition', 'Urgente?', { conditionExpression: "${a} != ''" }),
      step('d', 'output', 'Saída'),
    ])

    await waitFor(() => expect(within(document.body).getByTestId('run-input-confirm')).toBeTruthy())
    expect(showMock).not.toHaveBeenCalledWith(expect.objectContaining({ title: 'editor.notif.runBlocked' }))
  })
})
