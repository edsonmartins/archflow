/**
 * Clonar um template e abri-lo no editor (RCHFLW-45).
 *
 * <p>O clone gravava o formato do designer — config em `config` e as arestas num
 * `configuration.connections` no topo do documento. O editor só lê `steps[].configuration` e
 * `steps[].connections`: o fluxo clonado abria com os nós soltos e o painel de config vazio, e o
 * primeiro Salvar gravava `configuration: {}` em cada passo, perdendo a config do template de vez.
 * Estes testes passam pelo caminho inteiro — `templateApi.clone` → o que o backend guardou → o
 * editor real (FlowCanvas + useFlowStore) → Salvar → reabrir —; só a rede é falsa.</p>
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'

const workflowApiMock = vi.hoisted(() => ({
  get:    vi.fn(),
  update: vi.fn(),
  create: vi.fn(),
}))
const apiMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string) => k,
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))
vi.mock('../../services/api', () => ({
  api:          apiMock,
  workflowApi:  workflowApiMock,
  executionApi: {},
}))
const yamlApiMock = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn() }))
vi.mock('../../services/workflow-yaml-api', () => ({ workflowYamlApi: yamlApiMock }))
vi.mock('../../services/agui-client', () => ({ runAgUiWorkflow: vi.fn(() => () => {}) }))
vi.mock('../../components/copilot/CopilotGenerateBridge', () => ({
  requestCopilotGeneration: vi.fn(),
  newCopilotGenerateNotificationId: () => 'archflow-copilot-generate-test',
}))
vi.mock('../../components/PublishModal', () => ({ PublishModal: () => null }))
vi.mock('../../components/NodePalette', () => ({ NodePalette: () => null }))
// O painel de propriedades real é pesado e não é o que está em teste: este mostra a config que o
// editor entrega a ele, que é exatamente o que o painel real exibiria.
vi.mock('../../components/PropertyPanel', async () => {
  const react = await import('react')
  return {
    PropertyPanel: ({ nodeId, nodeData }: { nodeId: string | null; nodeData: { config?: unknown } | null }) =>
      react.createElement('pre', { 'data-testid': 'panel-config', 'data-node': nodeId ?? '' },
        JSON.stringify(nodeData?.config ?? null)),
  }
})

import { WorkflowEditor } from '../WorkflowEditorPage'
import { useFlowStore } from '../../components/FlowCanvas/store/useFlowStore'
import { useWorkflowStore } from '../../stores/workflow-store'
import { templateApi } from '../../services/template-api'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
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
})

/** As 8 arestas do sac-basic (src/data/templates.ts), como pares origem→destino. */
const SAC_EDGES = [
  's1→s2', 's2→s3', 's2→s4', 's2→s5', 's3→s6', 's4→s6', 's5→s7', 's6→s7',
]

type SavedStep = { id: string; configuration?: Record<string, unknown>; connections?: unknown[] }
type SavedDoc = { steps: SavedStep[]; configuration?: Record<string, unknown> }

let root: Root | null = null
let container: HTMLDivElement

async function renderEditor() {
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
  await waitFor(() => expect(useFlowStore.getState().canvasApi).not.toBeNull())
  return within(container)
}

async function unmountEditor() {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
  useWorkflowStore.setState({ currentWorkflow: null, error: null, loading: false })
  useFlowStore.setState({ nodes: [], edges: [], canvasApi: null, pendingCanvasOps: [], isExecuting: false })
}

function canvasEdges() {
  return useFlowStore.getState().edges.map(e => `${e.source}→${e.target}`).sort()
}

function nodeConfig(id: string) {
  return useFlowStore.getState().nodes.find(n => n.id === id)?.data.config
}

/** Abre o painel de config de um nó como o usuário: clicando nele no canvas. */
async function openPanel(ui: ReturnType<typeof within>, nodeId: string) {
  const node = container.querySelector(`.react-flow__node[data-id="${nodeId}"]`) as HTMLElement | null
  expect(node).not.toBeNull()
  await act(async () => { fireEvent.click(node!) })
  const panel = await waitFor(() => {
    const p = ui.getByTestId('panel-config')
    expect(p.getAttribute('data-node')).toBe(nodeId)
    return p
  })
  return JSON.parse(panel.textContent ?? 'null')
}

function savedEdges(body: SavedDoc) {
  return body.steps
    .flatMap(s => (s.connections ?? []) as { sourceId: string; targetId: string }[])
    .map(c => `${c.sourceId}→${c.targetId}`)
    .sort()
}

describe('WorkflowEditor — template clonado (RCHFLW-45)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    templateApi._resetCache()
    useWorkflowStore.setState({ currentWorkflow: null, error: null, loading: false })
    useFlowStore.setState({ nodes: [], edges: [], canvasApi: null, pendingCanvasOps: [], isExecuting: false })
    // O backend guarda o que recebe e devolve no GET — como o POST/PUT reais.
    let stored: unknown = null
    workflowApiMock.create.mockImplementation(async (body: object) => {
      stored = { id: 'wf-1', ...body }
      return stored
    })
    workflowApiMock.update.mockImplementation(async (_id: string, body: unknown) => {
      stored = body
      return body
    })
    workflowApiMock.get.mockImplementation(async () => structuredClone(stored))
  })

  afterEach(unmountEditor)

  it('abre com as arestas e a config dos nós, e o primeiro Salvar as mantém', async () => {
    await templateApi.clone('sac-basic')

    // O motor (DefaultFlowStepFactory) lê o documento guardado sem passar pelo editor: executar o
    // clone sem salvar só segue as arestas se elas estiverem dentro dos passos.
    const stored = (await workflowApiMock.get('wf-1')) as SavedDoc
    expect(savedEdges(stored)).toEqual([...SAC_EDGES].sort())
    expect(stored.steps.find(s => s.id === 's2')?.configuration).toEqual({ strategy: 'llm' })
    expect(stored.configuration).not.toHaveProperty('connections')

    const ui = await renderEditor()

    expect(useFlowStore.getState().nodes).toHaveLength(7)
    expect(canvasEdges()).toEqual([...SAC_EDGES].sort())
    expect(await openPanel(ui, 's2')).toEqual({ strategy: 'llm' })
    expect(await openPanel(ui, 's3')).toEqual({ persona: 'order_tracking' })

    await act(async () => { fireEvent.click(ui.getByTestId('editor-save')) })
    await waitFor(() => expect(workflowApiMock.update).toHaveBeenCalledTimes(1))

    const body = workflowApiMock.update.mock.calls[0][1] as SavedDoc
    expect(body.steps).toHaveLength(7)
    expect(savedEdges(body)).toEqual([...SAC_EDGES].sort())
    expect(body.steps.find(s => s.id === 's2')?.configuration).toEqual({ strategy: 'llm' })
    expect(body.steps.find(s => s.id === 's6')?.configuration).toEqual({ name: 'crm_lookup' })
    expect(body.configuration).toMatchObject({ templateId: 'sac-basic' })
    expect(body.configuration).not.toHaveProperty('connections')

    // Reabrir: o que voltou do backend tem tudo.
    await unmountEditor()
    const again = await renderEditor()
    expect(canvasEdges()).toEqual([...SAC_EDGES].sort())
    expect(nodeConfig('s5')).toEqual({ persona: 'faq' })
    expect(await openPanel(again, 's2')).toEqual({ strategy: 'llm' })
  })

  it('template do backend (SPI): abre com a aresta condicional e a config, e o Salvar as mantém', async () => {
    // Como GET /api/templates serializa (TemplateDocument): documento canônico, type cru.
    apiMock.get.mockResolvedValue([{
      id: 'customer-support', displayName: 'Customer Support with RAG', description: 'd', category: 'support',
      tags: [], stepCount: 3, unsupportedNodeTypes: ['vector-search'],
      steps: [
        { id: 'input', type: 'input', componentId: 'input', label: 'Inquiry', position: { x: 0, y: 0 },
          configuration: {}, connections: [{ sourceId: 'input', targetId: 'intent', isErrorPath: false }] },
        { id: 'intent', type: 'llm', componentId: 'llm', label: 'Intent', position: { x: 250, y: 0 },
          configuration: { temperature: 0.3 },
          connections: [{ sourceId: 'intent', targetId: 'kb', isErrorPath: false, condition: "${intent} != 'general'" }] },
        { id: 'kb', type: 'vector-search', componentId: 'vector-search', label: 'KB', position: { x: 500, y: 0 },
          configuration: { topK: 5 }, connections: [] },
      ],
    }])
    apiMock.post.mockResolvedValue({})
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    await templateApi.list()
    await templateApi.clone('customer-support')
    const stored = (await workflowApiMock.get('wf-1')) as SavedDoc
    expect(savedEdges(stored)).toEqual(['input→intent', 'intent→kb'])
    expect(stored.steps.map(s => (s as { type?: string }).type)).toEqual(['input', 'llm', 'vector-search'])

    const ui = await renderEditor()

    expect(canvasEdges()).toEqual(['input→intent', 'intent→kb'])
    const cond = useFlowStore.getState().edges.find(e => e.source === 'intent')?.data as { condition?: string }
    expect(cond?.condition).toBe("${intent} != 'general'")
    expect(await openPanel(ui, 'kb')).toEqual({ topK: 5 })

    await act(async () => { fireEvent.click(ui.getByTestId('editor-save')) })
    await waitFor(() => expect(workflowApiMock.update).toHaveBeenCalledTimes(1))

    const body = workflowApiMock.update.mock.calls[0][1] as SavedDoc
    expect(savedEdges(body)).toEqual(['input→intent', 'intent→kb'])
    expect(body.steps.find(s => s.id === 'intent')?.connections)
      .toEqual([{ sourceId: 'intent', targetId: 'kb', isErrorPath: false, condition: "${intent} != 'general'" }])
    expect(body.steps.find(s => s.id === 'intent')?.configuration).toEqual({ temperature: 0.3 })
    expect(body.steps.find(s => s.id === 'kb')?.configuration).toEqual({ topK: 5 })
  })
})
