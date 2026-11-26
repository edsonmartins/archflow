/**
 * Publicar e Salvar YAML com a versão que o editor tem na tela.
 *
 * - RCHFLW-146: Publicar com alterações não salvas criava a versão imutável a partir do rascunho gravado — sem
 *   essas alterações — e a punha em PRODUCTION, com o editor ainda "NÃO SALVO". Agora o Publicar salva antes
 *   (como o Executar rascunho) e publica com o updatedAt do que salvou no If-Match; um 409 abre o aviso de
 *   conflito e não publica nada.
 * - RCHFLW-174: o Salvar YAML não mandava versão, e um YAML aberto antes de outra sessão salvar apagava esse
 *   salvamento. Agora leva o updatedAt do GET que trouxe o texto, e o 409 abre o mesmo aviso do canvas.
 *
 * <p>O PublishModal é o de verdade; só a rede, os painéis laterais e as notificações são falsos.</p>
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

const apiMock = vi.hoisted(() => {
  class ApiError extends Error {
    constructor(public status: number, message: string) {
      super(message)
    }
  }
  return {
    ApiError,
    workflowApi: {
      get: vi.fn(), update: vi.fn(), list: vi.fn(),
      publish: vi.fn(), versions: vi.fn(), deployment: vi.fn(), deploy: vi.fn(),
    },
  }
})
const workflowApiMock = apiMock.workflowApi
const yamlApiMock = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn() }))
const showMock = vi.hoisted(() => vi.fn())

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('../../services/api', () => ({
  workflowApi: apiMock.workflowApi, executionApi: {}, ApiError: apiMock.ApiError,
}))
vi.mock('../../services/workflow-yaml-api', () => ({ workflowYamlApi: yamlApiMock }))
vi.mock('../../services/agui-client', () => ({ runAgUiWorkflow: vi.fn(() => () => {}) }))
vi.mock('../../components/copilot/CopilotGenerateBridge', () => ({
  requestCopilotGeneration: vi.fn(),
  newCopilotGenerateNotificationId: () => 'archflow-copilot-generate-test',
}))
vi.mock('../../components/NodePalette', () => ({ NodePalette: () => null }))
vi.mock('../../components/PropertyPanel', () => ({ PropertyPanel: () => null }))
vi.mock('@mantine/notifications', () => ({ notifications: { show: showMock, hide: vi.fn() } }))

import { WorkflowEditor } from '../WorkflowEditorPage'
import { useFlowStore } from '../../components/FlowCanvas/store/useFlowStore'
import { useWorkflowStore } from '../../stores/workflow-store'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const LIDO = '2026-11-25T10:00:00Z'
const SALVO = '2026-11-25T11:00:00Z'

function doc(updatedAt = LIDO, prompt = 'antigo') {
  return {
    id: 'wf-1',
    updatedAt,
    metadata: { name: 'Fluxo', description: '', version: '1.0.0', category: '', tags: [] },
    configuration: {},
    steps: [{ id: 'a', type: 'LLM-CHAT', componentId: 'llm-chat', label: 'A', position: { x: 0, y: 0 },
      configuration: { systemPrompt: prompt }, connections: [] }],
  }
}

function version(n: number) {
  return { id: `wfv-${n}`, workflowId: 'wf-1', number: n, document: doc(), comment: null,
    createdAt: '2026-11-25T12:00:00Z' }
}

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
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
  useWorkflowStore.setState({ currentWorkflow: null, workflows: [], error: null, loading: false })
  useFlowStore.setState({ nodes: [], edges: [], canvasApi: null, pendingCanvasOps: [], isExecuting: false })
  workflowApiMock.get.mockResolvedValue(doc())
  workflowApiMock.update.mockImplementation(async (_id: string, body: Record<string, unknown>) =>
    ({ ...body, updatedAt: SALVO }))
  workflowApiMock.list.mockResolvedValue([])
  workflowApiMock.versions.mockResolvedValue([])
  workflowApiMock.deployment.mockRejectedValue(new apiMock.ApiError(404, 'not found'))
  workflowApiMock.publish.mockResolvedValue({
    version: version(1),
    deployment: { workflowId: 'wf-1', environment: 'PRODUCTION', versionId: 'wfv-1', deployedAt: 'x' },
  })
  yamlApiMock.get.mockResolvedValue({ id: 'wf-1', yaml: 'id: wf-1\n', version: null, updatedAt: LIDO })
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

async function click(el: HTMLElement) {
  await act(async () => { fireEvent.click(el) })
}

const body = () => within(document.body)

async function publicar(ui: ReturnType<typeof within>) {
  await click(ui.getByTestId('editor-publish'))
  await click(await waitFor(() => body().getByText('editor.publish.createVersion')))
}

describe('WorkflowEditor — Publicar com alterações não salvas (RCHFLW-146)', () => {
  it('salva o que está na tela antes e publica exatamente essa versão (If-Match com o updatedAt salvo)', async () => {
    const ui = await renderEditor()
    await act(async () => { useFlowStore.getState().updateNodeConfig('a', 'systemPrompt', 'limite 2000') })
    expect(ui.getByTestId('editor-unsaved')).toBeTruthy()

    await click(ui.getByTestId('editor-publish'))
    // O modal avisa que vai salvar antes de criar a versão.
    expect(await waitFor(() => body().getByTestId('publish-unsaved-notice'))).toBeTruthy()
    await click(body().getByText('editor.publish.createVersion'))

    await waitFor(() => expect(workflowApiMock.publish).toHaveBeenCalledTimes(1))
    expect(workflowApiMock.update).toHaveBeenCalledTimes(1)
    const gravado = workflowApiMock.update.mock.calls[0][1] as { steps: Array<{ configuration: unknown }> }
    expect(gravado.steps[0].configuration).toEqual({ systemPrompt: 'limite 2000' })
    expect(workflowApiMock.update.mock.invocationCallOrder[0])
      .toBeLessThan(workflowApiMock.publish.mock.invocationCallOrder[0])
    expect(workflowApiMock.publish.mock.calls[0][3]).toBe(SALVO)
    await waitFor(() => expect(ui.queryByTestId('editor-unsaved')).toBeNull())
  })

  it('sem alteração pendente não grava nada, e publica com a versão carregada', async () => {
    const ui = await renderEditor()
    await publicar(ui)

    await waitFor(() => expect(workflowApiMock.publish).toHaveBeenCalledTimes(1))
    expect(workflowApiMock.update).not.toHaveBeenCalled()
    expect(workflowApiMock.publish.mock.calls[0][3]).toBe(LIDO)
    expect(body().queryByTestId('publish-unsaved-notice')).toBeNull()
  })

  it('o Salvar antes de publicar levou 409: abre o aviso de conflito e não publica', async () => {
    workflowApiMock.update.mockRejectedValue(new apiMock.ApiError(409, 'changed elsewhere'))
    const ui = await renderEditor()
    await act(async () => { useFlowStore.getState().updateNodeConfig('a', 'systemPrompt', 'limite 2000') })

    await publicar(ui)

    await waitFor(() => expect(body().getByTestId('editor-conflict-modal')).toBeTruthy())
    expect(workflowApiMock.publish).not.toHaveBeenCalled()
  })

  it('o rascunho mudou no servidor depois do carregamento: o 409 do Publicar abre o aviso de conflito', async () => {
    workflowApiMock.publish.mockRejectedValue(new apiMock.ApiError(409, 'changed elsewhere'))
    const ui = await renderEditor()

    await publicar(ui)

    await waitFor(() => expect(body().getByTestId('editor-conflict-modal')).toBeTruthy())
    expect(showMock).not.toHaveBeenCalledWith(expect.objectContaining({ color: 'teal' }))
  })
})

describe('WorkflowEditor — Salvar YAML com a versão lida (RCHFLW-174)', () => {
  async function editarYaml(ui: ReturnType<typeof within>) {
    await click(ui.getByTestId('editor-tab-code'))
    const textarea = await waitFor(() => ui.getByRole('textbox') as HTMLTextAreaElement)
    await act(async () => { fireEvent.change(textarea, { target: { value: 'id: wf-1\nmetadata: {}\n' } }) })
  }

  it('o PUT do YAML leva o updatedAt do GET que trouxe o texto', async () => {
    yamlApiMock.update.mockResolvedValue({ id: 'wf-1', yaml: 'id: wf-1\n', version: null, updatedAt: SALVO })
    const ui = await renderEditor()
    await editarYaml(ui)

    await click(ui.getByTestId('editor-save'))

    await waitFor(() => expect(yamlApiMock.update).toHaveBeenCalledTimes(1))
    expect(yamlApiMock.update.mock.calls[0][2]).toBe(LIDO)
  })

  it('409 no Salvar YAML: o aviso de conflito, e o Sobrescrever regrava o YAML sem a versão', async () => {
    yamlApiMock.update.mockRejectedValueOnce(Object.assign(new Error('changed elsewhere'), { status: 409 }))
    yamlApiMock.update.mockResolvedValueOnce({ id: 'wf-1', yaml: 'id: wf-1\n', version: null, updatedAt: SALVO })
    const ui = await renderEditor()
    await editarYaml(ui)

    await click(ui.getByTestId('editor-save'))

    await waitFor(() => expect(body().getByTestId('editor-conflict-modal')).toBeTruthy())
    await click(body().getByTestId('editor-conflict-overwrite'))
    await waitFor(() => expect(yamlApiMock.update).toHaveBeenCalledTimes(2))
    expect(yamlApiMock.update.mock.calls[1][1]).toBe('id: wf-1\nmetadata: {}\n')
    expect(yamlApiMock.update.mock.calls[1][2]).toBeUndefined()
    // O Sobrescrever do YAML não grava o canvas por cima.
    expect(workflowApiMock.update).not.toHaveBeenCalled()
  })
})

describe('WorkflowEditor — a versão que o Publicar e o Salvar YAML mandam depois de outra gravação desta sessão', () => {
  const OUTRA = '2026-11-25T11:30:00Z'

  async function abrirCodigo(ui: ReturnType<typeof within>) {
    await click(ui.getByTestId('editor-tab-code'))
    return await waitFor(() => ui.getByRole('textbox') as HTMLTextAreaElement)
  }

  it('Sobrescrever (canvas) do 409 do Publicar na aba Código recarrega o YAML: o Salvar YAML seguinte não leva 409',
    async () => {
      workflowApiMock.publish.mockRejectedValueOnce(new apiMock.ApiError(409, 'changed elsewhere'))
      const ui = await renderEditor()
      await abrirCodigo(ui)
      // Depois do Sobrescrever, o GET do YAML traz o texto da gravação forçada, com a versão dela.
      yamlApiMock.get.mockResolvedValue({ id: 'wf-1', yaml: 'id: wf-1\n', version: null, updatedAt: SALVO })
      yamlApiMock.update.mockResolvedValue({ id: 'wf-1', yaml: 'id: wf-1\n', version: null, updatedAt: OUTRA })

      await publicar(ui)
      await waitFor(() => expect(body().getByTestId('editor-conflict-modal')).toBeTruthy())
      await click(body().getByTestId('editor-conflict-overwrite'))
      await waitFor(() => expect(workflowApiMock.update).toHaveBeenCalledTimes(1))
      await waitFor(() => expect(yamlApiMock.get).toHaveBeenCalledTimes(2))

      const textarea = await waitFor(() => ui.getByRole('textbox') as HTMLTextAreaElement)
      await act(async () => { fireEvent.change(textarea, { target: { value: 'id: wf-1\nmetadata: {}\n' } }) })
      await click(ui.getByTestId('editor-save'))

      await waitFor(() => expect(yamlApiMock.update).toHaveBeenCalledTimes(1))
      expect(yamlApiMock.update.mock.calls[0][2]).toBe(SALVO)
    })

  it('YAML pendente: o Publicar manda a versão do PUT do YAML, não a do reload que veio depois', async () => {
    yamlApiMock.update.mockResolvedValue({ id: 'wf-1', yaml: 'id: wf-1\n', version: null, updatedAt: SALVO })
    const ui = await renderEditor()
    const textarea = await abrirCodigo(ui)
    await act(async () => { fireEvent.change(textarea, { target: { value: 'id: wf-1\nmetadata: {}\n' } }) })
    // Outra sessão grava entre o PUT do YAML e o GET que reconstrói o canvas.
    workflowApiMock.get.mockResolvedValue(doc(OUTRA))

    await publicar(ui)

    await waitFor(() => expect(workflowApiMock.publish).toHaveBeenCalledTimes(1))
    expect(yamlApiMock.update).toHaveBeenCalledTimes(1)
    expect(workflowApiMock.publish.mock.calls[0][3]).toBe(SALVO)
  })

  it('Salvar YAML gravou e o reload do canvas falhou: o Publicar manda a versão do YAML gravado', async () => {
    yamlApiMock.update.mockResolvedValue({ id: 'wf-1', yaml: 'id: wf-1\n', version: null, updatedAt: SALVO })
    const ui = await renderEditor()
    const textarea = await abrirCodigo(ui)
    await act(async () => { fireEvent.change(textarea, { target: { value: 'id: wf-1\nmetadata: {}\n' } }) })
    workflowApiMock.get.mockRejectedValue(new apiMock.ApiError(503, 'indisponível'))
    await click(ui.getByTestId('editor-save'))
    await waitFor(() => expect(yamlApiMock.update).toHaveBeenCalledTimes(1))
    expect(useWorkflowStore.getState().currentWorkflow?.updatedAt).toBe(LIDO)

    await publicar(ui)

    await waitFor(() => expect(workflowApiMock.publish).toHaveBeenCalledTimes(1))
    expect(yamlApiMock.update).toHaveBeenCalledTimes(1)
    expect(workflowApiMock.publish.mock.calls[0][3]).toBe(SALVO)
  })
})
