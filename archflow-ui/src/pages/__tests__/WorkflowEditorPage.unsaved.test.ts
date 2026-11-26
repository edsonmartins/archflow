/**
 * Alterações não salvas e conflito no Salvar.
 *
 * - RCHFLW-124: mudar o prompt e clicar em "Fluxos" no menu descartava a alteração sem aviso, e o cabeçalho não
 *   dizia "não salvo". Agora há o indicador, e sair do editor com alterações pede confirmação num modal do app,
 *   pelo bloqueio de navegação do router (useBlocker) — Continuar editando, Descartar e sair, Salvar e sair.
 * - RCHFLW-125: com outra sessão gravando no meio, o Salvar respondia "Fluxo salvo" e apagava a mudança dela.
 *   Agora o PUT leva o updatedAt lido; o 409 abre o aviso "alterado em outro lugar", com Recarregar e
 *   Sobrescrever.
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

const workflowApiMock = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn(), list: vi.fn() }))
const yamlApiMock = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn() }))
const showMock = vi.hoisted(() => vi.fn())

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('../../services/api', () => ({ workflowApi: workflowApiMock, executionApi: {} }))
vi.mock('../../services/workflow-yaml-api', () => ({ workflowYamlApi: yamlApiMock }))
vi.mock('../../services/agui-client', () => ({ runAgUiWorkflow: vi.fn(() => () => {}) }))
vi.mock('../../components/copilot/CopilotGenerateBridge', () => ({
  requestCopilotGeneration: vi.fn(),
  newCopilotGenerateNotificationId: () => 'archflow-copilot-generate-test',
}))
vi.mock('../../components/PublishModal', () => ({ PublishModal: () => null }))
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

function doc(name = 'Fluxo', updatedAt = '2026-11-25T10:00:00Z') {
  return {
    id: 'wf-1',
    updatedAt,
    metadata: { name, description: '', version: '1.0.0', category: '', tags: [] },
    configuration: {},
    steps: [{ id: 'a', type: 'LLM-CHAT', componentId: 'llm-chat', label: 'A', position: { x: 0, y: 0 },
      configuration: { systemPrompt: 'antigo' }, connections: [] }],
  }
}

function conflito() {
  return Object.assign(new Error('Workflow was changed elsewhere since it was loaded'), { status: 409 })
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
  // O backend devolve o que recebeu, com um updatedAt novo — como o PUT real.
  workflowApiMock.update.mockImplementation(async (_id: string, body: Record<string, unknown>) =>
    ({ ...body, updatedAt: '2026-11-25T11:00:00Z' }))
  yamlApiMock.get.mockResolvedValue({ id: 'wf-1', yaml: 'id: wf-1\n', version: null })
  workflowApiMock.list.mockResolvedValue([])
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
          h(Routes, null,
            // As duas rotas do App, com o mesmo elemento: ir de uma para a outra não remonta o editor.
            h(Route, { path: '/editor', element: h(WorkflowEditor) }),
            h(Route, { path: '/editor/:id', element: h(WorkflowEditor) }),
            h(Route, { path: '/workflows', element: h('div', { 'data-testid': 'workflow-list' }) })))),
    )
  })
  await waitFor(() => expect(useFlowStore.getState().canvasApi).not.toBeNull())
  return within(container)
}

async function click(el: HTMLElement) {
  await act(async () => { fireEvent.click(el) })
}

/** A mudança do QA: o System prompt do LLM Chat. */
async function editarPrompt() {
  await act(async () => { useFlowStore.getState().updateNodeConfig('a', 'systemPrompt', 'novo') })
}

/** Como o item "Fluxos" do menu lateral: uma navegação do router. */
async function irParaFluxos() {
  await act(async () => { await ultimoRoteador!.navigate('/workflows') })
}

const body = () => within(document.body)

describe('WorkflowEditor — alterações não salvas (RCHFLW-124)', () => {
  it('o cabeçalho mostra "não salvo" só enquanto há alteração, e o Salvar apaga o indicador', async () => {
    const ui = await renderEditor()
    expect(ui.queryByTestId('editor-unsaved')).toBeNull()

    await editarPrompt()
    expect(ui.getByTestId('editor-unsaved').textContent).toBe('editor.unsaved.badge')

    await click(ui.getByTestId('editor-save'))
    await waitFor(() => expect(ui.queryByTestId('editor-unsaved')).toBeNull())
  })

  it('renomear também conta como alteração não salva', async () => {
    const ui = await renderEditor()
    await click(ui.getByTestId('editor-title'))
    const input = ui.getByTestId('editor-title-input') as HTMLInputElement
    await act(async () => { fireEvent.change(input, { target: { value: 'Outro nome' } }) })
    await act(async () => { fireEvent.keyDown(input, { key: 'Enter' }) })

    expect(ui.getByTestId('editor-unsaved')).toBeTruthy()
  })

  it('sair com alteração não sai: o modal do app pergunta, e Continuar editando mantém o editor e a edição', async () => {
    const ui = await renderEditor()
    await editarPrompt()

    await irParaFluxos()

    await waitFor(() => expect(body().getByTestId('editor-unsaved-modal')).toBeTruthy())
    expect(ui.queryByTestId('workflow-list')).toBeNull()
    expect(ultimoRoteador!.state.location.pathname).toBe('/editor/wf-1')

    await click(body().getByTestId('editor-unsaved-stay'))
    expect(ultimoRoteador!.state.location.pathname).toBe('/editor/wf-1')
    expect(useFlowStore.getState().nodes[0].data.config).toEqual({ systemPrompt: 'novo' })
    expect(workflowApiMock.update).not.toHaveBeenCalled()
  })

  it('Descartar e sair libera a navegação sem gravar', async () => {
    const ui = await renderEditor()
    await editarPrompt()
    await irParaFluxos()
    await waitFor(() => body().getByTestId('editor-unsaved-modal'))

    await click(body().getByTestId('editor-unsaved-discard'))

    await waitFor(() => expect(ui.getByTestId('workflow-list')).toBeTruthy())
    expect(workflowApiMock.update).not.toHaveBeenCalled()
  })

  it('Salvar e sair grava o que está na tela e então sai', async () => {
    const ui = await renderEditor()
    await editarPrompt()
    await irParaFluxos()
    await waitFor(() => body().getByTestId('editor-unsaved-modal'))

    await click(body().getByTestId('editor-unsaved-save'))

    await waitFor(() => expect(ui.getByTestId('workflow-list')).toBeTruthy())
    expect(workflowApiMock.update).toHaveBeenCalledTimes(1)
    const gravado = workflowApiMock.update.mock.calls[0][1] as { steps: Array<{ configuration: unknown }> }
    expect(gravado.steps[0].configuration).toEqual({ systemPrompt: 'novo' })
  })

  it('Salvar e sair que falha fica no editor, com o erro', async () => {
    const ui = await renderEditor()
    await editarPrompt()
    workflowApiMock.update.mockRejectedValueOnce(Object.assign(new Error('boom'), { status: 500 }))
    await irParaFluxos()
    await waitFor(() => body().getByTestId('editor-unsaved-modal'))

    await click(body().getByTestId('editor-unsaved-save'))

    await waitFor(() => expect(showMock).toHaveBeenCalledWith(expect.objectContaining({ color: 'red' })))
    expect(ultimoRoteador!.state.location.pathname).toBe('/editor/wf-1')
    expect(ui.queryByTestId('workflow-list')).toBeNull()
  })

  it('sem alteração, sair não pergunta nada', async () => {
    const ui = await renderEditor()
    await irParaFluxos()
    await waitFor(() => expect(ui.getByTestId('workflow-list')).toBeTruthy())
    expect(body().queryByTestId('editor-unsaved-modal')).toBeNull()
  })

  it('Descartar e sair para o item Editor do menu (/editor, sem remontar) descarta mesmo o canvas e o nome', async () => {
    const ui = await renderEditor()
    await editarPrompt()
    await click(ui.getByTestId('editor-title'))
    const input = ui.getByTestId('editor-title-input') as HTMLInputElement
    await act(async () => { fireEvent.change(input, { target: { value: 'Nome descartado' } }) })
    await act(async () => { fireEvent.keyDown(input, { key: 'Enter' }) })

    await act(async () => { await ultimoRoteador!.navigate('/editor') })
    await waitFor(() => body().getByTestId('editor-unsaved-modal'))
    await click(body().getByTestId('editor-unsaved-discard'))

    await waitFor(() => expect(ultimoRoteador!.state.location.pathname).toBe('/editor'))
    await waitFor(() => expect(useFlowStore.getState().nodes[0]?.data.config).toEqual({ systemPrompt: 'antigo' }))
    expect(ui.getByTestId('editor-title').textContent).toBe('Fluxo')
    expect(useWorkflowStore.getState().currentWorkflow?.metadata.name).toBe('Fluxo')
    expect(ui.queryByTestId('editor-unsaved')).toBeNull()
    expect(workflowApiMock.update).not.toHaveBeenCalled()
  })

  it('renomear com a lista de fluxos ainda vazia não deixa o Salvar como "Salvando…"', async () => {
    let responder!: (lista: unknown[]) => void
    workflowApiMock.list.mockReturnValue(new Promise(r => { responder = r }))
    const ui = await renderEditor()
    await click(ui.getByTestId('editor-title'))
    const input = ui.getByTestId('editor-title-input') as HTMLInputElement
    await act(async () => { fireEvent.change(input, { target: { value: 'Outro nome' } }) })
    await act(async () => { fireEvent.keyDown(input, { key: 'Enter' }) })
    await waitFor(() => expect(workflowApiMock.list).toHaveBeenCalled())

    const salvar = ui.getByTestId('editor-save') as HTMLButtonElement
    expect(salvar.disabled).toBe(false)
    expect(salvar.textContent).toBe('editor.save')
    expect(useWorkflowStore.getState().loading).toBe(false)
    await act(async () => { responder([]) })
  })

  it('depois de salvar, sair não pergunta nada', async () => {
    const ui = await renderEditor()
    await editarPrompt()
    await click(ui.getByTestId('editor-save'))
    await waitFor(() => expect(workflowApiMock.update).toHaveBeenCalledTimes(1))

    await irParaFluxos()
    await waitFor(() => expect(ui.getByTestId('workflow-list')).toBeTruthy())
  })
})

describe('WorkflowEditor — conflito no Salvar (RCHFLW-125)', () => {
  it('o PUT leva o updatedAt lido, e o seguinte leva o que o servidor devolveu', async () => {
    const ui = await renderEditor()
    await editarPrompt()
    await click(ui.getByTestId('editor-save'))
    await waitFor(() => expect(workflowApiMock.update).toHaveBeenCalledTimes(1))
    expect(workflowApiMock.update.mock.calls[0][2]).toBe('2026-11-25T10:00:00Z')

    await act(async () => { useFlowStore.getState().updateNodeConfig('a', 'systemPrompt', 'mais novo') })
    await click(ui.getByTestId('editor-save'))
    await waitFor(() => expect(workflowApiMock.update).toHaveBeenCalledTimes(2))
    expect(workflowApiMock.update.mock.calls[1][2]).toBe('2026-11-25T11:00:00Z')
  })

  it('Salvar e logo a aba Código: o segundo PUT espera o primeiro e leva a versão dele — sem 409 contra si mesmo', async () => {
    let responder!: () => void
    workflowApiMock.update.mockImplementationOnce((_id: string, b: Record<string, unknown>) =>
      new Promise(r => { responder = () => r({ ...b, updatedAt: '2026-11-25T11:00:00Z' }) }))
    // O servidor de verdade: If-Match com a versão já superada → 409.
    workflowApiMock.update.mockImplementation(async (_id: string, b: Record<string, unknown>, versao?: string) => {
      if (versao !== '2026-11-25T11:00:00Z') throw conflito()
      return { ...b, updatedAt: '2026-11-25T12:00:00Z' }
    })
    const ui = await renderEditor()
    await editarPrompt()

    await click(ui.getByTestId('editor-save'))
    await act(async () => { useFlowStore.getState().updateNodeConfig('a', 'systemPrompt', 'mais novo') })
    await click(ui.getByTestId('editor-tab-code'))
    expect(workflowApiMock.update).toHaveBeenCalledTimes(1)

    await act(async () => { responder() })

    await waitFor(() => expect(workflowApiMock.update).toHaveBeenCalledTimes(2))
    expect(workflowApiMock.update.mock.calls[1][2]).toBe('2026-11-25T11:00:00Z')
    const gravado = workflowApiMock.update.mock.calls[1][1] as { steps: Array<{ configuration: unknown }> }
    expect(gravado.steps[0].configuration).toEqual({ systemPrompt: 'mais novo' })
    await waitFor(() => expect(ui.getByTestId('editor-yaml-pane')).toBeTruthy())
    expect(body().queryByTestId('editor-conflict-modal')).toBeNull()
  })

  it('Recarregar que falha diz que o recarregar falhou (não um salvar) e mantém o aviso aberto', async () => {
    const ui = await renderEditor()
    await editarPrompt()
    workflowApiMock.update.mockRejectedValueOnce(conflito())
    await click(ui.getByTestId('editor-save'))
    await waitFor(() => body().getByTestId('editor-conflict-modal'))

    workflowApiMock.get.mockRejectedValue(new Error('Network Error'))
    await click(body().getByTestId('editor-conflict-reload'))

    await waitFor(() => expect(showMock).toHaveBeenCalledWith(expect.objectContaining({
      title: 'editor.loadFailed', message: 'editor.conflict.reloadFailedMsg', color: 'red' })))
    expect(showMock).not.toHaveBeenCalledWith(expect.objectContaining({ message: 'editor.notif.saveErrorMsg' }))
    expect(body().getByTestId('editor-conflict-modal')).toBeTruthy()
    expect(useFlowStore.getState().nodes[0].data.config).toEqual({ systemPrompt: 'novo' })
  })

  it('409: avisa que o fluxo foi alterado em outro lugar, sem "salvo com sucesso"', async () => {
    const ui = await renderEditor()
    await editarPrompt()
    workflowApiMock.update.mockRejectedValueOnce(conflito())

    await click(ui.getByTestId('editor-save'))

    await waitFor(() => expect(body().getByTestId('editor-conflict-modal')).toBeTruthy())
    expect(showMock).not.toHaveBeenCalledWith(expect.objectContaining({ title: 'editor.notif.saved' }))
    expect(ui.getByTestId('editor-unsaved')).toBeTruthy()
  })

  it('Recarregar troca a tela pela versão do servidor (a da outra sessão)', async () => {
    const ui = await renderEditor()
    await editarPrompt()
    workflowApiMock.update.mockRejectedValueOnce(conflito())
    await click(ui.getByTestId('editor-save'))
    await waitFor(() => body().getByTestId('editor-conflict-modal'))

    workflowApiMock.get.mockResolvedValue(doc('Fluxo (outra aba)', '2026-11-25T10:30:00Z'))
    await click(body().getByTestId('editor-conflict-reload'))

    await waitFor(() => expect(ui.getByTestId('editor-title').textContent).toBe('Fluxo (outra aba)'))
    await waitFor(() => expect(useFlowStore.getState().nodes[0]?.data.config).toEqual({ systemPrompt: 'antigo' }))
    expect(ui.queryByTestId('editor-unsaved')).toBeNull()
    expect(workflowApiMock.update).toHaveBeenCalledTimes(1)
  })

  it('Sobrescrever grava a versão da tela por cima, sem a versão no If-Match', async () => {
    const ui = await renderEditor()
    await editarPrompt()
    workflowApiMock.update.mockRejectedValueOnce(conflito())
    await click(ui.getByTestId('editor-save'))
    await waitFor(() => body().getByTestId('editor-conflict-modal'))

    await click(body().getByTestId('editor-conflict-overwrite'))

    await waitFor(() => expect(workflowApiMock.update).toHaveBeenCalledTimes(2))
    expect(workflowApiMock.update.mock.calls[1][2]).toBeUndefined()
    const gravado = workflowApiMock.update.mock.calls[1][1] as { steps: Array<{ configuration: unknown }> }
    expect(gravado.steps[0].configuration).toEqual({ systemPrompt: 'novo' })
    await waitFor(() => expect(ui.queryByTestId('editor-unsaved')).toBeNull())
  })

  it('409 ao Executar: abre o mesmo aviso e não roda nenhuma versão', async () => {
    const ui = await renderEditor()
    await editarPrompt()
    workflowApiMock.update.mockRejectedValueOnce(conflito())

    await click(ui.getByTestId('editor-run'))
    const modal = body()
    await click(await waitFor(() => modal.getByTestId('run-input-confirm')))

    await waitFor(() => expect(body().getByTestId('editor-conflict-modal')).toBeTruthy())
    const { runAgUiWorkflow } = await import('../../services/agui-client')
    expect(runAgUiWorkflow).not.toHaveBeenCalled()
  })
})
