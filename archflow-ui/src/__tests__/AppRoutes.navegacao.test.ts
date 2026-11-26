/**
 * RCHFLW-188 — navegação:
 * - item 5: deslogado, abrir /workflows, /executions?status=FAILED ou /admin/workspace/users e entrar caía
 *   sempre em /. O ProtectedRoute (e o 401 da API) mandavam para /login sem guardar a origem.
 * - item 6: /foo/bar, /admin/nope, /marketplace e /admin/brainsentry mostravam uma página toda em branco: não
 *   havia rota '*'.
 *
 * <p>Monta as rotas reais do App (AppRoutes) com os layouts e as páginas simulados.</p>
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'
import { MemoryDataRouter, ultimoRoteador } from '../lib/__tests__/roteadorDeTeste'
import { tokenComPapeis } from '../lib/__tests__/papeisDeTeste'

const authApiMock = vi.hoisted(() => ({ logout: vi.fn(), me: vi.fn(), login: vi.fn() }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('../services/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/api')>()
  return { ...actual, authApi: authApiMock }
})
vi.mock('../pages/LoginArchitecture', () => ({ default: () => null }))
vi.mock('../components/layout/LanguageSwitcher', () => ({ default: () => null }))
// Os layouts reais puxam o copiloto e as APIs; aqui só importa onde a página cai.
vi.mock('../components/layout/AppLayout', async () => {
  const { createElement } = await import('react')
  const { Outlet } = await import('react-router-dom')
  return { default: () => createElement('div', { 'data-testid': 'app-layout' }, createElement(Outlet)) }
})
vi.mock('../components/admin/AdminLayout', async () => {
  const { createElement } = await import('react')
  const { Outlet } = await import('react-router-dom')
  return { default: () => createElement('div', { 'data-testid': 'admin-layout' }, createElement(Outlet)) }
})
vi.mock('../pages/WorkflowEditorPage', () => ({ WorkflowEditor: () => 'editor-page' }))
vi.mock('../components/copilot/CopilotGenerateBridge', () => ({ default: () => null, requestCopilotGeneration: vi.fn() }))
vi.mock('../pages/DashboardPage', () => ({ default: () => 'dashboard-page' }))
vi.mock('../pages/WorkflowListPage', () => ({ default: () => 'workflow-list-page' }))
vi.mock('../pages/ExecutionHistoryPage', () => ({ default: () => 'execution-history-page' }))
vi.mock('../pages/admin/tenant/UserManagement', () => ({ default: () => 'user-management-page' }))

import { AppRoutes } from '../App'
import { useAuthStore } from '../stores/auth-store'
import { useTenantStore } from '../stores/useTenantStore'

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  useAuthStore.setState({ token: null, user: null, loading: false, error: null })
  useTenantStore.getState().setRole('viewer')
  const token = tokenComPapeis(['ADMIN'])
  authApiMock.login.mockResolvedValue({ token, refreshToken: 'refresh', userId: 'u-1', username: 'admin',
    email: '', roles: ['ADMIN'] })
  authApiMock.me.mockResolvedValue({ userId: 'u-1', username: 'admin', email: '', roles: ['ADMIN'] })
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function render(path: string) {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, null, h(MemoryDataRouter, { initialEntries: [path] }, h(AppRoutes))))
  })
  return within(container)
}

const onde = () => `${ultimoRoteador!.state.location.pathname}${ultimoRoteador!.state.location.search}`

function logado() {
  const token = tokenComPapeis(['ADMIN'])
  sessionStorage.setItem('archflow_token', token)
  useAuthStore.setState({ token, user: { id: 'u-1', username: 'admin', name: 'admin', email: '', roles: ['ADMIN'] } })
  useTenantStore.getState().setRole('superadmin')
}

async function entrar(ui: ReturnType<typeof within>) {
  const [usuario] = ui.getAllByRole('textbox')
  const senha = container.querySelector('input[type="password"]') as HTMLInputElement
  await act(async () => { fireEvent.change(usuario, { target: { value: 'admin' } }) })
  await act(async () => { fireEvent.change(senha, { target: { value: 'admin123' } }) })
  await act(async () => { fireEvent.submit(senha.closest('form')!) })
}

describe('O login volta para a página pedida (RCHFLW-188, item 5)', () => {
  it.each([
    '/workflows',
    '/executions?status=FAILED',
    '/admin/workspace/users',
  ])('deslogado em %s: o login guarda a página e volta para ela, com a query', async (pedida) => {
    const ui = await render(pedida)
    await waitFor(() => expect(ultimoRoteador!.state.location.pathname).toBe('/login'))
    expect(new URLSearchParams(ultimoRoteador!.state.location.search).get('from')).toBe(pedida)

    await entrar(ui)

    await waitFor(() => expect(onde()).toBe(pedida))
  })

  it('um "from" para fora do app é ignorado: o login cai no início', async () => {
    const ui = await render('/login?from=%2F%2Fevil.example%2Fx')

    await entrar(ui)

    await waitFor(() => expect(onde()).toBe('/'))
  })
})

describe('Rota desconhecida (RCHFLW-188, item 6)', () => {
  it.each(['/foo/bar', '/marketplace'])('%s mostra "não encontrado" dentro do app, com o menu', async (rota) => {
    logado()
    const ui = await render(rota)

    const card = await waitFor(() => ui.getByTestId('route-not-found'))
    expect(ui.getByTestId('app-layout').contains(card)).toBe(true)
    expect(card.textContent).toContain('common.notFoundTitle')

    await act(async () => { fireEvent.click(within(card).getByRole('button', { name: 'common.backHome' })) })
    expect(onde()).toBe('/')
  })

  it.each(['/admin/nope', '/admin/brainsentry'])('%s mostra "não encontrado" na Administração', async (rota) => {
    logado()
    const ui = await render(rota)

    const card = await waitFor(() => ui.getByTestId('route-not-found'))
    expect(ui.getByTestId('admin-layout').contains(card)).toBe(true)
    await act(async () => { fireEvent.click(within(card).getByRole('button', { name: 'common.backHome' })) })
    await waitFor(() => expect(onde()).toBe('/admin/workspace'))
  })

  it('deslogado, a rota desconhecida passa pelo login primeiro', async () => {
    await render('/foo/bar')
    await waitFor(() => expect(ultimoRoteador!.state.location.pathname).toBe('/login'))
  })
})
