/**
 * RCHFLW-134 — ajustes do editor:
 * - nome muito longo fica numa linha, com reticências (o de 512 caracteres ocupava ~6 linhas do topo);
 * - renomear para vazio mostra erro (era ignorado calado); nome igual ao de outro fluxo avisa, sem bloquear;
 * - YAML editado e não salvo: voltar ao Canvas pergunta antes de descartar (modal do app);
 * - Salvar YAML com nó de tipo desconhecido (`foo-bar`) dá o mesmo aviso que o Executar dá aos nós sem execução —
 *   antes o YAML era aceito e o fluxo só falhava no fim, depois de gastar o LLM.
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
const yamlApiMock = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn() }))
const catalogMock = vi.hoisted(() => ({ listAgents: vi.fn(), listAssistants: vi.fn(), listTools: vi.fn() }))
const showMock = vi.hoisted(() => vi.fn())

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, o?: Record<string, unknown>) => (o && Object.keys(o).length ? `${k}:${JSON.stringify(o)}` : k),
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))
vi.mock('../../services/api', () => ({ workflowApi: workflowApiMock, executionApi: {} }))
vi.mock('../../services/workflow-yaml-api', () => ({ workflowYamlApi: yamlApiMock }))
vi.mock('../../services/catalog-api', () => ({ catalogApi: catalogMock }))
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

function doc(name: string, steps: unknown[] = [step('a', 'LLM-CHAT', 'llm-chat', 'A')]) {
  return {
    id: 'wf-1',
    updatedAt: '2026-11-25T10:00:00Z',
    metadata: { name, description: '', version: '1.0.0', category: '', tags: [] },
    configuration: {},
    steps,
  }
}

function step(id: string, type: string, componentId: string | undefined, label: string) {
  return { id, type, componentId, label, position: { x: 0, y: 0 }, configuration: {}, connections: [] }
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
  workflowApiMock.get.mockResolvedValue(doc('Fluxo'))
  workflowApiMock.update.mockImplementation(async (_id: string, body: unknown) => body)
  workflowApiMock.list.mockResolvedValue([
    { id: 'wf-1', name: 'Fluxo', description: '', version: '1.0.0', status: 'draft', updatedAt: '', stepCount: 1 },
    { id: 'wf-2', name: 'Triagem', description: '', version: '1.0.0', status: 'draft', updatedAt: '', stepCount: 3 },
  ])
  yamlApiMock.get.mockResolvedValue({ id: 'wf-1', yaml: 'id: wf-1\nsteps: []\n', version: null })
  catalogMock.listAgents.mockResolvedValue([{ id: 'agente-do-plugin', displayName: 'P', kind: 'agent' }])
  catalogMock.listAssistants.mockResolvedValue([])
  catalogMock.listTools.mockResolvedValue([{ id: 'calculator', displayName: 'Calc', kind: 'tool' }])
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

async function rename(ui: ReturnType<typeof within>, name: string) {
  await click(ui.getByTestId('editor-title'))
  const input = ui.getByTestId('editor-title-input') as HTMLInputElement
  await act(async () => { fireEvent.change(input, { target: { value: name } }) })
  await act(async () => { fireEvent.keyDown(input, { key: 'Enter' }) })
}

async function editarYaml(ui: ReturnType<typeof within>, texto: string) {
  await click(ui.getByTestId('editor-tab-code'))
  const textarea = await waitFor(() => ui.getByRole('textbox') as HTMLTextAreaElement)
  await act(async () => { fireEvent.change(textarea, { target: { value: texto } }) })
}

const toasts = () => showMock.mock.calls.map(c => c[0] as { title?: string; message?: string; color?: string })
const body = () => within(document.body)

describe('WorkflowEditor — nome do fluxo (RCHFLW-134)', () => {
  it('nome longo fica numa linha com reticências, e o nome inteiro no tooltip', async () => {
    const longo = 'N'.repeat(512)
    workflowApiMock.get.mockResolvedValue(doc(longo))
    const ui = await renderEditor()

    const titulo = ui.getByTestId('editor-title')
    expect(titulo.textContent).toBe(longo)
    expect(titulo.style.whiteSpace).toBe('nowrap')
    expect(titulo.style.textOverflow).toBe('ellipsis')
    expect(titulo.style.overflow).toBe('hidden')
    expect(titulo.getAttribute('title')).toContain(longo)
    // O contêiner precisa poder encolher, senão a reticência nunca aparece.
    expect((titulo.parentElement as HTMLElement).style.minWidth).toMatch(/^0(px)?$/)
  })

  it('renomear para vazio mostra erro e mantém o nome', async () => {
    const ui = await renderEditor()
    await rename(ui, '   ')

    expect(ui.getByTestId('editor-title').textContent).toBe('Fluxo')
    expect(toasts()).toContainEqual(expect.objectContaining({ title: 'editor.renameEmpty', color: 'red' }))
    expect(ui.queryByTestId('editor-unsaved')).toBeNull()
  })

  it('nome igual ao de outro fluxo é aceito, com aviso', async () => {
    const ui = await renderEditor()
    await rename(ui, ' triagem ')

    expect(ui.getByTestId('editor-title').textContent).toBe('triagem')
    await waitFor(() => expect(toasts()).toContainEqual(
      expect.objectContaining({ title: 'editor.renameDuplicate', color: 'yellow' })))
    expect(ui.getByTestId('editor-unsaved')).toBeTruthy()
  })

  it('nome que só este fluxo usa não avisa', async () => {
    const ui = await renderEditor()
    await rename(ui, 'Nome novo')

    await waitFor(() => expect(workflowApiMock.list).toHaveBeenCalled())
    expect(toasts().some(n => n.title === 'editor.renameDuplicate')).toBe(false)
  })
})

describe('WorkflowEditor — YAML não salvo ao voltar para o Canvas (RCHFLW-134)', () => {
  it('pergunta antes de descartar; Continuar no YAML mantém o texto', async () => {
    const ui = await renderEditor()
    await editarYaml(ui, 'steps: [ isto nao e yaml: {\n')

    await click(ui.getByTestId('editor-tab-canvas'))

    await waitFor(() => expect(body().getByTestId('editor-discard-yaml-modal')).toBeTruthy())
    expect(ui.getByTestId('editor-yaml-pane')).toBeTruthy()
    await click(body().getByTestId('editor-discard-yaml-stay'))
    expect((ui.getByRole('textbox') as HTMLTextAreaElement).value).toBe('steps: [ isto nao e yaml: {\n')
    expect(yamlApiMock.update).not.toHaveBeenCalled()
  })

  it('Descartar e voltar vai para o Canvas sem gravar', async () => {
    const ui = await renderEditor()
    await editarYaml(ui, 'steps: [ isto nao e yaml: {\n')
    await click(ui.getByTestId('editor-tab-canvas'))
    await waitFor(() => body().getByTestId('editor-discard-yaml-modal'))

    await click(body().getByTestId('editor-discard-yaml-confirm'))

    await waitFor(() => expect(ui.queryByTestId('editor-yaml-pane')).toBeNull())
    expect(yamlApiMock.update).not.toHaveBeenCalled()
    expect(ui.queryByTestId('editor-unsaved')).toBeNull()
  })

  it('YAML sem edição volta direto, sem perguntar', async () => {
    const ui = await renderEditor()
    await click(ui.getByTestId('editor-tab-code'))
    await waitFor(() => ui.getByRole('textbox'))

    await click(ui.getByTestId('editor-tab-canvas'))

    expect(ui.queryByTestId('editor-yaml-pane')).toBeNull()
    expect(body().queryByTestId('editor-discard-yaml-modal')).toBeNull()
  })
})

describe('WorkflowEditor — Salvar YAML com nó que não executa (RCHFLW-134)', () => {
  async function salvarYamlQueGrava(steps: unknown[]) {
    const ui = await renderEditor()
    await editarYaml(ui, 'id: wf-1\nsteps: []\n# editado\n')
    yamlApiMock.update.mockResolvedValue({ id: 'wf-1', yaml: 'id: wf-1\n', version: null })
    workflowApiMock.get.mockResolvedValue(doc('Fluxo', steps))
    await click(ui.getByTestId('editor-save'))
    await waitFor(() => expect(yamlApiMock.update).toHaveBeenCalledTimes(1))
    return ui
  }

  it('tipo desconhecido (foo-bar): YAML salvo, com o aviso do Executar nomeando o nó', async () => {
    await salvarYamlQueGrava([step('a', 'LLM-CHAT', 'llm-chat', 'A'), step('b', 'foo-bar', undefined, 'Estranho')])

    await waitFor(() => expect(toasts()).toContainEqual(expect.objectContaining({
      title: 'editor.notif.yamlNotExecutable',
      color: 'orange',
    })))
    const aviso = toasts().find(n => n.title === 'editor.notif.yamlNotExecutable')!
    expect(aviso.message).toContain('editor.notif.runBlockedNotExecutable')
    expect(aviso.message).toContain('\\"Estranho\\" (foo-bar)')
    expect(toasts()).toContainEqual(expect.objectContaining({ title: 'editor.notif.yamlSaved' }))
  })

  it('componente do catálogo (plugin, ferramenta) não é desconhecido: sem aviso', async () => {
    await salvarYamlQueGrava([step('a', 'TOOL', 'calculator', 'Calc'), step('b', 'agente-do-plugin', undefined, 'P')])

    await waitFor(() => expect(catalogMock.listTools).toHaveBeenCalled())
    await act(async () => { await Promise.resolve() })
    expect(toasts().some(n => n.title === 'editor.notif.yamlNotExecutable')).toBe(false)
  })

  it('tipo da paleta sem execução (loop) também avisa', async () => {
    await salvarYamlQueGrava([step('a', 'loop', 'loop', 'Repetir')])

    await waitFor(() => expect(toasts()).toContainEqual(
      expect.objectContaining({ title: 'editor.notif.yamlNotExecutable' })))
  })
})
