/**
 * RCHFLW-74: o editor respeita o papel. Sem workflow:update (VIEWER, EXECUTOR) ele é só leitura —
 * canvas, YAML e painel de propriedades travados, título não renomeável, sem paleta de nós, sem Salvar,
 * Publicar e Gerar com IA — e mostra o selo "Somente leitura". Sem workflow:execute (VIEWER) não há Executar. O EXECUTOR executa a versão
 * gravada sem tentar gravar antes (o PUT responderia 403 e a execução nunca começaria).
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
vi.mock('../../components/NodePalette', () => ({ NodePalette: () => h('div', { 'data-testid': 'node-palette' }) }))
vi.mock('../../components/PropertyPanel', () => ({
  PropertyPanel: (p: { readOnly?: boolean }) =>
    h('div', { 'data-testid': 'property-panel', 'data-readonly': String(!!p.readOnly) }),
}))
vi.mock('../../components/CodeEditor/YamlEditor', () => ({
  default: (p: { readOnly?: boolean }) => h('div', { 'data-testid': 'yaml-editor', 'data-readonly': String(!!p.readOnly) }),
}))
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
  // Só leitura não registra a API do canvas (o FlowCanvas readonly não aceita operações); espera o
  // fluxo da rota carregar no cabeçalho.
  await waitFor(() => expect(within(container).getByTestId('editor-title').textContent).toBe('Fluxo'))
  return within(container)
}

function botoes(ui: ReturnType<typeof within>) {
  const tem = (id: string) => ui.queryByTestId(id) !== null
  return {
    salvar: tem('editor-save'), publicar: tem('editor-publish'), ia: tem('editor-ai'), executar: tem('editor-run'),
    somenteLeitura: tem('editor-read-only'),
    // A paleta e o botão dela: num canvas só leitura os itens não fariam nada.
    paleta: tem('node-palette') && tem('toggle-palette'),
    painelSoLeitura: ui.getByTestId('property-panel').getAttribute('data-readonly') === 'true',
  }
}

describe('WorkflowEditor — ações por papel (RCHFLW-74)', () => {
  it('VIEWER: só leitura e sem Executar', async () => {
    entrarComo('VIEWER')
    const ui = await renderEditor()
    expect(botoes(ui)).toEqual({
      salvar: false, publicar: false, ia: false, executar: false, somenteLeitura: true,
      paleta: false, painelSoLeitura: true,
    })
    // O título é texto, não o botão de renomear.
    expect(ui.getByTestId('editor-title').tagName).toBe('SPAN')
    // A aba Código abre o YAML travado.
    await act(async () => { fireEvent.click(ui.getByTestId('editor-tab-code')) })
    await waitFor(() => expect(ui.getByTestId('yaml-editor').getAttribute('data-readonly')).toBe('true'))
    expect(workflowApiMock.update).not.toHaveBeenCalled()
  })

  it('EXECUTOR: executa a versão gravada sem tentar gravar', async () => {
    entrarComo('EXECUTOR')
    const ui = await renderEditor()
    expect(botoes(ui)).toEqual({
      salvar: false, publicar: false, ia: false, executar: true, somenteLeitura: true,
      paleta: false, painelSoLeitura: true,
    })
    await act(async () => { fireEvent.click(ui.getByTestId('editor-run')) })
    const confirm = await waitFor(() => within(document.body).getByTestId('run-input-confirm'))
    await act(async () => { fireEvent.click(confirm) })
    await waitFor(() => expect(runMock).toHaveBeenCalledTimes(1))
    expect(runMock.mock.calls[0][0]).toBe('wf-1')
    expect(workflowApiMock.update).not.toHaveBeenCalled()
  })

  it('DESIGNER: edita (título renomeável, YAML editável) e executa', async () => {
    entrarComo('DESIGNER')
    const ui = await renderEditor()
    expect(botoes(ui)).toEqual({
      salvar: true, publicar: true, ia: true, executar: true, somenteLeitura: false,
      paleta: true, painelSoLeitura: false,
    })
    expect(ui.getByTestId('editor-title').tagName).toBe('BUTTON')
    await act(async () => { fireEvent.click(ui.getByTestId('editor-tab-code')) })
    await waitFor(() => expect(ui.getByTestId('yaml-editor').getAttribute('data-readonly')).toBe('false'))
  })
})
