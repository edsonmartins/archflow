/**
 * Alternar Canvas → Código → Canvas não pode perder o estado do canvas.
 *
 * <p>O editor desmontava o FlowCanvas na aba Código; no remount ele restaurava o
 * workflowData memoizado no último load (não no último save) e zerava o undo — o nó
 * recém-adicionado sumia e um Salvar seguinte gravava o snapshot velho. Estes testes
 * usam o FlowCanvas e o useFlowStore reais; só a rede e os painéis laterais são falsos.</p>
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { entrarComo } from '../../lib/__tests__/papeisDeTeste'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { Route, Routes } from 'react-router-dom'
import { MemoryDataRouter } from '../../lib/__tests__/roteadorDeTeste'
import { MantineProvider, getDefaultZIndex } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'

const workflowApiMock = vi.hoisted(() => ({
  get:    vi.fn(),
  update: vi.fn(),
}))
const yamlApiMock = vi.hoisted(() => ({
  get:    vi.fn(),
  update: vi.fn(),
}))
const runMock = vi.hoisted(() => vi.fn(() => () => {}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string) => k,
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))
vi.mock('../../services/api', () => ({
  workflowApi:  workflowApiMock,
  executionApi: {},
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

import { WorkflowEditor } from '../WorkflowEditorPage'
import { useFlowStore } from '../../components/FlowCanvas/store/useFlowStore'
import { useWorkflowStore } from '../../stores/workflow-store'

// ── Polyfills que o React Flow e o Mantine pedem e o jsdom não tem ──
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
beforeEach(() => {
  // Papel que concede editar e executar: sem ele as ações somem (RCHFLW-74).
  entrarComo('DESIGNER')
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

function step(id: string, label: string, configuration: Record<string, unknown> = {}) {
  return { id, type: 'LLM-CHAT', componentId: 'llm-chat', label, position: { x: 0, y: 0 }, configuration, connections: [] }
}

function doc(steps: unknown[]) {
  return {
    id:            'wf-1',
    metadata:      { name: 'Fluxo', description: '', version: '1.0.0', category: '', tags: [] },
    configuration: {},
    steps,
  }
}

let root: Root | null = null
let container: HTMLDivElement

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
  // O canvas registra a API imperativa ao montar — é o sinal de que já está de pé.
  await waitFor(() => expect(useFlowStore.getState().canvasApi).not.toBeNull())
  return within(container)
}

async function click(el: HTMLElement) {
  await act(async () => { fireEvent.click(el) })
}

/** Executar abre o modal "Entrada da execução"; o run só sai depois de confirmar. */
async function runFromEditor(ui: ReturnType<typeof within>, inputText?: string) {
  await click(ui.getByTestId('editor-run'))
  const modal = within(document.body)
  const textarea = await waitFor(() => modal.getByTestId('run-input-text') as HTMLTextAreaElement)
  if (inputText !== undefined) {
    await act(async () => { fireEvent.change(textarea, { target: { value: inputText } }) })
  }
  await click(modal.getByTestId('run-input-confirm'))
}

function storeLabels() {
  return useFlowStore.getState().nodes.map(n => n.data.label)
}

describe('WorkflowEditor — troca de aba Canvas/Código', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useWorkflowStore.setState({ currentWorkflow: null, error: null, loading: false })
    useFlowStore.setState({ nodes: [], edges: [], canvasApi: null, pendingCanvasOps: [], isExecuting: false })
    workflowApiMock.get.mockResolvedValue(doc([step('a', 'Primeiro')]))
    // O backend devolve o que recebeu — como o PUT real.
    workflowApiMock.update.mockImplementation(async (_id: string, body: unknown) => body)
    yamlApiMock.get.mockResolvedValue({ id: 'wf-1', yaml: 'steps: []\n', version: null })
  })

  afterEach(async () => {
    await act(async () => { root?.unmount() })
    root = null
    container?.remove()
  })

  it('preserva o nó adicionado e o undo ao ir para Código e voltar', async () => {
    const ui = await renderEditor()

    await act(async () => {
      useFlowStore.getState().canvasApi!.addNode({ nodeType: 'llm-chat', label: 'Novo' })
    })
    await waitFor(() => expect(storeLabels()).toEqual(['Primeiro', 'Novo']))
    expect((ui.getByTestId('canvas-undo') as HTMLButtonElement).disabled).toBe(false)

    await click(ui.getByTestId('editor-tab-code'))
    await waitFor(() => expect(ui.getByTestId('editor-yaml-pane')).toBeTruthy())
    // Oculto, não desmontado: o canvas sai do foco/cliques mas mantém o estado.
    const pane = ui.getByTestId('editor-canvas-pane')
    expect(pane.style.visibility).toBe('hidden')
    expect(pane.hasAttribute('inert')).toBe(true)

    await click(ui.getByTestId('editor-tab-canvas'))

    expect(storeLabels()).toEqual(['Primeiro', 'Novo'])
    expect(ui.getByTestId('editor-canvas-pane').style.visibility).toBe('visible')
    expect(ui.queryByTestId('editor-yaml-pane')).toBeNull()
    expect((ui.getByTestId('canvas-undo') as HTMLButtonElement).disabled).toBe(false)
  })

  it('depois de salvar, a ida e volta não reapresenta (nem regrava) o snapshot anterior', async () => {
    const ui = await renderEditor()

    await act(async () => { useFlowStore.getState().updateNodeConfig('a', 'prompt', '${entrada.nova}') })
    await click(ui.getByTestId('editor-save'))
    await waitFor(() => expect(workflowApiMock.update).toHaveBeenCalledTimes(1))

    await click(ui.getByTestId('editor-tab-code'))
    await click(ui.getByTestId('editor-tab-canvas'))
    expect(useFlowStore.getState().nodes[0].data.config).toEqual({ prompt: '${entrada.nova}' })

    await click(ui.getByTestId('editor-save'))
    await waitFor(() => expect(workflowApiMock.update).toHaveBeenCalledTimes(2))
    const body = workflowApiMock.update.mock.calls[1][1] as { steps: Array<{ configuration: unknown }> }
    expect(body.steps[0].configuration).toEqual({ prompt: '${entrada.nova}' })
  })

  it('Salvar YAML reconstrói o canvas a partir do documento salvo', async () => {
    const ui = await renderEditor()

    await click(ui.getByTestId('editor-tab-code'))
    const textarea = await waitFor(() => ui.getByRole('textbox') as HTMLTextAreaElement)
    await act(async () => { fireEvent.change(textarea, { target: { value: 'editado: sim\n' } }) })

    yamlApiMock.update.mockResolvedValue({ id: 'wf-1', yaml: 'editado: sim\n', version: null })
    // O backend agora devolve o documento que o YAML produziu.
    workflowApiMock.get.mockResolvedValue(doc([step('a', 'Renomeado no YAML'), step('b', 'Novo no YAML')]))

    await click(ui.getByTestId('editor-save'))

    await waitFor(() => expect(storeLabels()).toEqual(['Renomeado no YAML', 'Novo no YAML']))
    expect(yamlApiMock.update).toHaveBeenCalledWith('wf-1', 'editado: sim\n')
    await click(ui.getByTestId('editor-tab-canvas'))
    expect(storeLabels()).toEqual(['Renomeado no YAML', 'Novo no YAML'])

    // Um Salvar do canvas logo em seguida grava o documento do YAML, não o anterior.
    await click(ui.getByTestId('editor-save'))
    await waitFor(() => expect(workflowApiMock.update).toHaveBeenCalledTimes(1))
    const body = workflowApiMock.update.mock.calls[0][1] as { steps: Array<{ label: string }> }
    expect(body.steps.map(s => s.label)).toEqual(['Renomeado no YAML', 'Novo no YAML'])
  })

  it('Executar na aba Código com YAML não salvo grava o YAML, não o snapshot do canvas', async () => {
    const ui = await renderEditor()

    await click(ui.getByTestId('editor-tab-code'))
    const textarea = await waitFor(() => ui.getByRole('textbox') as HTMLTextAreaElement)
    await act(async () => { fireEvent.change(textarea, { target: { value: 'editado: sim\n' } }) })
    yamlApiMock.update.mockResolvedValue({ id: 'wf-1', yaml: 'editado: sim\n', version: null })

    await runFromEditor(ui)

    await waitFor(() => expect(runMock).toHaveBeenCalledTimes(1))
    expect(yamlApiMock.update).toHaveBeenCalledWith('wf-1', 'editado: sim\n')
    expect(workflowApiMock.update).not.toHaveBeenCalled()
  })
  it('ir para Código com o canvas sujo grava o canvas antes de buscar o YAML', async () => {
    const ui = await renderEditor()

    await act(async () => {
      useFlowStore.getState().canvasApi!.addNode({ nodeType: 'llm-chat', label: 'Novo' })
    })
    await waitFor(() => expect(storeLabels()).toEqual(['Primeiro', 'Novo']))

    await click(ui.getByTestId('editor-tab-code'))
    await waitFor(() => expect(ui.getByTestId('editor-yaml-pane')).toBeTruthy())

    // O YAML vem do backend: sem gravar antes, ele não teria o nó novo e um Salvar YAML o apagaria.
    expect(workflowApiMock.update).toHaveBeenCalledTimes(1)
    const body = workflowApiMock.update.mock.calls[0][1] as { steps: Array<{ label: string }> }
    expect(body.steps.map(s => s.label)).toEqual(['Primeiro', 'Novo'])
    expect(workflowApiMock.update.mock.invocationCallOrder[0])
      .toBeLessThan(yamlApiMock.get.mock.invocationCallOrder[0])

    // Já gravado: voltar e ir de novo, sem editar, não regrava.
    await click(ui.getByTestId('editor-tab-canvas'))
    await click(ui.getByTestId('editor-tab-code'))
    expect(workflowApiMock.update).toHaveBeenCalledTimes(1)
  })

  it('canvas limpo: ir para Código não grava nada', async () => {
    const ui = await renderEditor()

    await click(ui.getByTestId('editor-tab-code'))
    await waitFor(() => expect(yamlApiMock.get).toHaveBeenCalledTimes(1))
    expect(workflowApiMock.update).not.toHaveBeenCalled()
  })

  it('se a gravação do canvas sujo falha, fica no Canvas e não abre um YAML que não reflete o canvas', async () => {
    const ui = await renderEditor()
    workflowApiMock.update.mockRejectedValueOnce(new Error('validação'))

    await act(async () => { useFlowStore.getState().updateNodeConfig('a', 'prompt', 'editado') })
    await click(ui.getByTestId('editor-tab-code'))

    await waitFor(() => expect(workflowApiMock.update).toHaveBeenCalledTimes(1))
    expect(ui.queryByTestId('editor-yaml-pane')).toBeNull()
    expect(ui.getByTestId('editor-canvas-pane').style.visibility).toBe('visible')
    expect(yamlApiMock.get).not.toHaveBeenCalled()
    expect(useFlowStore.getState().nodes[0].data.config).toEqual({ prompt: 'editado' })
  })

  it('atalhos de teclado na aba Código não mexem no canvas oculto', async () => {
    const ui = await renderEditor()

    await act(async () => {
      useFlowStore.getState().canvasApi!.addNode({ nodeType: 'llm-chat', label: 'Novo' })
    })
    await waitFor(() => expect(storeLabels()).toEqual(['Primeiro', 'Novo']))
    // Seleciona o nó original (o clique no nó é o caminho real de seleção do React Flow).
    const nodeEl = container.querySelector('.react-flow__node[data-id="a"]') as HTMLElement
    await click(nodeEl)
    await waitFor(() => expect(nodeEl.classList.contains('selected')).toBe(true))

    await click(ui.getByTestId('editor-tab-code'))
    await waitFor(() => expect(ui.getByTestId('editor-yaml-pane')).toBeTruthy())

    // Fora do textarea (ex.: foco no botão da aba): Ctrl+Z, Delete e Backspace chegariam ao canvas.
    // Cada tecla num act próprio: o React Flow guarda "pressionado" em estado e só exclui num efeito;
    // keyDown e keyUp no mesmo act se anulariam e o teste passaria sem provar nada.
    const tab = ui.getByTestId('editor-tab-code')
    const press = async (init: { key: string; code: string; ctrlKey?: boolean }) => {
      await act(async () => {
        fireEvent.keyDown(tab, init)
        // A exclusão passa pelo onBeforeDelete (assíncrono): dá tempo a ela acontecer.
        await new Promise(r => setTimeout(r, 20))
      })
      await act(async () => { fireEvent.keyUp(tab, init) })
    }
    await press({ key: 'z', code: 'KeyZ', ctrlKey: true })
    await press({ key: 'Delete', code: 'Delete' })
    await press({ key: 'Backspace', code: 'Backspace' })
    expect(storeLabels()).toEqual(['Primeiro', 'Novo'])

    // De volta ao canvas os atalhos voltam a valer — prova que o teste acima exercita o caminho real.
    await click(ui.getByTestId('editor-tab-canvas'))
    const pane = container.querySelector('.react-flow') as HTMLElement
    await act(async () => { fireEvent.keyDown(pane, { key: 'z', code: 'KeyZ', ctrlKey: true }) })
    await waitFor(() => expect(storeLabels()).toEqual(['Primeiro']))
  })

  it('Executar com YAML sujo e PUT recusado mostra o erro de validação no editor YAML', async () => {
    const ui = await renderEditor()

    await click(ui.getByTestId('editor-tab-code'))
    const textarea = await waitFor(() => ui.getByRole('textbox') as HTMLTextAreaElement)
    await act(async () => { fireEvent.change(textarea, { target: { value: 'quebrado: [\n' } }) })
    yamlApiMock.update.mockRejectedValue(new Error('YAML inválido na linha 1'))

    await runFromEditor(ui)

    await waitFor(() => expect(ui.getByText('YAML inválido na linha 1')).toBeTruthy())
    expect(runMock).not.toHaveBeenCalled()
    expect(workflowApiMock.update).not.toHaveBeenCalled()
  })

  it('YAML gravado com reload falho: um Salvar do canvas não regrava o estado de antes do YAML', async () => {
    const ui = await renderEditor()

    await click(ui.getByTestId('editor-tab-code'))
    const textarea = await waitFor(() => ui.getByRole('textbox') as HTMLTextAreaElement)
    await act(async () => { fireEvent.change(textarea, { target: { value: 'editado: sim\n' } }) })
    yamlApiMock.update.mockResolvedValue({ id: 'wf-1', yaml: 'editado: sim\n', version: null })
    // O PUT passa; os dois reloads seguintes (Salvar YAML e a volta ao Canvas) falham.
    workflowApiMock.get
      .mockRejectedValueOnce(new Error('rede'))
      .mockRejectedValueOnce(new Error('rede'))
      .mockResolvedValue(doc([step('a', 'Do YAML')]))

    await click(ui.getByTestId('editor-save'))
    await waitFor(() => expect(yamlApiMock.update).toHaveBeenCalledTimes(1))
    await click(ui.getByTestId('editor-tab-canvas'))
    await waitFor(() => expect(workflowApiMock.get).toHaveBeenCalledTimes(3))
    expect(storeLabels()).toEqual(['Primeiro'])

    // O canvas ainda é o de antes do YAML: o Salvar recarrega em vez de gravá-lo por cima.
    await click(ui.getByTestId('editor-save'))
    await waitFor(() => expect(storeLabels()).toEqual(['Do YAML']))
    expect(workflowApiMock.update).not.toHaveBeenCalled()

    // Reconstruído, o próximo Salvar grava normalmente o documento do YAML.
    await click(ui.getByTestId('editor-save'))
    await waitFor(() => expect(workflowApiMock.update).toHaveBeenCalledTimes(1))
    const body = workflowApiMock.update.mock.calls[0][1] as { steps: Array<{ label: string }> }
    expect(body.steps.map(s => s.label)).toEqual(['Do YAML'])
  })

  it('canvas reconstruído oculto (Salvar YAML) é medido e fica visível ao voltar', async () => {
    // Simula o layout: o React Flow só mostra um nó depois de medi-lo (offsetWidth/Height, lidos no
    // callback do ResizeObserver). Um ancestral com display:none mede 0 — visibility:hidden não.
    const hiddenByDisplay = (el: HTMLElement) => {
      for (let n: HTMLElement | null = el; n; n = n.parentElement) {
        if (n.style.display === 'none') return true
      }
      return false
    }
    const width = vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get')
      .mockImplementation(function (this: HTMLElement) { return hiddenByDisplay(this) ? 0 : 160 })
    const height = vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get')
      .mockImplementation(function (this: HTMLElement) { return hiddenByDisplay(this) ? 0 : 48 })
    ;(globalThis as any).ResizeObserver = class {
      constructor(private cb: (entries: Array<{ target: Element }>) => void) {}
      observe(target: Element) { setTimeout(() => this.cb([{ target }]), 0) }
      unobserve() {}
      disconnect() {}
    }
    try {
      const ui = await renderEditor()
      await click(ui.getByTestId('editor-tab-code'))
      const textarea = await waitFor(() => ui.getByRole('textbox') as HTMLTextAreaElement)
      await act(async () => { fireEvent.change(textarea, { target: { value: 'editado: sim\n' } }) })
      yamlApiMock.update.mockResolvedValue({ id: 'wf-1', yaml: 'editado: sim\n', version: null })
      workflowApiMock.get.mockResolvedValue(doc([step('a', 'A'), step('b', 'B')]))

      await click(ui.getByTestId('editor-save'))
      await waitFor(() => expect(storeLabels()).toEqual(['A', 'B']))
      // Ainda na aba Código: os nós novos já foram medidos com o canvas oculto.
      await waitFor(() => {
        const nodes = Array.from(container.querySelectorAll<HTMLElement>('.react-flow__node'))
        expect(nodes.map(n => n.dataset.id)).toEqual(['a', 'b'])
        expect(nodes.every(n => n.style.visibility === 'visible')).toBe(true)
      })

      await click(ui.getByTestId('editor-tab-canvas'))
      expect(ui.getByTestId('editor-canvas-pane').style.visibility).toBe('visible')
      const nodes = Array.from(container.querySelectorAll<HTMLElement>('.react-flow__node'))
      expect(nodes.every(n => n.style.visibility === 'visible')).toBe(true)
    } finally {
      width.mockRestore()
      height.mockRestore()
    }
  })
  it('o painel Código não sobe acima dos Modals e Notifications do Mantine', async () => {
    const ui = await renderEditor()
    await click(ui.getByTestId('editor-tab-code'))
    const yamlPane = await waitFor(() => ui.getByTestId('editor-yaml-pane'))
    const canvasPane = ui.getByTestId('editor-canvas-pane')
    const body = yamlPane.parentElement as HTMLElement

    // O jsdom não empilha nada; o que dá para travar é a regra: o corpo do editor e o canvas criam
    // contextos de empilhamento próprios, e o z-index do painel é local e menor que o do Modal (200).
    // Sem isso o painel opaco entrava no contexto raiz e cobria o "Gerar com IA" e o Publicar.
    expect(body.style.isolation).toBe('isolate')
    expect(canvasPane.style.isolation).toBe('isolate')
    expect(Number(yamlPane.style.zIndex)).toBeLessThan(Number(getDefaultZIndex('modal')))
    expect(Number(yamlPane.style.zIndex)).toBeGreaterThan(0)
  })
})

describe('WorkflowEditor — Executar pede a entrada da execução', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    sessionStorage.clear()
    useWorkflowStore.setState({ currentWorkflow: null, error: null, loading: false })
    useFlowStore.setState({ nodes: [], edges: [], canvasApi: null, pendingCanvasOps: [], isExecuting: false })
    workflowApiMock.get.mockResolvedValue(doc([step('a', 'Primeiro')]))
    workflowApiMock.update.mockImplementation(async (_id: string, body: unknown) => body)
    yamlApiMock.get.mockResolvedValue({ id: 'wf-1', yaml: 'steps: []\n', version: null })
  })

  afterEach(async () => {
    await act(async () => { root?.unmount() })
    root = null
    container?.remove()
  })

  it('não grava nem executa antes de confirmar; Cancelar não executa', async () => {
    const ui = await renderEditor()
    await click(ui.getByTestId('editor-run'))
    const modal = within(document.body)
    await waitFor(() => expect(modal.getByTestId('run-input-text')).toBeTruthy())
    expect(runMock).not.toHaveBeenCalled()
    expect(workflowApiMock.update).not.toHaveBeenCalled()

    await click(modal.getByTestId('run-input-cancel'))
    expect(runMock).not.toHaveBeenCalled()
    expect(workflowApiMock.update).not.toHaveBeenCalled()
  })

  it('objeto JSON vai como state do AG-UI (o seedInput põe cada chave no contexto)', async () => {
    const ui = await renderEditor()
    await runFromEditor(ui, '{"cliente": "ACME", "itens": 3}')
    await waitFor(() => expect(runMock).toHaveBeenCalledTimes(1))
    expect(runMock.mock.calls[0][0]).toBe('wf-1')
    expect(runMock.mock.calls[0][1]).toEqual({ state: { cliente: 'ACME', itens: 3 } })
  })

  it('texto livre vai como última mensagem, para o seedInput pôr o texto em input', async () => {
    const ui = await renderEditor()
    await runFromEditor(ui, 'olá, fluxo')
    await waitFor(() => expect(runMock).toHaveBeenCalledTimes(1))
    expect(runMock.mock.calls[0][1]).toEqual({ messages: [{ role: 'user', content: 'olá, fluxo' }] })
  })

  it('vazio continua executando sem entrada, como antes', async () => {
    const ui = await renderEditor()
    await runFromEditor(ui)
    await waitFor(() => expect(runMock).toHaveBeenCalledTimes(1))
    expect(runMock.mock.calls[0][1]).toEqual({})
  })

  it('reabre o modal com a última entrada usada neste fluxo', async () => {
    const ui = await renderEditor()
    await runFromEditor(ui, '{"x": 1}')
    await waitFor(() => expect(runMock).toHaveBeenCalledTimes(1))
    // Termina a execução para o botão voltar a ser Executar (e não Parar).
    await act(async () => { useFlowStore.setState({ isExecuting: false }) })

    await click(ui.getByTestId('editor-run'))
    const textarea = await waitFor(() =>
      within(document.body).getByTestId('run-input-text') as HTMLTextAreaElement)
    expect(textarea.value).toBe('{"x": 1}')
  })
})
