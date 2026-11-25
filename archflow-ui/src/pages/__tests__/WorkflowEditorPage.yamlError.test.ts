/**
 * RCHFLW-94: Salvar YAML com texto inválido punha na faixa de erro do editor o corpo JSON cru da resposta
 * (`{"status":400,"error":"Malformed YAML: while parsing a flow node\n in 'reader', line 10, ...`), com as
 * quebras de linha escapadas e a cadeia de referências do Jackson. Aqui o YAML passa pelo `request()` e
 * pelo `workflowYamlApi` reais — só a rede é falsa — e a faixa precisa mostrar a frase com linha/coluna.
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
import { notifications } from '@mantine/notifications'

const workflowApiMock = vi.hoisted(() => ({
  get:    vi.fn(),
  update: vi.fn(),
}))

// `t` lê o pt-BR.json real e interpola: o teste verifica a frase que o usuário pt-BR lê, não só a chave.
vi.mock('react-i18next', async () => {
  const ptBR = (await import('../../i18n/locales/pt-BR.json')).default as Record<string, unknown>
  const t = (k: string, o?: Record<string, unknown>) => {
    const modelo = k.split('.').reduce<unknown>((no, parte) => (no as Record<string, unknown>)?.[parte], ptBR)
    if (typeof modelo !== 'string') return k
    return modelo.replace(/\{\{(\w+)\}\}/g, (_, nome: string) => String(o?.[nome] ?? ''))
  }
  return {
    useTranslation: () => ({ t, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
  }
})
// Só o CRUD JSON é falso; `api`/`ApiError` são os reais, que o workflow-yaml-api usa.
vi.mock('../../services/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../services/api')>()),
  workflowApi:  workflowApiMock,
  executionApi: {},
}))
vi.mock('../../services/agui-client', () => ({ runAgUiWorkflow: vi.fn(() => () => {}) }))
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

const DOC = {
  id:            'wf-1',
  metadata:      { name: 'Fluxo', description: '', version: '1.0.0', category: '', tags: [] },
  configuration: {},
  steps:         [],
}

/** O corpo que o SpringWorkflowYamlController devolve para YAML inválido. */
const ERRO_YAML = {
  status:  400,
  error:   "Invalid YAML at line 6, column 1: expected the node content, but found '<stream end>'",
  message: "Invalid YAML at line 6, column 1: expected the node content, but found '<stream end>'",
  reason:  "expected the node content, but found '<stream end>'",
  line:    6,
  column:  1,
}

/** O que o usuário pt-BR precisa ler: a frase montada com linha/coluna, só o problema do parser como está. */
const FRASE_PT = "YAML inválido na linha 6, coluna 1: expected the node content, but found '<stream end>'"

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

async function click(el: HTMLElement) {
  await act(async () => { fireEvent.click(el) })
}

describe('WorkflowEditor — erro de Salvar YAML', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    sessionStorage.clear()
    useWorkflowStore.setState({ currentWorkflow: null, error: null, loading: false })
    useFlowStore.setState({ nodes: [], edges: [], canvasApi: null, pendingCanvasOps: [], isExecuting: false })
    workflowApiMock.get.mockResolvedValue(DOC)
    workflowApiMock.update.mockImplementation(async (_id: string, body: unknown) => body)
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (/\/workflows\/wf-1\/yaml$/.test(url) && (init?.method ?? 'GET') === 'GET') {
        return new Response(JSON.stringify({ id: 'wf-1', yaml: 'id: wf-1\nsteps: []\n', version: null }),
          { status: 200 })
      }
      if (/\/workflows\/wf-1\/yaml$/.test(url) && init?.method === 'PUT') {
        return new Response(JSON.stringify(ERRO_YAML), { status: 400, statusText: 'Bad Request' })
      }
      return new Response('', { status: 404 })
    }))
  })

  afterEach(async () => {
    await act(async () => { root?.unmount() })
    root = null
    container?.remove()
    vi.unstubAllGlobals()
  })

  it('a faixa de erro mostra a frase com linha/coluna, não o corpo JSON', async () => {
    const toast = vi.spyOn(notifications, 'show')
    const ui = await renderEditor()

    await click(ui.getByTestId('editor-tab-code'))
    const textarea = await waitFor(() => ui.getByRole('textbox') as HTMLTextAreaElement)
    await act(async () => {
      fireEvent.change(textarea, { target: { value: 'id: wf-1\nsteps: [ isto nao e yaml: {\n' } })
    })
    await click(ui.getByTestId('editor-save'))

    const faixa = await waitFor(() => within(ui.getByTestId('yaml-editor')).getByRole('alert'))
    expect(faixa.textContent).toBe(FRASE_PT)
    expect(faixa.textContent).not.toContain('{')
    expect(faixa.textContent).not.toContain('"status"')
    // O toast diz o mesmo problema, não só "Falha ao salvar o YAML".
    expect(toast).toHaveBeenCalledWith(expect.objectContaining({ title: 'Erro no YAML', message: FRASE_PT }))
  })
})
