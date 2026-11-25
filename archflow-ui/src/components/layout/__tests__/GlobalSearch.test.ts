/**
 * RCHFLW-91 — o "Buscar em tudo" (⌘K) só listava as páginas do menu e os 30 primeiros fluxos: o id de uma
 * execução, o nome de uma conversa ou de um modelo davam "Nada encontrado", e o 31º fluxo nunca aparecia.
 * Aqui o Spotlight real do Mantine roda com os endpoints de lista simulados e a busca é digitada no campo.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { spotlight } from '@mantine/spotlight'
import { MemoryRouter } from 'react-router-dom'
import { fireEvent, waitFor } from '@testing-library/dom'
import ptBR from '../../../i18n/locales/pt-BR.json'
import en from '../../../i18n/locales/en.json'

const apis = vi.hoisted(() => ({
  workflows: vi.fn(),
  executions: vi.fn(),
  conversations: vi.fn(),
  templates: vi.fn(),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('../../../services/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../services/api')>()
  return {
    ...actual,
    workflowApi: { ...actual.workflowApi, list: apis.workflows },
    executionApi: { ...actual.executionApi, list: apis.executions },
  }
})
vi.mock('../../../services/conversation-api', () => ({
  conversationApi: { listConversations: apis.conversations },
}))
vi.mock('../../../services/template-api', () => ({
  templateApi: { list: apis.templates },
}))

import GlobalSearch from '../GlobalSearch'
import { useWorkflowStore } from '../../../stores/workflow-store'
import { useTenantStore, type TenantInfo } from '../../../stores/useTenantStore'

let root: Root | null = null
let container: HTMLDivElement

const flows = Array.from({ length: 40 }, (_, i) => ({
  id: `wf-${i + 1}`, name: `Fluxo número ${i + 1}`, description: '', version: '1', status: 'draft',
  updatedAt: '2026-11-24T10:00:00Z', stepCount: 2,
}))

const execution = {
  id: 'exec-afbcbc5d', workflowId: 'wf-7', workflowName: 'Fluxo número 7', status: 'COMPLETED',
  startedAt: '2026-11-24T10:00:00Z', completedAt: null, duration: null, error: null,
}

const conversation = {
  id: 'conv-123', title: 'Atendimento da Maria', status: 'ACTIVE', lastMessage: 'quero trocar o pedido',
  createdAt: '2026-11-24T10:00:00Z', updatedAt: '2026-11-24T10:00:00Z',
}

const template = {
  id: 'tpl-sac', name: 'SAC simples', icon: 'x', summary: 'Atende clientes', description: '',
  category: 'customer_support', tags: ['suporte'], complexity: 'starter', steps: [], connections: [],
}

beforeEach(() => {
  vi.clearAllMocks()
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  ;(globalThis as any).ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} }
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  Element.prototype.scrollIntoView ??= function scrollIntoView() {}
  useTenantStore.setState({ impersonating: null })
  useWorkflowStore.setState({ workflows: [] })
  apis.workflows.mockResolvedValue(flows)
  apis.executions.mockResolvedValue([execution])
  apis.templates.mockResolvedValue([template])
  apis.conversations.mockImplementation(async ({ search }: { search: string }) => ({
    items: 'conv-123 atendimento da maria quero trocar o pedido'.includes(search.toLowerCase())
      ? [conversation] : [],
    page: 0, pageSize: 100, total: 1,
  }))
})

afterEach(async () => {
  await act(async () => { spotlight.close() })
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

const pageActions = [{
  group: 'nav.groups.run',
  actions: [{ id: 'nav-/executions', label: 'nav.executions', onClick: () => {} }],
}]

async function openSearch() {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, null, h(MemoryRouter, null, h(GlobalSearch, { pageActions }))))
  })
  await waitFor(() => expect(useWorkflowStore.getState().workflows).toHaveLength(40))
  await act(async () => { spotlight.open() })
  return waitFor(() => {
    const input = document.body.querySelector<HTMLInputElement>('input[placeholder="nav.searchPlaceholder"]')
    expect(input).not.toBeNull()
    return input!
  })
}

async function reopen() {
  await act(async () => { spotlight.open() })
  return waitFor(() => {
    const input = document.body.querySelector<HTMLInputElement>('input[placeholder="nav.searchPlaceholder"]')
    expect(input).not.toBeNull()
    return input!
  })
}

async function switchTenant(id: string) {
  await act(async () => { spotlight.close() })
  await act(async () => { useTenantStore.setState({ impersonating: { id, name: id } as unknown as TenantInfo }) })
}

async function type(input: HTMLInputElement, value: string) {
  await act(async () => { fireEvent.change(input, { target: { value } }) })
}

/** Rótulos das ações visíveis (o texto do botão da ação, sem a descrição). */
function actionLabels(): string[] {
  return [...document.body.querySelectorAll('.mantine-Spotlight-action')]
    .map((el) => el.querySelector('.mantine-Spotlight-actionLabel')?.textContent ?? '')
}

/** Rótulos dos grupos visíveis — o Mantine os desenha via a variável CSS --spotlight-label. */
function groupLabels(): string[] {
  return [...document.body.querySelectorAll<HTMLElement>('.mantine-Spotlight-actionsGroup')]
    .map((el) => el.style.getPropertyValue('--spotlight-label').replace(/^'|'$/g, ''))
}

describe('GlobalSearch — Buscar em tudo', () => {
  it('acha um fluxo além do 30º (sem o corte antigo)', async () => {
    const input = await openSearch()
    await type(input, 'número 35')

    await waitFor(() => expect(actionLabels()).toEqual(['Fluxo número 35']))
    expect(groupLabels()).toEqual(['nav.workflows'])
  })

  it('acha uma execução pelo id, rotulada no grupo Execuções', async () => {
    const input = await openSearch()
    await type(input, 'exec-afbc')

    await waitFor(() => expect(actionLabels()).toContain('exec-afbcbc5d'))
    expect(groupLabels()).toContain('nav.executions')
    expect(apis.executions).toHaveBeenCalledWith()
  })

  it('acha uma conversa pela busca do servidor, mesmo casando só pela última mensagem', async () => {
    const input = await openSearch()
    await type(input, 'trocar o pedido')

    await waitFor(() => expect(actionLabels()).toEqual(['Atendimento da Maria']))
    expect(groupLabels()).toEqual(['nav.conversations'])
    expect(apis.conversations).toHaveBeenLastCalledWith({ search: 'trocar o pedido', pageSize: 100 })
  })

  it('acha um modelo pelo nome, rotulado no grupo Modelos', async () => {
    const input = await openSearch()
    await type(input, 'SAC')

    await waitFor(() => expect(actionLabels()).toContain('SAC simples'))
    expect(groupLabels()).toContain('nav.templates')
  })

  it('se o endpoint de execuções falha, o grupo some e o resto da busca continua', async () => {
    apis.executions.mockRejectedValue(new Error('500'))
    const input = await openSearch()
    // "e" casa com tudo: o fluxo, a conversa, o modelo — e casaria com a execução, se ela tivesse vindo.
    await type(input, 'e')

    await waitFor(() => expect(actionLabels()).toContain('Atendimento da Maria'))
    expect(actionLabels()).toContain('SAC simples')
    expect(actionLabels()).toContain('Fluxo número 1')
    expect(groupLabels()).not.toContain('nav.executions')
  })

  it('se a busca de conversas falha, os outros grupos seguem aparecendo', async () => {
    apis.conversations.mockRejectedValue(new Error('500'))
    const input = await openSearch()
    await type(input, 'exec-afbc')

    await waitFor(() => expect(actionLabels()).toContain('exec-afbcbc5d'))
    await waitFor(() => expect(apis.conversations).toHaveBeenCalled())
    expect(groupLabels()).not.toContain('nav.conversations')
  })

  it('o placeholder diz o que é pesquisado, nos dois idiomas', () => {
    const pt = ptBR.nav.searchPlaceholder.toLowerCase()
    for (const word of ['páginas', 'fluxos', 'execuções', 'conversas', 'modelos']) expect(pt).toContain(word)
    const english = en.nav.searchPlaceholder.toLowerCase()
    for (const word of ['pages', 'workflows', 'executions', 'conversations', 'templates']) {
      expect(english).toContain(word)
    }
  })

  it('ao trocar de tenant, as execuções do anterior não aparecem enquanto as do novo não chegam', async () => {
    const input = await openSearch()
    await type(input, 'exec-afbc')
    await waitFor(() => expect(actionLabels()).toContain('exec-afbcbc5d'))
    await type(input, '')

    await switchTenant('tenant-b')
    apis.executions.mockReturnValue(new Promise(() => { /* GET /executions do tenant B ainda no ar */ }))
    const calls = apis.conversations.mock.calls.length
    const again = await reopen()
    await type(again, 'exec-afbc')

    // A busca de conversas (com espera de digitação) já respondeu: a tela está assentada.
    await waitFor(() => expect(apis.conversations.mock.calls.length).toBeGreaterThan(calls))
    expect(actionLabels()).not.toContain('exec-afbcbc5d')
    expect(groupLabels()).not.toContain('nav.executions')
  })

  it('uma resposta pedida no tenant anterior que chega depois da troca é descartada', async () => {
    let resolveOld!: (v: unknown) => void
    apis.executions.mockReturnValue(new Promise((r) => { resolveOld = r }))
    await openSearch()

    await switchTenant('tenant-b')
    apis.executions.mockReturnValue(new Promise(() => { /* B ainda no ar */ }))
    await act(async () => { resolveOld([execution]) })
    const again = await reopen()
    await type(again, 'exec-afbc')

    await waitFor(() => expect(apis.conversations).toHaveBeenCalled())
    expect(actionLabels()).not.toContain('exec-afbcbc5d')
  })
})
