/**
 * Parar do editor (RCHFLW-54): além de fechar o stream, o Parar cancela a execução no servidor — a
 * função de parar do agui-client faz o POST /api/executions/{id}/cancel com o id do RUN_STARTED (ver
 * agui-client.test). Aqui: o editor chama essa função e só anuncia "interrompida" quando o servidor
 * aceitou; se recusou, avisa que a execução pode continuar rodando.
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
const stopMock = vi.hoisted(() => vi.fn(async (): Promise<'stopped' | 'byDisconnect' | 'failed'> => 'stopped'))
const runMock = vi.hoisted(() => vi.fn((_id: string, _input: unknown, _onEvent: (ev: AgUiEvent) => void) => stopMock))

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

describe('WorkflowEditor — Parar', () => {
  it('Parar durante o run chama a função de parar do cliente (que cancela no servidor) e anuncia a interrupção', async () => {
    const { notifications } = await import('@mantine/notifications')
    const emit = await startRun()
    emit({ type: 'RUN_STARTED', runId: 'exec-9' })
    emit({ type: 'STEP_STARTED', stepId: 'a' })

    await act(async () => { fireEvent.click(within(container).getByTestId('editor-run')) })

    expect(stopMock).toHaveBeenCalledTimes(1)
    expect(useFlowStore.getState().isExecuting).toBe(false)
    await waitFor(() => expect(vi.mocked(notifications.show).mock.calls.map(c => c[0]).at(-1))
      .toMatchObject({ title: 'editor.notif.execStopped' }))
  })

  it('servidor recusa o cancelamento → avisa em vermelho, nunca "interrompida"', async () => {
    const { notifications } = await import('@mantine/notifications')
    stopMock.mockResolvedValueOnce('failed')
    const emit = await startRun()
    emit({ type: 'RUN_STARTED', runId: 'exec-9' })

    await act(async () => { fireEvent.click(within(container).getByTestId('editor-run')) })

    await waitFor(() => expect(vi.mocked(notifications.show).mock.calls.map(c => c[0]).at(-1))
      .toMatchObject({ title: 'editor.notif.execStopError', color: 'red' }))
    expect(vi.mocked(notifications.show).mock.calls.some(c => c[0].title === 'editor.notif.execStopped')).toBe(false)
  })

  // DESIGNER/EXECUTOR: executar pede workflow:execute, cancelar pela API pede execution:manage (só
  // ADMIN). O 403 não é "pode continuar rodando" — a desconexão cancela; a tela diz a causa real.
  it('sem permissão de cancelar pela API (403) → avisa que a desconexão interrompe, sem erro vermelho', async () => {
    const { notifications } = await import('@mantine/notifications')
    stopMock.mockResolvedValueOnce('byDisconnect')
    const emit = await startRun()
    emit({ type: 'RUN_STARTED', runId: 'exec-9' })

    await act(async () => { fireEvent.click(within(container).getByTestId('editor-run')) })

    await waitFor(() => expect(vi.mocked(notifications.show).mock.calls.map(c => c[0]).at(-1))
      .toMatchObject({ title: 'editor.notif.execStopping', message: 'editor.notif.execStoppingMsg', color: 'yellow' }))
    expect(vi.mocked(notifications.show).mock.calls.some(c => c[0].title === 'editor.notif.execStopError')).toBe(false)
  })
})
