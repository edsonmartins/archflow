/**
 * RCHFLW-103: gatilhos agendados e o Agent lab rodam no tenant da identidade. O servidor recusa (403)
 * outro tenant para quem não é admin; a tela mostra o tenant do acesso e não deixa um usuário comum
 * trocá-lo. O admin começa no tenant em que está e pode escolher outro; sem login (auth desligada, dev)
 * o campo continua livre.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'

const triggersMock = vi.hoisted(() => ({
  list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn(), fireNow: vi.fn(),
}))
const playgroundMock = vi.hoisted(() => ({ invoke: vi.fn(), message: vi.fn(), invocation: vi.fn() }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn() } }))
// A tabela desenha em canvas; não é o que está em teste.
vi.mock('../../components/VTable', () => ({
  VxTable: () => null,
  colunaApagada: (c: unknown) => c,
  colunaStatus: (c: unknown) => c,
  colunaDupla: (c: unknown) => c,
  colunaAcoes: () => ({}),
}))
vi.mock('../../components/PropertyPanel/useCatalog', () => ({
  useCatalog: () => ({
    agents: [{ id: 'conversational-agent', displayName: 'Conversational Agent' }],
    assistants: [], tools: [], embeddings: [], memories: [], vectorstores: [], chains: [],
    loading: false, usingFallback: false,
  }),
}))
vi.mock('../../services/triggers-api', () => ({ triggersApi: triggersMock }))
vi.mock('../../services/agent-playground-api', async importOriginal => {
  const real = await importOriginal<typeof import('../../services/agent-playground-api')>()
  return { ...real, agentPlaygroundApi: playgroundMock }
})

import ScheduledTriggersPage from '../ScheduledTriggersPage'
import AgentPlaygroundPage from '../AgentPlaygroundPage'
import { useAuthStore } from '../../stores/auth-store'
import { useTenantStore } from '../../stores/useTenantStore'
import { actingTenant, SERVER_DEFAULT_TENANT } from '../../lib/actingTenant'

function base64Url(value: object): string {
  return btoa(JSON.stringify(value)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_')
}

/** JWT só com o payload que a UI lê; a assinatura é falsa porque quem confere é o backend. */
function token(roles: string[], tenantId?: string): string {
  const payload: Record<string, unknown> = { sub: 'u-teste', type: 'access', roles }
  if (tenantId) payload.tenantId = tenantId
  return `${base64Url({ alg: 'HS256', typ: 'JWT' })}.${base64Url(payload)}.assinatura`
}

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
  vi.clearAllMocks()
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  ;(globalThis as any).ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} }
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  Element.prototype.scrollIntoView ??= () => {}
  if (!(document as any).fonts) {
    Object.defineProperty(document, 'fonts', {
      value: { addEventListener() {}, removeEventListener() {} }, configurable: true,
    })
  }
  sessionStorage.clear()
  useAuthStore.setState({ token: null })
  useTenantStore.setState({ impersonating: null, currentRole: 'viewer' })
  triggersMock.list.mockResolvedValue([])
  triggersMock.create.mockImplementation(async (t: Record<string, unknown>) => ({ id: 'trg-1', ...t }))
  playgroundMock.invoke.mockResolvedValue({
    requestId: 'req-1', tenantId: 'x', agentId: 'conversational-agent', status: 'ACCEPTED', timestamp: '',
  })
  playgroundMock.invocation.mockResolvedValue({
    requestId: 'req-1', tenantId: 'x', agentId: 'conversational-agent', status: 'COMPLETED', attempts: 1,
  })
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function render(page: () => ReturnType<typeof h>) {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, { env: 'test' }, page()))
  })
  return within(container)
}

const body = () => within(document.body)

async function pickAgent(testId: string) {
  await act(async () => { fireEvent.click(body().getByTestId(testId)) })
  const option = await waitFor(() => body().getByRole('option', { name: /Conversational Agent/ }))
  await act(async () => { fireEvent.click(option) })
}

async function type(el: HTMLElement, value: string) {
  await act(async () => { fireEvent.change(el, { target: { value } }) })
}

async function abrirNovoGatilho() {
  const ui = await render(() => h(ScheduledTriggersPage))
  await act(async () => { fireEvent.click(ui.getByTestId('trigger-new')) })
  await waitFor(() => body().getByTestId('trigger-tenant'))
  await type(body().getByTestId('trigger-name'), 'Diário')
  await type(body().getByTestId('trigger-cron'), '0 0 2 * * ?')
  await pickAgent('trigger-agent')
}

describe('actingTenant (RCHFLW-103)', () => {
  it('sem login: livre, sem tenant sugerido', () => {
    expect(actingTenant(null, null)).toEqual({ tenantId: null, locked: false })
  })
  it('usuário comum: o tenant do token, travado', () => {
    expect(actingTenant(token(['VIEWER'], 'tenant-a'), null)).toEqual({ tenantId: 'tenant-a', locked: true })
  })
  it('token sem a claim: o tenant padrão do servidor, travado', () => {
    expect(actingTenant(token(['DESIGNER']), null)).toEqual({ tenantId: SERVER_DEFAULT_TENANT, locked: true })
  })
  it('admin: o tenant personificado (ou o do token), livre', () => {
    expect(actingTenant(token(['ROLE_ADMIN'], 'tenant-a'), { id: 'tenant-b' }))
      .toEqual({ tenantId: 'tenant-b', locked: false })
    expect(actingTenant(token(['ADMIN'], 'tenant-a'), null)).toEqual({ tenantId: 'tenant-a', locked: false })
  })
})

describe('ScheduledTriggersPage — tenant do gatilho (RCHFLW-103)', () => {
  it('usuário comum: o campo mostra o tenant do acesso, só leitura, e é ele que vai no corpo', async () => {
    // DESIGNER: não é admin (não troca de tenant), mas tem workflow:update, que criar o gatilho exige.
    useAuthStore.setState({ token: token(['DESIGNER'], 'tenant-a') })
    await abrirNovoGatilho()

    const campo = body().getByTestId('trigger-tenant') as HTMLInputElement
    expect(campo.value).toBe('tenant-a')
    expect(campo.readOnly).toBe(true)
    await type(campo, 'tenant-b')
    await act(async () => { fireEvent.click(body().getByTestId('trigger-save')) })

    await waitFor(() => expect(triggersMock.create).toHaveBeenCalled())
    expect(triggersMock.create.mock.calls[0][0].tenantId).toBe('tenant-a')
    expect(triggersMock.list).toHaveBeenCalledWith(undefined)
  })

  it('admin: começa no próprio tenant, pode escolher outro, e a lista pede todos', async () => {
    useAuthStore.setState({ token: token(['ADMIN'], 'tenant-a') })
    useTenantStore.setState({ currentRole: 'superadmin' })
    await abrirNovoGatilho()

    const campo = body().getByTestId('trigger-tenant') as HTMLInputElement
    expect(campo.value).toBe('tenant-a')
    expect(campo.readOnly).toBe(false)
    await type(campo, 'tenant-b')
    await act(async () => { fireEvent.click(body().getByTestId('trigger-save')) })

    await waitFor(() => expect(triggersMock.create).toHaveBeenCalled())
    expect(triggersMock.create.mock.calls[0][0].tenantId).toBe('tenant-b')
    expect(triggersMock.list).toHaveBeenCalledWith('all')
  })
})

describe('AgentPlaygroundPage — tenant da invocação (RCHFLW-103)', () => {
  it('usuário comum: invoca no tenant do acesso, sem poder trocá-lo', async () => {
    useAuthStore.setState({ token: token(['EXECUTOR'], 'tenant-a') })
    const ui = await render(() => h(AgentPlaygroundPage))

    const campo = ui.getByTestId('pg-tenant') as HTMLInputElement
    expect(campo.value).toBe('tenant-a')
    expect(campo.readOnly).toBe(true)
    await type(campo, 'tenant-b')
    await pickAgent('pg-agent')
    await act(async () => { fireEvent.click(ui.getByTestId('pg-invoke-btn')) })

    await waitFor(() => expect(playgroundMock.invoke).toHaveBeenCalled())
    expect(playgroundMock.invoke.mock.calls[0][1]).toBe('tenant-a')
  })

  it('sem login (dev): o campo continua livre, como antes', async () => {
    const ui = await render(() => h(AgentPlaygroundPage))

    const campo = ui.getByTestId('pg-tenant') as HTMLInputElement
    expect(campo.value).toBe('tenant_demo')
    expect(campo.readOnly).toBe(false)
  })
})
