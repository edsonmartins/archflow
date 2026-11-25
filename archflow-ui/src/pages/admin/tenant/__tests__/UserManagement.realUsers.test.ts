/**
 * Admin › Usuários sobre o login de verdade (RCHFLW-85): a lista é a do backend (sem "João Silva" /
 * "Maria Santos"), os papéis e as permissões vêm de `GET /admin/workspace/roles`, o convite mostra o link
 * de aceite uma vez (o produto não envia e-mail) e uma recusa do backend aparece como frase no diálogo.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'

const userApiMock = vi.hoisted(() => ({
  list: vi.fn(), roles: vi.fn(), invite: vi.fn(), update: vi.fn(), remove: vi.fn(), revoke: vi.fn(),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, o?: Record<string, unknown>) => (o && 'email' in o ? `${k}:${String(o.email)}` : k),
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))
vi.mock('../../../../services/admin-api', () => ({
  userApi: userApiMock,
  invitationLink: (token: string) => `http://localhost/accept-invite?token=${encodeURIComponent(token)}`,
}))
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn() } }))
vi.mock('../../../../lib/confirm', () => ({ confirmAction: vi.fn() }))

import UserManagement from '../UserManagement'

const ROLES = [
  { name: 'ADMIN', description: 'Full system access', permissions: ['*:*'] },
  { name: 'DESIGNER', description: 'Edit', permissions: ['agent:read', 'workflow:create', 'workflow:read'] },
  { name: 'EXECUTOR', description: 'Run', permissions: ['workflow:execute', 'workflow:read'] },
  { name: 'VIEWER', description: 'Read', permissions: ['agent:read', 'execution:read', 'workflow:read'] },
]
const ADMIN = {
  id: 'user-1', name: 'System Administrator', email: 'admin@archflow.local', role: 'ADMIN', status: 'active',
  lastAccessAt: '2026-11-25T12:00:00Z', invitationExpiresAt: null,
}

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
  vi.clearAllMocks()
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  ;(globalThis as any).ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} }
  Element.prototype.scrollIntoView ??= function () {}
  userApiMock.list.mockResolvedValue([ADMIN])
  userApiMock.roles.mockResolvedValue(ROLES)
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
    root!.render(h(MantineProvider, null, h(UserManagement)))
  })
}

const byTestId = (id: string) => document.body.querySelector(`[data-testid="${id}"]`) as HTMLElement | null

async function click(el: Element | null) {
  expect(el).not.toBeNull()
  await act(async () => {
    el!.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    el!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  })
}

async function waitFor(id: string) {
  for (let i = 0; i < 50 && byTestId(id) === null; i++) {
    await act(async () => { await new Promise((r) => setTimeout(r, 20)) })
  }
  expect(byTestId(id)).not.toBeNull()
}

async function type(input: HTMLInputElement, value: string) {
  await act(async () => {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!
    setter.call(input, value)
    input.dispatchEvent(new Event('input', { bubbles: true }))
  })
}

async function fillInvite(email: string, roleLabel: string) {
  await click(byTestId('user-invite'))
  await waitFor('invite-form')
  await type(byTestId('invite-email') as HTMLInputElement, email)
  await click(byTestId('invite-role'))
  const option = Array.from(document.body.querySelectorAll('[role="option"]'))
    .find((o) => o.textContent === roleLabel)
  await click(option ?? null)
}

describe('UserManagement — usuários reais do workspace', () => {
  it('lista o que o backend devolve — nenhum usuário de demonstração', async () => {
    await render()

    const rows = byTestId('user-list')!.querySelectorAll('tbody tr')
    expect(rows).toHaveLength(1)
    expect(byTestId('user-row-user-1')!.textContent).toContain('System Administrator')
    expect(byTestId('user-row-user-1')!.textContent).toContain('admin.tenant.users.roles.ADMIN')
    expect(document.body.textContent).not.toContain('João Silva')
    expect(document.body.textContent).not.toContain('Maria Santos')
  })

  it('os cartões de papel são os do backend, com as permissões que cada um concede', async () => {
    await render()

    for (const role of ROLES) {
      expect(byTestId(`role-card-${role.name}`)).not.toBeNull()
    }
    const viewer = byTestId('role-card-VIEWER')!.textContent
    expect(viewer).toContain('admin.tenant.users.permissionNames.execution_read')
    expect(viewer).not.toContain('admin.tenant.users.permissionNames.workflow_create')
    expect(byTestId('role-card-ADMIN')!.textContent).toContain('admin.tenant.users.permissionNames.all')
    // A matriz antiga (admin/editor/viewer) não existe no backend.
    expect(byTestId('role-card-editor')).toBeNull()
  })

  it('convidar envia o papel do backend e mostra o link de aceite uma vez', async () => {
    userApiMock.invite.mockResolvedValue({
      user: { id: 'user-2', name: 'viewer.teste@example.com', email: 'viewer.teste@example.com', role: 'VIEWER',
        status: 'invited', lastAccessAt: null, invitationExpiresAt: '2026-11-28T12:00:00Z' },
      invitationToken: 'user-2.SEGREDO',
      expiresAt: '2026-11-28T12:00:00Z',
    })
    await render()
    await fillInvite('viewer.teste@example.com', 'admin.tenant.users.roles.VIEWER')
    await click(byTestId('invite-send'))

    expect(userApiMock.invite).toHaveBeenCalledWith({ email: 'viewer.teste@example.com', role: 'VIEWER' })
    await waitFor('invite-link')
    expect(byTestId('invite-link-url')!.textContent)
      .toBe('http://localhost/accept-invite?token=user-2.SEGREDO')
    expect(byTestId('user-row-user-2')!.textContent).toContain('admin.tenant.users.status.invited')
  })

  it('recusa do backend aparece como frase no diálogo, que continua aberto', async () => {
    userApiMock.invite.mockRejectedValue(new Error('A user with the e-mail ana@acme.com already exists'))
    await render()
    await fillInvite('ana@acme.com', 'admin.tenant.users.roles.DESIGNER')
    await click(byTestId('invite-send'))

    await waitFor('invite-error')
    expect(byTestId('invite-error')!.textContent).toContain('already exists')
    expect(byTestId('invite-error')!.textContent).not.toContain('{')
    expect(byTestId('invite-form')).not.toBeNull()
  })

  it('recusa com código: o diálogo mostra a frase traduzida, não a frase em inglês do backend', async () => {
    userApiMock.invite.mockRejectedValue(Object.assign(
      new Error('A user with the e-mail ana@acme.com already exists'), { code: 'USER_EXISTS' }))
    await render()
    await fillInvite('ana@acme.com', 'admin.tenant.users.roles.DESIGNER')
    await click(byTestId('invite-send'))

    await waitFor('invite-error')
    expect(byTestId('invite-error')!.textContent).toContain('workspaceUserErrors.USER_EXISTS')
    expect(byTestId('invite-error')!.textContent).not.toContain('already exists')
  })

  it('sem resposta do backend, o erro aparece — nada de lista inventada', async () => {
    userApiMock.list.mockRejectedValue(new Error('Administration endpoints require the ADMIN role'))
    await render()

    expect(byTestId('users-error')!.textContent).toContain('require the ADMIN role')
    expect(byTestId('user-list')!.querySelectorAll('tbody tr')).toHaveLength(0)
  })
})
