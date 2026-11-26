/**
 * RCHFLW-123 — a Entrada declarava `ioFields: [valor, cliente, motivo]`, e o diálogo de execução aceitava texto livre
 * sem aviso: a condição `${entrada.valor} > 500` dava false e o LLM confirmava o reembolso de R$ 900 sem aprovação.
 * O motor agora falha fechado; o Executar do editor AVISA (não impede) quando a entrada não traz os campos declarados.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { entrarComo } from '../../lib/__tests__/papeisDeTeste'
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
import { missingInputFields } from '../../lib/runInput'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

function step(id: string, type: string, label: string, configuration: Record<string, unknown> = {},
  connections: { targetId: string; branch?: string }[] = []) {
  return {
    id, type: type.toUpperCase(), componentId: type, label, position: { x: 0, y: 0 }, configuration,
    connections: connections.map(c => ({ sourceId: id, ...c })),
  }
}

/** Receber pedido (ioFields valor, cliente) → Acima de 500? → Gerente / Automático. */
const reembolso = [
  step('receber', 'input', 'Receber pedido', { ioFields: ['valor', 'cliente'] }, [{ targetId: 'acima' }]),
  step('acima', 'condition', 'Acima de 500?', { conditionExpression: '${valor} > 500' },
    [{ targetId: 'gerente', branch: 'true' }, { targetId: 'auto', branch: 'false' }]),
  step('gerente', 'output', 'Gerente'),
  step('auto', 'output', 'Automático'),
]

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
  entrarComo('DESIGNER')
  vi.clearAllMocks()
  sessionStorage.clear()
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

async function openRun(steps: unknown[]) {
  workflowApiMock.get.mockResolvedValue({
    id: 'wf-1', metadata: { name: 'Reembolso', description: '', version: '1.0.0', category: '', tags: [] },
    configuration: {}, steps,
  })
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
  const body = within(document.body)
  const text = await waitFor(() => body.getByTestId('run-input-text'))
  return { body, text }
}

describe('missingInputFields', () => {
  it('texto livre e vazio não trazem nenhum campo; o JSON traz as chaves dele', () => {
    expect(missingInputFields('quero reembolso de 900 reais', ['valor', 'cliente'])).toEqual(['valor', 'cliente'])
    expect(missingInputFields('', ['valor'])).toEqual(['valor'])
    expect(missingInputFields('{"valor": 900}', ['valor', 'cliente'])).toEqual(['cliente'])
    expect(missingInputFields('{"valor": 900, "cliente": "Carla"}', ['valor', 'cliente'])).toEqual([])
    expect(missingInputFields('texto', [])).toEqual([])
    expect(missingInputFields('texto', [' valor ', 'valor', ''])).toEqual(['valor'])
  })
})

describe('WorkflowEditor — Executar avisa os campos da Entrada (RCHFLW-123)', () => {
  it('texto livre num fluxo que declara campos: avisa quais faltam, e ainda deixa executar', async () => {
    const { body, text } = await openRun(reembolso)

    await act(async () => {
      fireEvent.change(text, { target: { value: 'Olá, quero reembolso de 900 reais do pedido 555.' } })
    })
    expect(body.getByTestId('run-input-missing-fields').textContent).toBe('runInput.missingFields[valor, cliente]')

    // JSON com os campos: o aviso some.
    await act(async () => { fireEvent.change(text, { target: { value: '{"valor": 900, "cliente": "Carla"}' } }) })
    expect(body.queryByTestId('run-input-missing-fields')).toBeNull()

    // É aviso, não bloqueio: com o texto livre de novo, Executar dispara o run.
    await act(async () => { fireEvent.change(text, { target: { value: 'reembolso de 900 reais' } }) })
    await act(async () => { fireEvent.click(body.getByTestId('run-input-confirm')) })
    await waitFor(() => expect(runMock).toHaveBeenCalled())
  })

  it('fluxo sem campos declarados: nenhum aviso', async () => {
    const { body, text } = await openRun([
      step('receber', 'input', 'Receber pedido', {}, [{ targetId: 'fim' }]),
      step('fim', 'output', 'Saída'),
    ])
    await act(async () => { fireEvent.change(text, { target: { value: 'texto livre' } }) })
    expect(body.queryByTestId('run-input-missing-fields')).toBeNull()
  })
})
