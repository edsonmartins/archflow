/**
 * QA2 RCHFLW-137 (parte da UI): `/api/admin/linktor/**` e `/api/admin/brainsentry/**` exigem o papel ADMIN
 * no servidor (`AdminSecurityConfiguration.ADMIN_PATHS`) — a configuração grava o comando que o servidor
 * executa e o destino da chave; a inbox fala com o Linktor usando essa chave. As escritas do catálogo de
 * extensões (`ADMIN_WRITE_PATHS`) também. Não há permissão para isso, é o papel: quem não é ADMIN vê as
 * ações desabilitadas com o aviso "Requer o papel ADMIN", ou não as vê (remover extensão).
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { entrarComo } from '../../lib/__tests__/papeisDeTeste'
import { createElement as h, act, type ReactElement } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor } from '@testing-library/dom'

const linktorMock = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn() }))
const brainsentryMock = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn() }))
const marketplaceMock = vi.hoisted(() => ({
  list: vi.fn(), get: vi.fn(), search: vi.fn(), install: vi.fn(), uninstall: vi.fn(),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn() } }))
vi.mock('../../services/linktor-api', () => ({ linktorApi: linktorMock }))
vi.mock('../../services/brainsentry-api', () => ({ brainsentryApi: brainsentryMock }))
vi.mock('../../services/marketplace-api', () => ({ marketplaceApi: marketplaceMock }))

import LinktorConfigPage from '../LinktorConfigPage'
import LinktorConversationPage from '../LinktorConversationPage'
import BrainSentryConfigPage from '../BrainSentryConfigPage'
import MarketplacePage from '../MarketplacePage'
import MarketplaceDetailPage from '../MarketplaceDetailPage'

const EXTENSOES = [
  { id: 'ext-a', name: 'a', version: '1', displayName: 'A', author: 'x', description: '', type: 'tool',
    permissions: [], installed: true },
  { id: 'ext-b', name: 'b', version: '1', displayName: 'B', author: 'x', description: '', type: 'tool',
    permissions: [], installed: false },
]

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
  if (!(document as any).fonts) {
    Object.defineProperty(document, 'fonts', {
      value: { addEventListener() {}, removeEventListener() {} }, configurable: true,
    })
  }
  sessionStorage.clear()
  linktorMock.get.mockResolvedValue({
    enabled: true, apiBaseUrl: 'http://linktor:8081/api/v1', apiKey: 'abcd…wxyz', accessToken: '',
    mcpCommand: 'node /opt/linktor/index.js', timeoutSeconds: 30,
  })
  brainsentryMock.get.mockResolvedValue({
    enabled: false, baseUrl: 'http://bs', apiKey: '', tenantId: 't', maxTokenBudget: 1000,
    deepAnalysisEnabled: false, timeoutSeconds: 10,
  })
  marketplaceMock.list.mockResolvedValue(EXTENSOES)
  marketplaceMock.get.mockResolvedValue(EXTENSOES[0])
  // A inbox do Linktor vem por fetch (linktor-inbox-api): conversa, mensagens e operadores.
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    const json = (corpo: unknown) => new Response(JSON.stringify(corpo), { status: 200 })
    if (url.includes('/conversations/conv-1/messages')) {
      return json([{ id: 'm1', sender_type: 'contact', content: 'Oi', created_at: '2026-11-25T09:00:00Z' }])
    }
    if (url.endsWith('/conversations/conv-1')) {
      return json({ id: 'conv-1', status: 'open', unread_count: 0, updated_at: '2026-11-25T09:01:00Z',
        contact: { id: 'c-1', name: 'Maria' }, channel: { id: 'ch-1', type: 'webchat', name: 'Site' } })
    }
    if (url.includes('/users')) return json([])
    return new Response('', { status: 404 })
  }))
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
  vi.unstubAllGlobals()
})

async function render(page: ReactElement, path = '/', rota = '/') {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, { env: 'test' },
      h(MemoryRouter, { initialEntries: [path] }, h(Routes, null, h(Route, { path: rota, element: page })))))
  })
}

function el(testId: string): HTMLButtonElement | null {
  return container.querySelector(`[data-testid="${testId}"]`) as HTMLButtonElement | null
}

async function botao(testId: string): Promise<HTMLButtonElement> {
  return waitFor(() => {
    const b = el(testId)
    expect(b, testId).not.toBeNull()
    return b!
  })
}

function falta(b: HTMLElement): string | null {
  return b.closest('[data-permission-missing]')?.getAttribute('data-permission-missing') ?? null
}

describe('Linktor e Brain Sentry — gravar exige o papel ADMIN (QA2 RCHFLW-137)', () => {
  it('DESIGNER: Salvar do Linktor desabilitado, com o aviso do papel', async () => {
    entrarComo('DESIGNER')
    await render(h(LinktorConfigPage))
    const salvar = await botao('linktor-save')

    expect(salvar.disabled).toBe(true)
    expect(falta(salvar)).toBe('ADMIN')
    await act(async () => { fireEvent.click(salvar) })
    expect(linktorMock.update).not.toHaveBeenCalled()
  })

  it('ADMIN: Salvar do Linktor habilitado', async () => {
    entrarComo('ADMIN')
    await render(h(LinktorConfigPage))
    const salvar = await botao('linktor-save')
    expect(salvar.disabled).toBe(false)
    expect(falta(salvar)).toBeNull()
  })

  it('DESIGNER: na conversa do Linktor, resolver e transferir ficam desabilitados com o aviso', async () => {
    entrarComo('DESIGNER')
    await render(h(LinktorConversationPage), '/admin/linktor/inbox/conv-1', '/admin/linktor/inbox/:id')

    for (const id of ['lk-resolve', 'lk-assign', 'lk-reply-send']) {
      const b = await botao(id)
      expect(b.disabled, id).toBe(true)
      expect(falta(b), id).toBe('ADMIN')
    }
  })

  it('ADMIN: na conversa do Linktor, resolver fica habilitado', async () => {
    entrarComo('ADMIN')
    await render(h(LinktorConversationPage), '/admin/linktor/inbox/conv-1', '/admin/linktor/inbox/:id')

    const resolver = await botao('lk-resolve')
    await waitFor(() => expect(resolver.disabled).toBe(false))
    expect(falta(resolver)).toBeNull()
  })

  it('Brain Sentry: DESIGNER não salva; ADMIN salva', async () => {
    entrarComo('DESIGNER')
    await render(h(BrainSentryConfigPage))
    let salvar = await botao('bs-save')
    expect(salvar.disabled).toBe(true)
    expect(falta(salvar)).toBe('ADMIN')
    await act(async () => { root?.unmount() })
    container.remove()

    entrarComo('ADMIN')
    await render(h(BrainSentryConfigPage))
    salvar = await botao('bs-save')
    expect(salvar.disabled).toBe(false)
  })
})

describe('Extensões — instalar e remover exigem o papel ADMIN (QA2 RCHFLW-137)', () => {
  it('DESIGNER: Instalar desabilitado com o aviso; sem instalar/remover por extensão', async () => {
    entrarComo('DESIGNER')
    await render(h(MarketplacePage))
    await waitFor(() => expect(el('extension-ext-a')).not.toBeNull())

    const instalar = el('marketplace-install-btn')!
    expect(instalar.disabled).toBe(true)
    expect(falta(instalar)).toBe('ADMIN')
    expect(el('uninstall-ext-a')).toBeNull()
    expect(el('install-ext-b')).toBeNull()
  })

  it('ADMIN: Instalar habilitado e as ações por extensão aparecem', async () => {
    entrarComo('ADMIN')
    await render(h(MarketplacePage))
    await waitFor(() => expect(el('extension-ext-a')).not.toBeNull())

    expect(el('marketplace-install-btn')!.disabled).toBe(false)
    expect(el('uninstall-ext-a')).not.toBeNull()
    expect(el('install-ext-b')).not.toBeNull()
  })

  it('detalhe: remover só aparece para o ADMIN', async () => {
    entrarComo('DESIGNER')
    await render(h(MarketplaceDetailPage), '/marketplace/ext-a', '/marketplace/:id')
    await waitFor(() => expect(el('marketplace-detail')).not.toBeNull())
    expect(el('uninstall-detail')).toBeNull()
    await act(async () => { root?.unmount() })
    container.remove()

    entrarComo('ADMIN')
    await render(h(MarketplaceDetailPage), '/marketplace/ext-a', '/marketplace/:id')
    await waitFor(() => expect(el('uninstall-detail')).not.toBeNull())
  })
})
