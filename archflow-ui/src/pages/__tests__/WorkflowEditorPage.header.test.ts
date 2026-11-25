/**
 * Cabeçalho do editor:
 * - o toast "Execução iniciada" mostra o id da EXECUÇÃO (vem do RUN_STARTED), não o do workflow — antes
 *   ele saía no clique com current.id rotulado como "ID da execução";
 * - o título do fluxo é renomeável (clique → campo; Enter confirma, Esc cancela) e o nome novo vai no
 *   próximo Salvar — inclusive o implícito da ida para a aba Código;
 * - na aba Código o título é só texto: o Salvar YAML grava o texto da tela (com o nome antigo) e recarrega
 *   do backend, então um nome trocado ali sumiria logo depois do toast "YAML salvo".
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
import type { AgUiEvent } from '../../services/agui-client'

const workflowApiMock = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn() }))
const executionApiMock = vi.hoisted(() => ({ get: vi.fn() }))
const yamlApiMock = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn() }))
const runMock = vi.hoisted(() => vi.fn((_id: string, _input: unknown, _onEvent: (ev: AgUiEvent) => void) => () => {}))
const showMock = vi.hoisted(() => vi.fn())

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    // O id interpolado entra no texto para o teste ver QUAL id o toast mostra.
    t: (k: string, o?: { id?: string }) => (o?.id ? `${k}:${o.id}` : k),
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
vi.mock('../../components/NodePalette', () => ({ NodePalette: () => null }))
vi.mock('../../components/PropertyPanel', () => ({ PropertyPanel: () => null }))
vi.mock('../../components/CodeEditor/YamlEditor', () => ({ default: () => null }))
vi.mock('@mantine/notifications', () => ({ notifications: { show: showMock } }))

import { WorkflowEditor } from '../WorkflowEditorPage'
import { useFlowStore } from '../../components/FlowCanvas/store/useFlowStore'
import { useWorkflowStore } from '../../stores/workflow-store'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
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
  // Papel que concede editar e executar: sem ele as ações somem (RCHFLW-74).
  entrarComo('DESIGNER')
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
  workflowApiMock.get.mockResolvedValue(doc)
  workflowApiMock.update.mockImplementation(async (_id: string, body: unknown) => body)
  executionApiMock.get.mockResolvedValue({ id: 'exec-9', workflowId: 'wf-1', status: 'COMPLETED', steps: [] })
  yamlApiMock.get.mockResolvedValue({ id: 'wf-1', yaml: 'id: wf-1\n', version: null })
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
        h(MemoryRouter, { initialEntries: ['/editor/wf-1'] },
          h(Routes, null, h(Route, { path: '/editor/:id', element: h(WorkflowEditor) })))),
    )
  })
  await waitFor(() => expect(useFlowStore.getState().canvasApi).not.toBeNull())
  return within(container)
}

function startedToasts() {
  return showMock.mock.calls.map(c => c[0]).filter(n => n.title === 'editor.notif.execStarted')
}

async function rename(ui: ReturnType<typeof within>, name: string, key: 'Enter' | 'Escape' = 'Enter') {
  await act(async () => { fireEvent.click(ui.getByTestId('editor-title')) })
  const input = ui.getByTestId('editor-title-input') as HTMLInputElement
  await act(async () => { fireEvent.change(input, { target: { value: name } }) })
  await act(async () => { fireEvent.keyDown(input, { key }) })
}

describe('WorkflowEditor — Executar diz que roda o rascunho (RCHFLW-89)', () => {
  it('o botão e o modal de entrada dizem rascunho, sem consultar a versão em produção', async () => {
    const ui = await renderEditor()
    const run = ui.getByTestId('editor-run')
    expect(run.textContent).toBe('editor.runDraft')

    await act(async () => { fireEvent.click(run) })
    const target = await waitFor(() => within(document.body).getByTestId('run-input-target'))
    expect(target.getAttribute('data-target')).toBe('draft')
    expect(target.textContent).toBe('runInput.target.draft')
  })
})

describe('WorkflowEditor — toast de execução iniciada', () => {
  it('mostra o id da execução do RUN_STARTED, nunca o id do workflow', async () => {
    const ui = await renderEditor()
    await act(async () => { fireEvent.click(ui.getByTestId('editor-run')) })
    const confirm = await waitFor(() => within(document.body).getByTestId('run-input-confirm'))
    await act(async () => { fireEvent.click(confirm) })
    await waitFor(() => expect(runMock).toHaveBeenCalledTimes(1))
    // Antes do RUN_STARTED não há id de execução para mostrar.
    expect(startedToasts()).toHaveLength(0)

    const onEvent = runMock.mock.calls[0][2]
    act(() => { onEvent({ type: 'RUN_STARTED', runId: 'exec-9' }) })

    const toasts = startedToasts()
    expect(toasts).toHaveLength(1)
    expect(toasts[0].message).toBe('editor.notif.execStartedMsg:exec-9')
    expect(toasts[0].message).not.toContain('wf-1')
  })
})

describe('WorkflowEditor — renomear o fluxo pelo título', () => {
  it('Enter troca o título e o Salvar grava metadata.name', async () => {
    const ui = await renderEditor()
    expect(ui.getByTestId('editor-title').textContent).toBe('Fluxo')

    await rename(ui, '  Triagem de pedidos  ')

    expect(ui.getByTestId('editor-title').textContent).toBe('Triagem de pedidos')
    expect(workflowApiMock.update).not.toHaveBeenCalled()

    await act(async () => { fireEvent.click(ui.getByTestId('editor-save')) })
    await waitFor(() => expect(workflowApiMock.update).toHaveBeenCalledTimes(1))
    const body = workflowApiMock.update.mock.calls[0][1] as { metadata: { name: string }; steps: unknown[] }
    expect(body.metadata.name).toBe('Triagem de pedidos')
    // O resto do documento segue o caminho normal do Salvar.
    expect(body.steps).toHaveLength(1)
  })

  it('Esc cancela e nome vazio é ignorado', async () => {
    const ui = await renderEditor()

    await rename(ui, 'Outro nome', 'Escape')
    expect(ui.getByTestId('editor-title').textContent).toBe('Fluxo')

    await rename(ui, '   ')
    expect(ui.getByTestId('editor-title').textContent).toBe('Fluxo')
    expect(useWorkflowStore.getState().currentWorkflow?.metadata.name).toBe('Fluxo')
  })

  it('renomear conta como edição não salva: a ida para a aba Código grava antes', async () => {
    const ui = await renderEditor()
    await rename(ui, 'Renomeado')

    await act(async () => { fireEvent.click(ui.getByTestId('editor-tab-code')) })

    await waitFor(() => expect(workflowApiMock.update).toHaveBeenCalledTimes(1))
    const body = workflowApiMock.update.mock.calls[0][1] as { metadata: { name: string } }
    expect(body.metadata.name).toBe('Renomeado')
  })

  it('na aba Código o título não é editável (o Salvar YAML descartaria o nome)', async () => {
    const ui = await renderEditor()
    await act(async () => { fireEvent.click(ui.getByTestId('editor-tab-code')) })
    // Sem edição pendente, a ida para a aba Código não grava nada.
    expect(workflowApiMock.update).not.toHaveBeenCalled()

    const title = ui.getByTestId('editor-title')
    expect(title.tagName).toBe('SPAN')
    expect(title.textContent).toBe('Fluxo')
    await act(async () => { fireEvent.click(title) })
    expect(ui.queryByTestId('editor-title-input')).toBeNull()
    expect(useWorkflowStore.getState().currentWorkflow?.metadata.name).toBe('Fluxo')

    // De volta ao canvas, o título volta a ser renomeável.
    await act(async () => { fireEvent.click(ui.getByTestId('editor-tab-canvas')) })
    await rename(ui, 'Depois do Código')
    expect(ui.getByTestId('editor-title').textContent).toBe('Depois do Código')
  })
})
