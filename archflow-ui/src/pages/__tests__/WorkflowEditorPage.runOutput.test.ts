/**
 * Aba "Saída" do editor: os eventos STEP_* não trazem a saída do passo, então ao fim do run o editor
 * lê o registro da execução (GET /executions/{id}, o id vem do RUN_STARTED) e preenche a saída de
 * cada nó por merge — status e duração dos eventos ficam.
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
import type { AgUiEvent } from '../../services/agui-client'

const workflowApiMock = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn() }))
const executionApiMock = vi.hoisted(() => ({ get: vi.fn() }))
const yamlApiMock = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn() }))
const runMock = vi.hoisted(() => vi.fn((_id: string, _input: unknown, _onEvent: (ev: AgUiEvent) => void) => () => {}))

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
vi.mock('../../components/NodePalette', () => ({ NodePalette: () => null }))
vi.mock('../../components/PropertyPanel', () => ({ PropertyPanel: () => null }))
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn() } }))

import { WorkflowEditor } from '../WorkflowEditorPage'
import { useFlowStore } from '../../components/FlowCanvas/store/useFlowStore'
import { useWorkflowStore } from '../../stores/workflow-store'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

function step(id: string, label: string) {
  return { id, type: 'LLM-CHAT', componentId: 'llm-chat', label, position: { x: 0, y: 0 }, configuration: {}, connections: [] }
}

const doc = {
  id: 'wf-1',
  metadata: { name: 'Fluxo', description: '', version: '1.0.0', category: '', tags: [] },
  configuration: {},
  steps: [step('a', 'Primeiro'), step('b', 'Segundo')],
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
  executionApiMock.get.mockResolvedValue({
    id: 'exec-9', workflowId: 'wf-1', status: 'COMPLETED',
    steps: [
      { stepId: 'a', status: 'COMPLETED', output: '{"resposta":"oi"}' },
      { stepId: 'b', status: 'FAILED', error: 'boom' },
    ],
  })
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function startRun(): Promise<(ev: AgUiEvent) => void> {
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
  const ui = within(container)
  await act(async () => { fireEvent.click(ui.getByTestId('editor-run')) })
  const modal = within(document.body)
  const confirm = await waitFor(() => modal.getByTestId('run-input-confirm'))
  await act(async () => { fireEvent.click(confirm) })
  await waitFor(() => expect(runMock).toHaveBeenCalledTimes(1))
  const onEvent = runMock.mock.calls[0][2]
  return (ev) => { act(() => { onEvent(ev) }) }
}

describe('WorkflowEditor — saída dos nós depois do run', () => {
  it('RUN_FINISHED lê o registro da execução e mescla a saída mantendo status e duração', async () => {
    const emit = await startRun()
    emit({ type: 'RUN_STARTED', runId: 'exec-9' })
    emit({ type: 'STEP_STARTED', stepId: 'a' })
    emit({ type: 'STEP_FINISHED', stepId: 'a', durationMs: 12 })
    emit({ type: 'STEP_STARTED', stepId: 'b' })
    emit({ type: 'STEP_FINISHED', stepId: 'b', status: 'STEP_FAILED', durationMs: 3, error: 'boom' })
    expect(executionApiMock.get).not.toHaveBeenCalled()

    emit({ type: 'RUN_FINISHED' })

    await waitFor(() => expect(useFlowStore.getState().executionState.a?.output).toEqual({ resposta: 'oi' }))
    expect(executionApiMock.get).toHaveBeenCalledWith('exec-9')
    const state = useFlowStore.getState().executionState
    expect(state.a).toMatchObject({ status: 'success', durationMs: 12 })
    // Passo sem saída gravada fica como os eventos deixaram.
    expect(state.b).toEqual({ status: 'error', durationMs: 3, error: 'boom' })
  })

  it('RUN_ERROR também preenche a saída dos passos que concluíram antes da falha', async () => {
    const emit = await startRun()
    emit({ type: 'RUN_STARTED', runId: 'exec-9' })
    emit({ type: 'STEP_FINISHED', stepId: 'a', durationMs: 12 })
    emit({ type: 'RUN_ERROR', message: 'falhou' })

    await waitFor(() => expect(useFlowStore.getState().executionState.a?.output).toEqual({ resposta: 'oi' }))
    expect(useFlowStore.getState().executionState.a.status).toBe('success')
  })

  it.each([
    [{ status: 'FAILED' }, 'editor.notif.execFailed', 'red'],
    // ExecutionStatus.CANCELLED é o valor que o AgUiController manda num run cancelado.
    [{ status: 'CANCELLED' }, 'editor.notif.execStopped', 'gray'],
    // Aprovação humana: o run suspende no portão e o resto do fluxo ainda não rodou.
    [{ status: 'PAUSED' }, 'editor.notif.execPaused', 'yellow'],
    [{ status: 'RUNNING' }, 'editor.notif.execUnknown', 'gray'],
    [{ status: 'COMPLETED' }, 'editor.notif.execFinished', 'teal'],
    [undefined, 'editor.notif.execFinished', 'teal'],
  ])('RUN_FINISHED com result %j avisa %s', async (result, title, color) => {
    const { notifications } = await import('@mantine/notifications')
    const emit = await startRun()
    emit({ type: 'RUN_STARTED', runId: 'exec-9' })
    emit(result ? { type: 'RUN_FINISHED', result } : { type: 'RUN_FINISHED' })

    const calls = vi.mocked(notifications.show).mock.calls.map(c => c[0])
    expect(calls.at(-1)).toMatchObject({ title, color })
    // Só um run COMPLETED (ou sem status) pode anunciar sucesso.
    if (result?.status && result.status !== 'COMPLETED') {
      expect(calls.some(c => c.title === 'editor.notif.execFinished')).toBe(false)
    }
  })

  it('não mistura saídas de um run antigo quando outro já começou', async () => {
    let resolveGet: (v: unknown) => void = () => {}
    executionApiMock.get.mockReturnValue(new Promise(r => { resolveGet = r }))
    const emit = await startRun()
    emit({ type: 'RUN_STARTED', runId: 'exec-9' })
    emit({ type: 'RUN_FINISHED' })
    await waitFor(() => expect(executionApiMock.get).toHaveBeenCalledWith('exec-9'))

    // Um novo run começa antes da leitura voltar.
    act(() => { useFlowStore.getState().startExecution('wf-1') })
    await act(async () => {
      resolveGet({ steps: [{ stepId: 'a', status: 'COMPLETED', output: '"velha"' }] })
    })

    expect(useFlowStore.getState().executionState.a).toBeUndefined()
  })
})
