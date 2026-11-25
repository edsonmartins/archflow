/**
 * RCHFLW-104: o gatilho cujo último disparo falhou aparece como falha — no card "Com falha" e na linha.
 * Na homologação o agente falhou nas duas vezes (manual e cron) e o card seguiu em 0; a linha não dizia
 * nada. O servidor agora grava lastStatus/lastError; aqui se afirma o que a tela faz com eles.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { waitFor, within } from '@testing-library/dom'

const triggersMock = vi.hoisted(() => ({
  list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn(), fireNow: vi.fn(),
}))
const playgroundMock = vi.hoisted(() => ({ invoke: vi.fn(), message: vi.fn(), invocation: vi.fn() }))
/** As colunas e linhas que a página entrega à tabela (que desenha em canvas e não está em teste). */
const tabela = vi.hoisted(() => ({
  columns: [] as any[], records: [] as any[], onIconClick: null as null | ((nome: string, r: unknown) => void),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn() } }))
vi.mock('../../components/VTable', () => ({
  VxTable: (props: { columns: any[]; records: any[]; onIconClick: (nome: string, r: unknown) => void }) => {
    tabela.columns = props.columns
    tabela.records = props.records
    tabela.onIconClick = props.onIconClick
    return null
  },
  colunaApagada: (c: unknown) => c,
  colunaStatus: (c: unknown) => c,
  // Como o helper real: o texto da célula é principal · secundária.
  colunaDupla: (c: Record<string, unknown>, p: (r: any) => string, s: (r: any) => string | undefined) => ({
    ...c, fieldFormat: (r: any) => [p(r), s(r)].filter(Boolean).join(' · '),
  }),
  colunaAcoes: () => ({}),
}))
vi.mock('../../components/PropertyPanel/useCatalog', () => ({
  useCatalog: () => ({
    agents: [], assistants: [], tools: [], embeddings: [], memories: [], vectorstores: [], chains: [],
    loading: false, usingFallback: false,
  }),
}))
vi.mock('../../services/triggers-api', () => ({ triggersApi: triggersMock }))
vi.mock('../../services/agent-playground-api', async importOriginal => {
  const real = await importOriginal<typeof import('../../services/agent-playground-api')>()
  return { ...real, agentPlaygroundApi: playgroundMock }
})

import ScheduledTriggersPage from '../ScheduledTriggersPage'
import { useAuthStore } from '../../stores/auth-store'
import { useTenantStore } from '../../stores/useTenantStore'

const gatilho = (extra: Record<string, unknown>) => ({
  id: 'trg-aa0785bb', name: 'Noturno', cronExpression: '0 * * * * ?', tenantId: 'tenant_demo',
  agentId: 'mcp-agent', payload: {}, enabled: true, createdAt: '2026-11-25T12:00:00Z',
  lastFiredAt: '2026-11-25T12:18:00Z', lastRequestId: 'req-2', ...extra,
})

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
  useAuthStore.setState({ token: null })
  useTenantStore.setState({ impersonating: null, currentRole: 'viewer' })
  tabela.columns = []
  tabela.records = []
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
    root!.render(h(MantineProvider, { env: 'test' }, h(ScheduledTriggersPage)))
  })
  const ui = within(container)
  await waitFor(() => expect(tabela.records.length).toBeGreaterThan(0))
  return ui
}

/** O valor do StatCard cujo rótulo é `label`. */
function valorDoCard(label: string): string {
  const rotulo = within(container).getByText(label)
  return rotulo.closest('.af-stat')!.querySelectorAll('p')[1].textContent ?? ''
}

const celulaDoUltimoResultado = (registro: unknown) => {
  const coluna = tabela.columns.find(c => c.field === 'lastStatus')
  expect(coluna, 'coluna "Último resultado" ausente').toBeDefined()
  return coluna.fieldFormat(registro)
}

describe('ScheduledTriggersPage — falha do último disparo (RCHFLW-104)', () => {
  it('disparo automático que falhou: o card "Com falha" conta e a linha mostra o erro', async () => {
    const falho = gatilho({ lastStatus: 'FAILED', lastError: "mcp-agent: 'systemPrompt' é obrigatório" })
    triggersMock.list.mockResolvedValue([falho, gatilho({ id: 'trg-ok', lastStatus: 'COMPLETED' })])
    await render()

    expect(valorDoCard('triggers.stats.failing')).toBe('1')
    expect(celulaDoUltimoResultado(falho))
      .toBe("triggers.lastStatus.FAILED · mcp-agent: 'systemPrompt' é obrigatório")
    expect(celulaDoUltimoResultado(tabela.records[1])).toBe('triggers.lastStatus.COMPLETED')
  })

  it('gatilho que nunca disparou não tem resultado nem conta como falha', async () => {
    const novo = gatilho({ lastFiredAt: null, lastRequestId: null, lastStatus: null, lastError: null })
    triggersMock.list.mockResolvedValue([novo])
    await render()

    expect(valorDoCard('triggers.stats.failing')).toBe('0')
    expect(celulaDoUltimoResultado(novo)).toBe('—')
  })

  it('"Disparar agora": terminado o agente, a lista é relida e o card passa a mostrar a falha', async () => {
    triggersMock.list.mockResolvedValueOnce([gatilho({ lastStatus: 'COMPLETED' })])
    triggersMock.fireNow.mockResolvedValue(gatilho({ lastRequestId: 'req-3', lastStatus: 'QUEUED' }))
    triggersMock.list.mockResolvedValueOnce([gatilho({ lastRequestId: 'req-3', lastStatus: 'QUEUED' })])
    triggersMock.list.mockResolvedValue([gatilho({ lastRequestId: 'req-3', lastStatus: 'FAILED', lastError: 'boom' })])
    playgroundMock.invocation.mockResolvedValue({
      requestId: 'req-3', tenantId: 'tenant_demo', agentId: 'mcp-agent', status: 'FAILED', error: 'boom',
      attempts: 1,
    })
    await render()
    expect(valorDoCard('triggers.stats.failing')).toBe('0')

    // O mesmo caminho do ícone "Disparar agora" da linha.
    await act(async () => { tabela.onIconClick!('fire', tabela.records[0]) })

    await waitFor(() => expect(valorDoCard('triggers.stats.failing')).toBe('1'))
    expect(triggersMock.list).toHaveBeenCalledTimes(3)
  })
})
