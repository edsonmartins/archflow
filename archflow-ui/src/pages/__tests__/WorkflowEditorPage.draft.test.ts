/**
 * RCHFLW-131: "Novo Fluxo" (Começar do zero e Gerar com IA) gravava um fluxo vazio antes de abrir o editor. Quem
 * fechava sem fazer nada deixava um "Fluxo sem título" com 0 passos, e o filtro de Execuções acumulava vários.
 *
 * Agora o modal abre um rascunho só em memória (/editor?new=1): o fluxo nasce no primeiro Salvar, ou quando o
 * Gerar com IA monta o primeiro nó; fechar sem fazer nada não deixa registro. A troca para /editor/:id não
 * recarrega nem reconstrói o canvas.
 *
 * Também a corrida vista logo após o login: o ?ai=1 abre o pedido antes de o fluxo carregar, e um Gerar nessa
 * janela mandava o copiloto a um editor sem canvas (ele acabava em /editor sem id). O Gerar só fica disponível
 * com o canvas de pé.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { entrarComo } from '../../lib/__tests__/papeisDeTeste'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { Route, Routes } from 'react-router-dom'
import { MemoryDataRouter, ultimoRoteador } from '../../lib/__tests__/roteadorDeTeste'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'

const workflowApiMock = vi.hoisted(() => ({
  get: vi.fn(), update: vi.fn(), create: vi.fn(), list: vi.fn(), execute: vi.fn(), setStatus: vi.fn(),
  delete: vi.fn(), deployment: vi.fn(), versions: vi.fn(),
}))
const generateMock = vi.hoisted(() => vi.fn())

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('../../services/api', () => ({ workflowApi: workflowApiMock, executionApi: { list: vi.fn() } }))
vi.mock('../../services/workflow-yaml-api', () => ({ workflowYamlApi: { get: vi.fn(), update: vi.fn() } }))
vi.mock('../../services/agui-client', () => ({ runAgUiWorkflow: vi.fn(() => () => {}) }))
vi.mock('../../components/copilot/CopilotGenerateBridge', () => ({
  requestCopilotGeneration: generateMock,
  newCopilotGenerateNotificationId: () => 'archflow-copilot-generate-test',
}))
vi.mock('../../components/PublishModal', () => ({ PublishModal: () => null }))
vi.mock('../../components/NodePalette', () => ({ NodePalette: () => null }))
vi.mock('../../components/PropertyPanel', () => ({ PropertyPanel: () => null }))
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn(), hide: vi.fn() } }))

import { WorkflowEditor } from '../WorkflowEditorPage'
import WorkflowListPage from '../WorkflowListPage'
import { useFlowStore } from '../../components/FlowCanvas/store/useFlowStore'
import { useWorkflowStore } from '../../stores/workflow-store'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const outro = {
  id: 'wf-velho',
  updatedAt: '2026-11-25T09:00:00Z',
  metadata: { name: 'Fluxo aberto antes', description: '', version: '1.0.0', category: '', tags: [] },
  configuration: {},
  steps: [{ id: 'x', type: 'LLM-CHAT', componentId: 'llm-chat', label: 'X', position: { x: 0, y: 0 },
    configuration: {}, connections: [] }],
}

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
  entrarComo('DESIGNER')
  vi.clearAllMocks()
  sessionStorage.clear()
  ;(globalThis as any).ResizeObserver = ResizeObserverStub
  ;(globalThis as any).DOMMatrixReadOnly ??= class { m22 = 1 }
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  // O Textarea autosize do Mantine escuta document.fonts, que o jsdom não tem.
  ;(document as any).fonts ??= { addEventListener() {}, removeEventListener() {} }
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  useWorkflowStore.setState({ currentWorkflow: null, workflows: [], error: null, loading: false })
  useFlowStore.setState({ nodes: [], edges: [], canvasApi: null, pendingCanvasOps: [], isExecuting: false })
  workflowApiMock.list.mockResolvedValue([])
  workflowApiMock.create.mockImplementation(async (body: Record<string, unknown>) =>
    ({ ...body, id: 'wf-novo', status: 'draft', updatedAt: '2026-11-25T12:00:00Z' }))
  workflowApiMock.update.mockImplementation(async (id: string, body: Record<string, unknown>) =>
    ({ ...body, id, updatedAt: '2026-11-25T12:30:00Z' }))
  workflowApiMock.get.mockImplementation(async (id: string) => (id === 'wf-velho' ? outro : null))
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function render(path: string, { esperarCanvas = true } = {}) {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(
      h(MantineProvider, null,
        h(MemoryDataRouter, { initialEntries: [path] },
          h(Routes, null,
            h(Route, { path: '/editor', element: h(WorkflowEditor) }),
            h(Route, { path: '/editor/:id', element: h(WorkflowEditor) }),
            h(Route, { path: '/workflows', element: h(WorkflowListPage) })))),
    )
  })
  if (esperarCanvas) await waitFor(() => expect(useFlowStore.getState().canvasApi).not.toBeNull())
  return within(container)
}

async function click(el: HTMLElement) {
  await act(async () => { fireEvent.click(el) })
}

const body = () => within(document.body)
const location = () => `${ultimoRoteador!.state.location.pathname}${ultimoRoteador!.state.location.search}`

describe('Novo Fluxo abre um rascunho, sem gravar (RCHFLW-131)', () => {
  it('Começar do zero e Gerar com IA não chamam o POST; abrem o editor no rascunho', async () => {
    await render('/workflows', { esperarCanvas: false })
    await click(await waitFor(() => body().getByRole('button', { name: 'workflows.newWorkflow' })))
    await click(await waitFor(() => body().getByTestId('create-blank')))

    await waitFor(() => expect(location()).toBe('/editor?new=1'))
    expect(workflowApiMock.create).not.toHaveBeenCalled()

    await act(async () => { await ultimoRoteador!.navigate('/workflows?new=ai') })
    await waitFor(() => expect(ultimoRoteador!.state.location.pathname).toBe('/editor'))
    expect(new URLSearchParams(ultimoRoteador!.state.location.search).get('new')).toBe('1')
    expect(workflowApiMock.create).not.toHaveBeenCalled()
  })

  it('o rascunho é um fluxo novo — não o último aberto — e não chama a API', async () => {
    useWorkflowStore.setState({ currentWorkflow: outro as any })
    const ui = await render('/editor?new=1')

    expect(ui.getByTestId('editor-title').textContent).toBe('workflows.untitledName')
    expect(useFlowStore.getState().nodes).toHaveLength(0)
    expect((ui.getByTestId('editor-publish') as HTMLButtonElement).disabled).toBe(true)
    expect((ui.getByTestId('editor-tab-code') as HTMLButtonElement).disabled).toBe(true)
    expect(ui.queryByTestId('editor-unsaved')).toBeNull()
    expect(workflowApiMock.get).not.toHaveBeenCalled()
    expect(workflowApiMock.create).not.toHaveBeenCalled()
  })

  it('fechar sem fazer nada não deixa registro nem pergunta nada', async () => {
    const ui = await render('/editor?new=1')

    await act(async () => { await ultimoRoteador!.navigate('/workflows') })

    await waitFor(() => expect(location()).toBe('/workflows'))
    expect(body().queryByTestId('editor-unsaved-modal')).toBeNull()
    expect(ui.queryByTestId('editor-title')).toBeNull()
    expect(workflowApiMock.create).not.toHaveBeenCalled()
    expect(workflowApiMock.update).not.toHaveBeenCalled()
    // O rascunho não fica como "último fluxo aberto" do /editor.
    expect(useWorkflowStore.getState().currentWorkflow).toBeNull()
  })

  it('o primeiro Salvar cria o fluxo com o que está na tela e a rota ganha o id, sem recarregar o canvas', async () => {
    const ui = await render('/editor?new=1')
    await act(async () => { useFlowStore.getState().canvasApi!.addNode({ nodeType: 'llm-chat', label: 'Primeiro' }) })
    expect(ui.getByTestId('editor-unsaved')).toBeTruthy()

    await click(ui.getByTestId('editor-save'))

    await waitFor(() => expect(location()).toBe('/editor/wf-novo'))
    expect(workflowApiMock.create).toHaveBeenCalledTimes(1)
    const criado = workflowApiMock.create.mock.calls[0][0] as { id?: unknown; steps: Array<{ label: string }> }
    expect(criado.id).toBeUndefined()
    expect(criado.steps.map(s => s.label)).toEqual(['Primeiro'])
    expect(workflowApiMock.update).not.toHaveBeenCalled()
    expect(workflowApiMock.get).not.toHaveBeenCalled()
    // O mesmo canvas, sem remontar: o nó e o histórico de desfazer continuam lá.
    expect(useFlowStore.getState().nodes.map(n => n.data.label)).toEqual(['Primeiro'])
    expect((ui.getByTestId('canvas-undo') as HTMLButtonElement).disabled).toBe(false)
    expect(ui.queryByTestId('editor-unsaved')).toBeNull()
    expect((ui.getByTestId('editor-publish') as HTMLButtonElement).disabled).toBe(false)

    // O Salvar seguinte é um PUT com a versão que o POST devolveu.
    await act(async () => { useFlowStore.getState().canvasApi!.addNode({ nodeType: 'output', label: 'Saída' }) })
    await click(ui.getByTestId('editor-save'))
    await waitFor(() => expect(workflowApiMock.update).toHaveBeenCalledTimes(1))
    expect(workflowApiMock.update.mock.calls[0][0]).toBe('wf-novo')
    expect(workflowApiMock.update.mock.calls[0][2]).toBe('2026-11-25T12:00:00Z')
    expect(workflowApiMock.create).toHaveBeenCalledTimes(1)
  })

  it('rascunho com nó e saída: o aviso de não salvo aparece; Salvar e sair cria e então sai', async () => {
    await render('/editor?new=1')
    await act(async () => { useFlowStore.getState().canvasApi!.addNode({ nodeType: 'llm-chat', label: 'A' }) })

    await act(async () => { await ultimoRoteador!.navigate('/workflows') })
    await waitFor(() => body().getByTestId('editor-unsaved-modal'))
    expect(workflowApiMock.create).not.toHaveBeenCalled()

    await click(body().getByTestId('editor-unsaved-save'))

    await waitFor(() => expect(location()).toBe('/workflows'))
    expect(workflowApiMock.create).toHaveBeenCalledTimes(1)
  })

  it('Gerar com IA no rascunho: nada é gravado até o copiloto montar o primeiro nó; aí o fluxo nasce', async () => {
    const ui = await render('/editor?new=1&ai=1')
    const goal = await waitFor(() => body().getByTestId('editor-ai-goal') as HTMLTextAreaElement)
    await act(async () => { fireEvent.change(goal, { target: { value: 'triagem de tickets' } }) })
    await click(body().getByTestId('editor-ai-generate'))

    expect(generateMock).toHaveBeenCalledTimes(1)
    expect(workflowApiMock.create).not.toHaveBeenCalled()

    // O copiloto monta pelo canvas (addNode das ferramentas do front, que leem a API do store a cada chamada).
    await act(async () => { useFlowStore.getState().canvasApi!.addNode({ nodeType: 'input', label: 'Entrada' }) })
    await waitFor(() => expect(workflowApiMock.create).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(location()).toBe('/editor/wf-novo'))

    // Os nós seguintes entram no mesmo canvas, como alteração não salva — sem outro POST.
    await waitFor(() => expect(useFlowStore.getState().canvasApi).not.toBeNull())
    await act(async () => { useFlowStore.getState().canvasApi!.addNode({ nodeType: 'llm-chat', label: 'Classificar' }) })
    expect(useFlowStore.getState().nodes.map(n => n.data.label)).toEqual(['Entrada', 'Classificar'])
    expect(ui.getByTestId('editor-unsaved')).toBeTruthy()
    expect(workflowApiMock.create).toHaveBeenCalledTimes(1)
    expect(workflowApiMock.get).not.toHaveBeenCalled()
  })

  it('POST do Gerar com IA no ar e o usuário descarta e sai: a resposta não o leva de volta ao editor', async () => {
    let responder!: () => void
    workflowApiMock.create.mockImplementationOnce((b: Record<string, unknown>) =>
      new Promise(r => { responder = () => r({ ...b, id: 'wf-novo', status: 'draft', updatedAt: '2026-11-25T12:00:00Z' }) }))
    await render('/editor?new=1&ai=1')
    const goal = await waitFor(() => body().getByTestId('editor-ai-goal') as HTMLTextAreaElement)
    await act(async () => { fireEvent.change(goal, { target: { value: 'triagem de tickets' } }) })
    await click(body().getByTestId('editor-ai-generate'))
    await act(async () => { useFlowStore.getState().canvasApi!.addNode({ nodeType: 'input', label: 'Entrada' }) })
    await waitFor(() => expect(workflowApiMock.create).toHaveBeenCalledTimes(1))

    await act(async () => { await ultimoRoteador!.navigate('/workflows') })
    await waitFor(() => body().getByTestId('editor-unsaved-modal'))
    await click(body().getByTestId('editor-unsaved-discard'))
    await waitFor(() => expect(location()).toBe('/workflows'))

    await act(async () => { responder() })
    await act(async () => { await new Promise(r => setTimeout(r, 0)) })

    expect(location()).toBe('/workflows')
    expect(useWorkflowStore.getState().currentWorkflow).toBeNull()
    expect(workflowApiMock.create).toHaveBeenCalledTimes(1)
  })

  it('POST do Gerar com IA no ar e Salvar e sair: um só POST, e o Salvar e sair grava por cima dele', async () => {
    let responder!: () => void
    workflowApiMock.create.mockImplementationOnce((b: Record<string, unknown>) =>
      new Promise(r => { responder = () => r({ ...b, id: 'wf-novo', status: 'draft', updatedAt: '2026-11-25T12:00:00Z' }) }))
    await render('/editor?new=1&ai=1')
    const goal = await waitFor(() => body().getByTestId('editor-ai-goal') as HTMLTextAreaElement)
    await act(async () => { fireEvent.change(goal, { target: { value: 'triagem de tickets' } }) })
    await click(body().getByTestId('editor-ai-generate'))
    await act(async () => { useFlowStore.getState().canvasApi!.addNode({ nodeType: 'input', label: 'Entrada' }) })
    await waitFor(() => expect(workflowApiMock.create).toHaveBeenCalledTimes(1))

    await act(async () => { await ultimoRoteador!.navigate('/workflows') })
    await waitFor(() => body().getByTestId('editor-unsaved-modal'))
    await click(body().getByTestId('editor-unsaved-save'))
    await act(async () => { responder() })

    await waitFor(() => expect(location()).toBe('/workflows'))
    expect(workflowApiMock.create).toHaveBeenCalledTimes(1)
    expect(workflowApiMock.update).toHaveBeenCalledTimes(1)
    expect(workflowApiMock.update.mock.calls[0][0]).toBe('wf-novo')
    expect(workflowApiMock.update.mock.calls[0][2]).toBe('2026-11-25T12:00:00Z')
  })

  it('?ai=1 antes de o fluxo carregar: o Gerar fica indisponível até o canvas existir', async () => {
    let responder!: (d: unknown) => void
    workflowApiMock.get.mockReturnValue(new Promise(r => { responder = r }))
    await render('/editor/wf-velho?ai=1', { esperarCanvas: false })
    const goal = await waitFor(() => body().getByTestId('editor-ai-goal') as HTMLTextAreaElement)
    await act(async () => { fireEvent.change(goal, { target: { value: 'triagem' } }) })

    expect((body().getByTestId('editor-ai-generate') as HTMLButtonElement).disabled).toBe(true)

    await act(async () => { responder(outro) })
    await waitFor(() => expect(useFlowStore.getState().canvasApi).not.toBeNull())
    await waitFor(() => expect((body().getByTestId('editor-ai-generate') as HTMLButtonElement).disabled).toBe(false))
  })
})

/**
 * RCHFLW-143: rascunho sujo, item "Editor" do menu (/editor — o mesmo editor, o rascunho continua na tela) e
 * Voltar do navegador para /editor?new=1. A troca era só de query, o bloqueio a deixava passar, e a rota do
 * rascunho criava um rascunho vazio por cima do que estava na tela — sem aviso e sem nada gravado.
 */
describe('Voltar para /editor?new=1 com o rascunho sujo (RCHFLW-143)', () => {
  async function rascunhoSujoNoEditor() {
    await render('/editor?new=1')
    await act(async () => { useFlowStore.getState().canvasApi!.addNode({ nodeType: 'llm-chat', label: 'A' }) })
    await act(async () => { useFlowStore.getState().canvasApi!.addNode({ nodeType: 'condition', label: 'B' }) })
    // O item "Editor" do menu: mesmo caminho, sai do ?new=1 e o rascunho continua na tela, sem perguntar.
    await act(async () => { await ultimoRoteador!.navigate('/editor') })
    expect(location()).toBe('/editor')
    expect(body().queryByTestId('editor-unsaved-modal')).toBeNull()
    expect(useFlowStore.getState().nodes).toHaveLength(2)
  }

  it('o Voltar pergunta, e Continuar editando mantém o rascunho e os nós', async () => {
    await rascunhoSujoNoEditor()

    await act(async () => { await ultimoRoteador!.navigate(-1) })

    await waitFor(() => expect(body().getByTestId('editor-unsaved-modal')).toBeTruthy())
    expect(location()).toBe('/editor')
    expect(useFlowStore.getState().nodes).toHaveLength(2)

    await click(body().getByTestId('editor-unsaved-stay'))
    expect(location()).toBe('/editor')
    expect(useFlowStore.getState().nodes.map(n => n.data.label)).toEqual(['A', 'B'])
    expect(workflowApiMock.create).not.toHaveBeenCalled()
  })

  it('Descartar e sair abre o rascunho novo, vazio', async () => {
    await rascunhoSujoNoEditor()
    await act(async () => { await ultimoRoteador!.navigate(-1) })
    await waitFor(() => body().getByTestId('editor-unsaved-modal'))

    await click(body().getByTestId('editor-unsaved-discard'))

    await waitFor(() => expect(location()).toBe('/editor?new=1'))
    await waitFor(() => expect(useFlowStore.getState().nodes).toHaveLength(0))
    expect(workflowApiMock.create).not.toHaveBeenCalled()
  })

  it('tirar só o ?ai=1 do rascunho continua sem perguntar', async () => {
    await render('/editor?new=1&ai=1')
    await act(async () => { useFlowStore.getState().canvasApi!.addNode({ nodeType: 'llm-chat', label: 'A' }) })

    await act(async () => { await ultimoRoteador!.navigate('/editor?new=1', { replace: true }) })

    expect(body().queryByTestId('editor-unsaved-modal')).toBeNull()
    expect(location()).toBe('/editor?new=1')
    expect(useFlowStore.getState().nodes).toHaveLength(1)
  })
})
