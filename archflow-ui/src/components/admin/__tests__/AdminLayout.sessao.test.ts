/**
 * Menu de Administração e sessão depois de um F5.
 *
 * - RCHFLW-145: o ADMIN do backend vira superadmin na UI, e o menu dele só tinha Tenants, Observabilidade,
 *   Configuração global e Uso e cobrança. O /admin abria a visão geral do workspace — fora do menu, nada
 *   destacado — e Usuários, Chaves de API, Skills, MCP, Agendamentos e Linktor só abriam digitando a URL.
 * - RCHFLW-144: depois de um F5 o token voltava do sessionStorage, mas o usuário só era buscado no login(): o
 *   rodapé perdia o bloco do usuário e o único Sair do app.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { Navigate, Route, Routes } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'
import { MemoryDataRouter, ultimoRoteador } from '../../../lib/__tests__/roteadorDeTeste'
import { tokenComPapeis } from '../../../lib/__tests__/papeisDeTeste'

const authApiMock = vi.hoisted(() => ({ logout: vi.fn(), me: vi.fn(), login: vi.fn() }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('../../../services/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../services/api')>()
  return { ...actual, authApi: authApiMock }
})
vi.mock('../ImpersonationBanner', () => ({ ImpersonationBanner: () => null }))

import AdminLayout from '../AdminLayout'
import ProtectedRoute from '../../ProtectedRoute'
import { useAuthStore } from '../../../stores/auth-store'
import { useTenantStore } from '../../../stores/useTenantStore'

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
  authApiMock.me.mockResolvedValue({ userId: 'u-1', username: 'admin', email: 'admin@archflow', roles: ['ADMIN'] })
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

/** A sessão como fica depois de um F5: token e papel no sessionStorage, nenhum usuário no store. */
function sessaoRecarregada(roles: string[], role: 'superadmin' | 'tenant_admin') {
  const token = tokenComPapeis(roles)
  sessionStorage.setItem('archflow_token', token)
  sessionStorage.setItem('archflow_refresh_token', 'refresh')
  useAuthStore.setState({ token, user: null, loading: false })
  useTenantStore.getState().exitImpersonation()
  useTenantStore.getState().setRole(role)
}

const pagina = (testId: string) => h('div', { 'data-testid': testId })

/** As rotas de Administração do App, com páginas simuladas. */
async function render(path: string) {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(
      h(MantineProvider, null,
        h(MemoryDataRouter, { initialEntries: [path] },
          h(Routes, null,
            h(Route, { path: '/login', element: pagina('login') }),
            h(Route, { path: '/admin', element: h(ProtectedRoute, { requiredRole: 'tenant_admin' }, h(AdminLayout)) },
              h(Route, { index: true, element: h(Navigate, { to: 'workspace', replace: true }) }),
              h(Route, { path: 'tenants', element: h(ProtectedRoute, { requiredRole: 'superadmin' }, pagina('tenants')) }),
              h(Route, { path: 'workspace', element: pagina('workspace') }),
              h(Route, { path: 'workspace/users', element: pagina('users') }),
              h(Route, { path: 'workspace/api-keys', element: pagina('api-keys') }),
              h(Route, { path: 'skills', element: pagina('skills') }),
              h(Route, { path: 'mcp', element: pagina('mcp') }),
              h(Route, { path: 'triggers', element: pagina('triggers') }),
              h(Route, { path: 'linktor', element: pagina('linktor') }),
              h(Route, { path: 'linktor/inbox', element: pagina('linktor-inbox') }))))),
    )
  })
  return within(container)
}

const path = () => ultimoRoteador!.state.location.pathname

describe('Menu de Administração do superadmin (RCHFLW-145)', () => {
  it('o /admin abre na visão geral do workspace, e ela está no menu, destacada', async () => {
    sessaoRecarregada(['ADMIN'], 'superadmin')
    const ui = await render('/admin')

    await waitFor(() => expect(path()).toBe('/admin/workspace'))
    const overview = ui.getByRole('button', { name: 'admin.layout.overview' })
    expect(overview.getAttribute('aria-current')).toBe('page')
    // As páginas da plataforma continuam no menu.
    for (const key of ['tenants', 'observability', 'globalConfig', 'usageBilling']) {
      expect(ui.getByRole('button', { name: `admin.layout.${key}` })).toBeTruthy()
    }
  })

  it.each([
    ['users', '/admin/workspace/users'],
    ['apiKeys', '/admin/workspace/api-keys'],
    ['skills', '/admin/skills'],
    ['mcp', '/admin/mcp'],
    ['triggers', '/admin/triggers'],
    ['linktorInbox', '/admin/linktor/inbox'],
    ['linktorConfig', '/admin/linktor'],
  ])('%s é alcançável pelo menu (%s)', async (key, destino) => {
    sessaoRecarregada(['ADMIN'], 'superadmin')
    const ui = await render('/admin/workspace')

    await act(async () => { fireEvent.click(ui.getByRole('button', { name: `admin.layout.${key}` })) })

    expect(path()).toBe(destino)
    expect(ui.getByRole('button', { name: `admin.layout.${key}` }).getAttribute('aria-current')).toBe('page')
  })

  it('o admin de tenant continua com o menu do workspace e sem as páginas da plataforma', async () => {
    sessaoRecarregada(['TENANT_ADMIN'], 'tenant_admin')
    authApiMock.me.mockResolvedValue({ userId: 'u-2', username: 'gestor', email: '', roles: ['TENANT_ADMIN'] })
    const ui = await render('/admin/workspace')

    expect(ui.getByRole('button', { name: 'admin.layout.users' })).toBeTruthy()
    expect(ui.getByRole('button', { name: 'admin.layout.skills' })).toBeTruthy()
    expect(ui.queryByRole('button', { name: 'admin.layout.tenants' })).toBeNull()
  })
})

describe('Sessão depois de um F5 (RCHFLW-144)', () => {
  it('o bloco do usuário e o Sair voltam: o usuário é buscado uma vez, mesmo com rotas protegidas aninhadas', async () => {
    sessaoRecarregada(['ADMIN'], 'superadmin')
    const ui = await render('/admin/tenants')

    await waitFor(() => expect(ui.getByRole('button', { name: 'header.logout' })).toBeTruthy())
    expect(ui.getByText('admin')).toBeTruthy()
    expect(authApiMock.me).toHaveBeenCalledTimes(1)
    expect(useAuthStore.getState().user?.username).toBe('admin')
  })

  it('sem sessão não busca o usuário: vai ao login', async () => {
    useAuthStore.setState({ token: null, user: null })
    await render('/admin/workspace')

    expect(path()).toBe('/login')
    expect(authApiMock.me).not.toHaveBeenCalled()
  })
})
