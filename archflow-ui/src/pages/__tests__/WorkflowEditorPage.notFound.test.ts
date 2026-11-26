/**
 * RCHFLW-117: abrir pela URL um fluxo inexistente ou de outro tenant (o backend responde 404, ou 403) mostrava
 * um canvas em branco, "Fluxo sem título", com o mesmo id na URL — parecia um fluxo novo. Agora o editor mostra
 * "Fluxo não encontrado" com a volta para a lista, sem canvas e sem Salvar. Outra falha de carregamento (500,
 * rede) mostra o erro de carregamento, com Atualizar — não "não encontrado".
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

const workflowApiMock = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn(), list: vi.fn() }))
const executionApiMock = vi.hoisted(() => ({ get: vi.fn() }))
const yamlApiMock = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn() }))
const runMock = vi.hoisted(() => vi.fn(() => () => {}))
const showMock = vi.hoisted(() => vi.fn())

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string) => k,
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))
vi.mock('../../services/api', () => ({
  workflowApi:  workflowApiMock,
  executionApi: executionApiMock,
}))
vi.mock('../../services/workflow-yaml-api', () => ({ workflowYamlApi: yamlApiMock }))
vi.mock('../../services/agui-client', () => ({ runAgUiWorkflow: runMock }))
vi.mock('../../components/copilot/CopilotGenerateBridge', () => ({
  requestCopilotGeneration: vi.fn(),
  newCopilotGenerateNotificationId: () => 'archflow-copilot-generate-test',
}))
vi.mock('../../components/PublishModal', () => ({ PublishModal: () => null }))
vi.mock('../../components/NodePalette', () => ({ NodePalette: () => h('div', { 'data-testid': 'node-palette' }) }))
vi.mock('../../components/PropertyPanel', () => ({ PropertyPanel: () => h('div', { 'data-testid': 'property-panel' }) }))
vi.mock('../../components/CodeEditor/YamlEditor', () => ({ default: () => h('div', { 'data-testid': 'yaml-editor' }) }))
vi.mock('@mantine/notifications', () => ({ notifications: { show: showMock } }))

import { WorkflowEditor } from '../WorkflowEditorPage'
import { useFlowStore } from '../../components/FlowCanvas/store/useFlowStore'
import { useWorkflowStore } from '../../stores/workflow-store'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

/** O mesmo formato do ApiError de services/api: frase em `message`, código em `status`. */
function erroHttp(status: number, message: string): Error {
  return Object.assign(new Error(message), { name: 'ApiError', status })
}

const doc = {
  id: 'wf-1',
  metadata: { name: 'Fluxo', description: '', version: '1.0.0', category: '', tags: [] },
  configuration: {},
  steps: [{ id: 'a', type: 'LLM-CHAT', componentId: 'llm-chat', label: 'A', position: { x: 0, y: 0 }, configuration: {}, connections: [] }],
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
  entrarComo('DESIGNER')
  workflowApiMock.get.mockResolvedValue(doc)
  workflowApiMock.update.mockImplementation(async (_id: string, body: unknown) => body)
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function renderEditor(path = '/editor/wf-1') {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(
      h(MantineProvider, null,
        h(MemoryDataRouter, { initialEntries: [path] },
          h(Routes, null,
            h(Route, { path: '/editor/:id', element: h(WorkflowEditor) }),
            h(Route, { path: '/workflows', element: h('div', { 'data-testid': 'workflow-list' }) })))),
    )
  })
  return within(container)
}

describe('WorkflowEditor — fluxo da rota inacessível (RCHFLW-117)', () => {
  it.each([404, 403])('%i: mostra "Fluxo não encontrado", sem canvas nem Salvar, e volta para a lista', async (status) => {
    workflowApiMock.get.mockRejectedValue(erroHttp(status, 'Workflow not found: wf-1'))
    const ui = await renderEditor()

    const estado = await waitFor(() => ui.getByTestId('editor-not-found'))
    expect(estado.textContent).toContain('editor.notFoundTitle')
    expect(estado.textContent).toContain('editor.notFoundHint')
    expect(ui.queryByTestId('editor-load-error')).toBeNull()
    // Nada do editor: um Salvar ali gravaria um documento que não existe nesta conta.
    expect(ui.queryByTestId('editor-save')).toBeNull()
    expect(ui.queryByTestId('editor-run')).toBeNull()
    expect(ui.queryByTestId('editor-canvas-pane')).toBeNull()
    expect(ui.queryByTestId('editor-title')).toBeNull()

    await act(async () => { fireEvent.click(ui.getByRole('button', { name: 'editor.backToList' })) })
    // No router de dados a navegação é assíncrona (transição): espera a lista aparecer.
    await waitFor(() => expect(ui.getByTestId('workflow-list')).toBeTruthy())
    expect(workflowApiMock.update).not.toHaveBeenCalled()
  })

  it('404 com uma cópia velha do mesmo id no store: mostra "não encontrado", não a cópia', async () => {
    // O store guarda o currentWorkflow entre aberturas do editor; um fluxo excluído desde então não pode
    // reaparecer editável a partir da memória.
    useWorkflowStore.setState({ currentWorkflow: doc as any })
    workflowApiMock.get.mockRejectedValue(erroHttp(404, 'Workflow not found: wf-1'))
    const ui = await renderEditor()

    await waitFor(() => expect(ui.getByTestId('editor-not-found')).toBeTruthy())
    expect(ui.queryByTestId('editor-save')).toBeNull()
  })

  it('500: mostra o erro de carregamento (não "não encontrado") e o Atualizar refaz o fetch', async () => {
    workflowApiMock.get.mockRejectedValueOnce(erroHttp(500, 'Erro interno ao ler o fluxo'))
    const ui = await renderEditor()

    const estado = await waitFor(() => ui.getByTestId('editor-load-error'))
    expect(estado.textContent).toContain('editor.loadFailed')
    expect(estado.textContent).toContain('Erro interno ao ler o fluxo')
    expect(ui.queryByTestId('editor-not-found')).toBeNull()
    expect(ui.queryByTestId('editor-save')).toBeNull()

    await act(async () => { fireEvent.click(ui.getByRole('button', { name: 'common.refresh' })) })
    await waitFor(() => expect(ui.getByTestId('editor-title').textContent).toBe('Fluxo'))
    expect(workflowApiMock.get).toHaveBeenCalledTimes(2)
    expect(ui.queryByTestId('editor-load-error')).toBeNull()
  })

  it('falha de rede (sem status HTTP): erro de carregamento, não "não encontrado"', async () => {
    workflowApiMock.get.mockRejectedValue(new TypeError('Failed to fetch'))
    const ui = await renderEditor()

    const estado = await waitFor(() => ui.getByTestId('editor-load-error'))
    expect(estado.textContent).toContain('Failed to fetch')
    expect(ui.queryByTestId('editor-not-found')).toBeNull()
  })

  it('listagem que falha durante o fetch do fluxo: o editor carrega, o erro dela não é do fluxo', async () => {
    // A busca global e o copiloto chamam fetchWorkflows ao montar. Se a listagem falha enquanto o GET do
    // fluxo ainda está em voo e ele depois dá certo, o `error` do store fica com a falha da listagem — o
    // editor não pode tomá-la como falha de carregar o fluxo da rota.
    let resolverGet!: (d: unknown) => void
    workflowApiMock.get.mockReturnValue(new Promise(r => { resolverGet = r }))
    workflowApiMock.list.mockRejectedValue(erroHttp(500, 'list boom'))
    const ui = await renderEditor()

    await act(async () => { await useWorkflowStore.getState().fetchWorkflows() })
    expect(useWorkflowStore.getState().error).toBe('list boom')
    await act(async () => { resolverGet(doc) })

    await waitFor(() => expect(ui.getByTestId('editor-title').textContent).toBe('Fluxo'))
    expect(ui.getByTestId('editor-save')).toBeTruthy()
    expect(ui.queryByTestId('editor-load-error')).toBeNull()
    expect(ui.queryByTestId('editor-not-found')).toBeNull()
  })

  it('id válido: carrega o editor normalmente', async () => {
    const ui = await renderEditor()

    await waitFor(() => expect(ui.getByTestId('editor-title').textContent).toBe('Fluxo'))
    expect(ui.getByTestId('editor-save')).toBeTruthy()
    expect(ui.getByTestId('editor-canvas-pane')).toBeTruthy()
    expect(ui.queryByTestId('editor-not-found')).toBeNull()
    expect(ui.queryByTestId('editor-load-error')).toBeNull()
  })
})
