/**
 * RCHFLW-142: com o editor "não salvo", o Sair da barra lateral encerrava a sessão e ia direto ao /login. O
 * logout() removia o token primeiro, o ProtectedRoute desmontava o editor antes de o useBlocker ver a
 * navegação, e a edição se perdia sem o modal — que, mesmo se aparecesse, não teria token para "Salvar e sair".
 *
 * <p>Aqui roda o AppLayout real, com o ProtectedRoute, o auth-store e o editor reais (copiloto simulado): o Sair
 * passa pelo mesmo aviso das outras saídas, e a sessão só termina quando a saída é liberada.</p>
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { Route, Routes } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'
import { MemoryDataRouter, ultimoRoteador } from '../../../lib/__tests__/roteadorDeTeste'
import { tokenComPapeis } from '../../../lib/__tests__/papeisDeTeste'

const workflowApiMock = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn(), list: vi.fn() }))
const authApiMock = vi.hoisted(() => ({ logout: vi.fn(), me: vi.fn(), login: vi.fn() }))
const tokenNoUpdate = vi.hoisted(() => ({ valor: undefined as string | null | undefined }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('../../../services/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../services/api')>()
  return { ...actual, workflowApi: workflowApiMock, executionApi: {}, authApi: authApiMock }
})
vi.mock('../../../services/approval-api', () => ({ approvalApi: { pendingCount: vi.fn(async () => ({ count: 0 })) } }))
vi.mock('../../../services/workflow-yaml-api', () => ({ workflowYamlApi: { get: vi.fn(), update: vi.fn() } }))
vi.mock('../../../services/agui-client', () => ({ runAgUiWorkflow: vi.fn(() => () => {}) }))
vi.mock('@copilotkit/react-core/v2', () => ({ CopilotKitProvider: ({ children }: { children: unknown }) => children }))
vi.mock('@copilotkit/react-core/v2/styles.css', () => ({}))
vi.mock('@ag-ui/client', () => ({ HttpAgent: class {} }))
vi.mock('../../copilot/CopilotAppOperator', () => ({ default: () => null }))
vi.mock('../../copilot/AppCopilotSidebar', () => ({ default: () => null }))
vi.mock('../../copilot/CopilotGenerateBridge', () => ({
  default: () => null,
  COPILOT_GENERATE_AGENT_ID: 'archflow-generate',
  requestCopilotGeneration: vi.fn(),
  newCopilotGenerateNotificationId: () => 'archflow-copilot-generate-test',
}))
vi.mock('../GlobalSearch', () => ({ default: () => null }))
vi.mock('../LanguageSwitcher', () => ({ default: () => null }))
vi.mock('../../admin/ImpersonationBanner', () => ({ ImpersonationBanner: () => null }))
vi.mock('../../PublishModal', () => ({ PublishModal: () => null }))
vi.mock('../../NodePalette', () => ({ NodePalette: () => null }))
vi.mock('../../PropertyPanel', () => ({ PropertyPanel: () => null }))
vi.mock('../../../pages/LoginArchitecture', () => ({ default: () => null }))
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn(), hide: vi.fn() } }))

import AppLayout from '../AppLayout'
import ProtectedRoute from '../../ProtectedRoute'
import LoginPage from '../../../pages/LoginPage'
import { WorkflowEditor } from '../../../pages/WorkflowEditorPage'
import { useAuthStore } from '../../../stores/auth-store'
import { useFlowStore } from '../../FlowCanvas/store/useFlowStore'
import { useWorkflowStore } from '../../../stores/workflow-store'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

function doc() {
  return {
    id: 'wf-1',
    updatedAt: '2026-11-25T10:00:00Z',
    metadata: { name: 'QA2 navegacao A', description: '', version: '1.0.0', category: '', tags: [] },
    configuration: {},
    steps: [{ id: 'a', type: 'LLM-CHAT', componentId: 'llm-chat', label: 'A', position: { x: 0, y: 0 },
      configuration: { systemPrompt: 'antigo' }, connections: [] }],
  }
}

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  const token = tokenComPapeis(['DESIGNER'])
  sessionStorage.setItem('archflow_token', token)
  sessionStorage.setItem('archflow_refresh_token', 'refresh')
  useAuthStore.setState({
    token,
    user: { id: 'u-1', username: 'admin', name: 'admin', email: 'admin@archflow', roles: ['DESIGNER'] },
  })
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
  workflowApiMock.list.mockResolvedValue([])
  workflowApiMock.update.mockImplementation(async (_id: string, body: Record<string, unknown>) => {
    // O "Salvar e sair" tem de gravar com a sessão ainda de pé.
    tokenNoUpdate.valor = sessionStorage.getItem('archflow_token')
    return { ...body, updatedAt: '2026-11-25T11:00:00Z' }
  })
  authApiMock.logout.mockResolvedValue(undefined)
  tokenNoUpdate.valor = undefined
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function render() {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(
      h(MantineProvider, null,
        h(MemoryDataRouter, { initialEntries: ['/editor/wf-1'] },
          h(Routes, null,
            h(Route, { path: '/login', element: h(LoginPage) }),
            h(Route, { element: h(ProtectedRoute, null, h(AppLayout)) },
              h(Route, { path: '/editor/:id', element: h(WorkflowEditor) }),
              h(Route, { path: '/workflows', element: h('div', { 'data-testid': 'workflow-list' }) }))))),
    )
  })
  await waitFor(() => expect(useFlowStore.getState().canvasApi).not.toBeNull())
}

const body = () => within(document.body)
const path = () => ultimoRoteador!.state.location.pathname

async function click(el: HTMLElement) {
  await act(async () => { fireEvent.click(el) })
}

async function editarESair() {
  await render()
  await act(async () => { useFlowStore.getState().updateNodeConfig('a', 'systemPrompt', 'novo') })
  await click(body().getByRole('button', { name: 'header.logout' }))
}

describe('Sair com o editor não salvo (RCHFLW-142)', () => {
  it('o Sair passa pelo aviso de não salvo, e Continuar editando mantém a sessão e a edição', async () => {
    await editarESair()

    await waitFor(() => expect(body().getByTestId('editor-unsaved-modal')).toBeTruthy())
    expect(path()).toBe('/editor/wf-1')
    expect(sessionStorage.getItem('archflow_token')).not.toBeNull()
    expect(authApiMock.logout).not.toHaveBeenCalled()

    await click(body().getByTestId('editor-unsaved-stay'))
    expect(path()).toBe('/editor/wf-1')
    expect(sessionStorage.getItem('archflow_token')).not.toBeNull()
    expect(useAuthStore.getState().token).not.toBeNull()
    expect(useFlowStore.getState().nodes[0].data.config).toEqual({ systemPrompt: 'novo' })
  })

  it('Descartar e sair encerra a sessão e chega ao login', async () => {
    await editarESair()
    await waitFor(() => body().getByTestId('editor-unsaved-modal'))

    await click(body().getByTestId('editor-unsaved-discard'))

    await waitFor(() => expect(path()).toBe('/login'))
    await waitFor(() => expect(sessionStorage.getItem('archflow_token')).toBeNull())
    expect(authApiMock.logout).toHaveBeenCalledTimes(1)
    expect(useAuthStore.getState().token).toBeNull()
    expect(workflowApiMock.update).not.toHaveBeenCalled()
  })

  it('Salvar e sair grava com o token ainda válido e só então encerra a sessão', async () => {
    await editarESair()
    await waitFor(() => body().getByTestId('editor-unsaved-modal'))

    await click(body().getByTestId('editor-unsaved-save'))

    await waitFor(() => expect(path()).toBe('/login'))
    await waitFor(() => expect(sessionStorage.getItem('archflow_token')).toBeNull())
    expect(workflowApiMock.update).toHaveBeenCalledTimes(1)
    expect(tokenNoUpdate.valor).not.toBeNull()
    expect(authApiMock.logout).toHaveBeenCalledTimes(1)
  })

  it('sem alteração, o Sair encerra a sessão direto, sem perguntar', async () => {
    await render()

    await click(body().getByRole('button', { name: 'header.logout' }))

    await waitFor(() => expect(path()).toBe('/login'))
    await waitFor(() => expect(sessionStorage.getItem('archflow_token')).toBeNull())
    expect(body().queryByTestId('editor-unsaved-modal')).toBeNull()
    expect(authApiMock.logout).toHaveBeenCalledTimes(1)
    // O estado do Sair sai do histórico: voltar a esta entrada não encerra outra sessão.
    expect(ultimoRoteador!.state.location.state).toBeNull()
  })

  // O logout roda quando o Sair chega ao /login. Se o formulário já aceitasse um login, o fim do logout (remover
  // os tokens, token:null) chegando depois apagaria a sessão nova e o usuário voltaria a esta tela.
  it('o formulário do login espera o logout terminar antes de aceitar um login', async () => {
    let terminarLogout: () => void = () => {}
    authApiMock.logout.mockReturnValueOnce(new Promise<void>((r) => { terminarLogout = r }))
    await render()

    await click(body().getByRole('button', { name: 'header.logout' }))
    await waitFor(() => expect(path()).toBe('/login'))
    const entrar = body().getByRole('button', { name: 'login.signIn' }) as HTMLButtonElement
    expect(entrar.disabled).toBe(true)
    // O Enter num campo submete o formulário mesmo com o botão desabilitado.
    await act(async () => { fireEvent.submit(entrar.closest('form')!) })
    expect(authApiMock.login).not.toHaveBeenCalled()

    await act(async () => { terminarLogout() })
    await waitFor(() => expect(entrar.disabled).toBe(false))
    expect(useAuthStore.getState().token).toBeNull()
  })
})
