/**
 * 2º bug hunt de QA — gatilhos agendados:
 * - RCHFLW-176: o clique duplo em "Disparar agora" executava o agente duas vezes;
 * - RCHFLW-140: um gatilho ativo sem agendamento no Quartz aparecia como "ativo";
 * - RCHFLW-175: o segundo clique em Salvar mandava outro POST;
 * - RCHFLW-191: resposta do agente invisível, payload que não é objeto, erro de cron cru em inglês,
 *   edição concorrente e fuso do servidor sem nome.
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
const show = vi.hoisted(() => vi.fn())
/** O que a página entrega à tabela (que desenha em canvas e não está em teste). */
const tabela = vi.hoisted(() => ({
  columns: [] as any[], records: [] as any[], acoes: [] as any[],
  onIconClick: null as null | ((nome: string, r: unknown) => void),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, o?: Record<string, unknown>) => (o?.zone ? `${k} ${o.zone}` : k),
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))
vi.mock('@mantine/notifications', () => ({ notifications: { show } }))
vi.mock('../../components/VTable', () => ({
  VxTable: (props: { columns: any[]; records: any[]; onIconClick: (nome: string, r: unknown) => void }) => {
    tabela.columns = props.columns
    tabela.records = props.records
    tabela.onIconClick = props.onIconClick
    return null
  },
  colunaApagada: (c: unknown) => c,
  colunaStatus: (c: unknown) => c,
  colunaDupla: (c: Record<string, unknown>, p: (r: any) => string, s: (r: any) => string | undefined) => ({
    ...c, fieldFormat: (r: any) => [p(r), s(r)].filter(Boolean).join(' · '),
  }),
  colunaAcoes: (acoes: any[]) => { tabela.acoes = acoes; return { field: '__acoes' } },
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
import { ApiError } from '../../services/api'
import { useAuthStore } from '../../stores/auth-store'
import { useTenantStore } from '../../stores/useTenantStore'

const gatilho = (extra: Record<string, unknown> = {}) => ({
  id: 'trg-8d91fc55', name: 'QA2 gatilhos triagem reembolso', cronExpression: '0 0 9 ? * MON-FRI',
  tenantId: 'tenant_demo', agentId: 'conversational-agent', payload: { pedido: 'reembolso' }, enabled: true,
  createdAt: '2026-11-26T01:00:00Z', updatedAt: '2026-11-26T01:00:00.123Z',
  nextFireTime: '2026-11-27T12:00:00Z', timeZone: 'America/Sao_Paulo',
  lastFiredAt: null, lastRequestId: null, lastStatus: null, lastError: null, ...extra,
})

function base64Url(value: object): string {
  return btoa(JSON.stringify(value)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_')
}

/** JWT só com o payload que a UI lê; a assinatura é falsa porque quem confere é o backend. */
function token(roles: string[], tenantId: string): string {
  const payload = { sub: 'u-teste', type: 'access', roles, tenantId }
  return `${base64Url({ alg: 'HS256', typ: 'JWT' })}.${base64Url(payload)}.assinatura`
}

/** Uma promessa que o teste resolve quando quiser — a requisição "em andamento". */
function pendente<T>() {
  let resolve!: (v: T) => void
  const promise = new Promise<T>(r => { resolve = r })
  return { promise, resolve }
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
  // ADMIN: cria, edita e dispara (workflow:update e workflow:execute).
  useAuthStore.setState({ token: token(['ADMIN'], 'tenant_demo') })
  useTenantStore.setState({ impersonating: null, currentRole: 'viewer' })
  tabela.columns = []
  tabela.records = []
  tabela.acoes = []
  triggersMock.list.mockResolvedValue([gatilho()])
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
  await waitFor(() => expect(tabela.records.length).toBeGreaterThan(0))
  return within(container)
}

const body = () => within(document.body)

async function type(el: HTMLElement, value: string) {
  await act(async () => { fireEvent.change(el, { target: { value } }) })
}

/** As ações que a linha mostra agora, pelo nome. */
const acoesDaLinha = (registro: unknown) =>
  tabela.acoes.filter(a => a.mostrar?.(registro) ?? true).map(a => a.nome)

function valorDoCard(label: string): string {
  const rotulo = within(container).getByText(label)
  return rotulo.closest('.af-stat')!.querySelectorAll('p')[1].textContent ?? ''
}

async function abrirNovo(payload = '{}') {
  const ui = await render()
  await act(async () => { fireEvent.click(ui.getByTestId('trigger-new')) })
  await waitFor(() => body().getByTestId('trigger-name'))
  await type(body().getByTestId('trigger-name'), 'Triagem')
  await type(body().getByTestId('trigger-cron'), '0 0 9 31 2 ? 2027')
  await act(async () => { fireEvent.click(body().getByTestId('trigger-agent')) })
  const option = await waitFor(() => body().getByRole('option', { name: /Conversational Agent/ }))
  await act(async () => { fireEvent.click(option) })
  await type(body().getByTestId('trigger-payload'), payload)
}

const salvar = async () => {
  await act(async () => { fireEvent.click(body().getByTestId('trigger-save')) })
}

describe('Disparar agora (RCHFLW-176)', () => {
  it('clique duplo enquanto o disparo está em andamento: uma requisição só, e o play some da linha', async () => {
    const disparo = pendente<unknown>()
    triggersMock.fireNow.mockReturnValue(disparo.promise)
    await render()
    const linha = tabela.records[0]
    expect(acoesDaLinha(linha)).toContain('fire')

    await act(async () => { tabela.onIconClick!('fire', linha) })
    await act(async () => { tabela.onIconClick!('fire', linha) })

    expect(triggersMock.fireNow).toHaveBeenCalledTimes(1)
    expect(acoesDaLinha(linha)).not.toContain('fire')
    expect(acoesDaLinha(linha)).toContain('firing')

    await act(async () => { disparo.resolve(gatilho()) })
    await waitFor(() => expect(acoesDaLinha(tabela.records[0])).toContain('fire'))
    // Terminada a requisição, outro clique é outro disparo.
    triggersMock.fireNow.mockResolvedValue(gatilho())
    await act(async () => { tabela.onIconClick!('fire', tabela.records[0]) })
    expect(triggersMock.fireNow).toHaveBeenCalledTimes(2)
  })

  it('a falha do disparo também libera o botão', async () => {
    triggersMock.fireNow.mockRejectedValueOnce(new ApiError(502, 'fila fora'))
    await render()

    await act(async () => { tabela.onIconClick!('fire', tabela.records[0]) })

    expect(acoesDaLinha(tabela.records[0])).toContain('fire')
  })
})

describe('Status: ativo é o que está agendado (RCHFLW-140)', () => {
  it('ativo sem próximo disparo aparece como "sem agendamento" e não conta como ativo', async () => {
    const semAgenda = gatilho({ id: 'trg-sem-job', nextFireTime: null })
    triggersMock.list.mockResolvedValue([gatilho(), semAgenda, gatilho({ id: 'trg-pausado', enabled: false,
      nextFireTime: null })])
    await render()

    const status = tabela.columns.find(c => c.field === 'enabled')
    expect(status.fieldFormat(tabela.records[0])).toBe('triggers.status.enabled')
    expect(status.fieldFormat(semAgenda)).toBe('triggers.status.unscheduled')
    expect(status.fieldFormat(tabela.records[2])).toBe('triggers.status.paused')
    expect(valorDoCard('triggers.stats.enabled')).toBe('1')
    expect(valorDoCard('triggers.stats.paused')).toBe('1')
  })

  it('a lista mostra o próximo disparo e o aviso nomeia o fuso do servidor', async () => {
    const ui = await render()

    expect(tabela.columns.map(c => c.field)).toContain('nextFireTime')
    expect(ui.getByText(/triggers\.infoZone America\/Sao_Paulo/)).toBeTruthy()
  })
})

describe('Salvar (RCHFLW-175, RCHFLW-191)', () => {
  it('payload que não é objeto: avisa "Payload JSON inválido" e não envia nada', async () => {
    await abrirNovo('[1,2]')
    await salvar()

    expect(triggersMock.create).not.toHaveBeenCalled()
    expect(show.mock.calls.at(-1)![0]).toMatchObject({ title: 'triggers.invalidJson' })
  })

  it('o segundo clique em Salvar enquanto o primeiro está em andamento não manda outro POST', async () => {
    const criacao = pendente<unknown>()
    triggersMock.create.mockReturnValue(criacao.promise)
    await abrirNovo()

    await salvar()
    await salvar()

    expect(triggersMock.create).toHaveBeenCalledTimes(1)
    await act(async () => { criacao.resolve(gatilho()) })
  })

  it('cron que nunca dispara: a mensagem é a traduzida, e não o texto do Quartz', async () => {
    triggersMock.create.mockRejectedValue(new ApiError(400, 'Cron expression never fires: 0 0 9 31 2 ? 2027',
      JSON.stringify({ error: 'Cron expression never fires: 0 0 9 31 2 ? 2027', code: 'TRIGGER_CRON_NEVER_FIRES' })))
    await abrirNovo()
    await salvar()

    expect(show.mock.calls.at(-1)![0]).toMatchObject({
      title: 'triggers.saveFailed', message: 'triggers.errors.TRIGGER_CRON_NEVER_FIRES',
    })
  })

  it('editar manda a versão lida no If-Match; o conflito mostra o aviso e relê a lista', async () => {
    triggersMock.update.mockRejectedValue(new ApiError(409, 'Trigger trg-8d91fc55 was changed elsewhere',
      JSON.stringify({ code: 'TRIGGER_CONFLICT', updatedAt: '2026-11-26T02:00:00Z' })))
    await render()
    await act(async () => { tabela.onIconClick!('edit', tabela.records[0]) })
    await waitFor(() => body().getByTestId('trigger-name'))
    await type(body().getByTestId('trigger-name'), 'Renomeado')
    const leiturasAntes = triggersMock.list.mock.calls.length
    await salvar()

    expect(triggersMock.update).toHaveBeenCalledWith('trg-8d91fc55',
      expect.objectContaining({ name: 'Renomeado' }), '2026-11-26T01:00:00.123Z')
    expect(show.mock.calls.at(-1)![0]).toMatchObject({ message: 'triggers.errors.TRIGGER_CONFLICT' })
    await waitFor(() => expect(triggersMock.list.mock.calls.length).toBeGreaterThan(leiturasAntes))
  })
})

describe('Ver resposta (RCHFLW-191)', () => {
  it('abre a última execução e mostra a resposta do agente e o requestId', async () => {
    const disparado = gatilho({ lastRequestId: '249a5087-935a-4054-b77e-650ac2291c77', lastStatus: 'COMPLETED' })
    triggersMock.list.mockResolvedValue([disparado])
    playgroundMock.invocation.mockResolvedValue({
      requestId: disparado.lastRequestId, tenantId: 'tenant_demo', agentId: 'conversational-agent',
      status: 'COMPLETED', output: { resposta: 'Reembolso aprovado para o pedido 123' }, attempts: 1,
    })
    await render()
    expect(acoesDaLinha(tabela.records[0])).toContain('result')

    await act(async () => { tabela.onIconClick!('result', tabela.records[0]) })

    await waitFor(() => body().getByTestId('trigger-run-output'))
    expect(playgroundMock.invocation).toHaveBeenCalledWith(disparado.lastRequestId, 'tenant_demo')
    expect(body().getByTestId('trigger-run-request').textContent).toContain(disparado.lastRequestId)
    expect(body().getByTestId('trigger-run-output').textContent).toContain('Reembolso aprovado para o pedido 123')
  })

  it('disparo que não enfileirou: a linha relê a falha e não oferece a resposta do disparo anterior', async () => {
    const anterior = gatilho({ lastRequestId: '249a5087-935a-4054-b77e-650ac2291c77', lastStatus: 'COMPLETED' })
    // O servidor grava a falha do disparo sem requestId: este disparo não criou invocação.
    const comFalha = gatilho({ lastRequestId: null, lastStatus: 'FAILED', lastError: 'fila fora' })
    triggersMock.list.mockResolvedValueOnce([anterior]).mockResolvedValue([comFalha])
    triggersMock.fireNow.mockRejectedValueOnce(new ApiError(500, 'fila fora'))
    await render()
    expect(acoesDaLinha(tabela.records[0])).toContain('result')

    await act(async () => { tabela.onIconClick!('fire', tabela.records[0]) })

    await waitFor(() => expect(tabela.records[0].lastStatus).toBe('FAILED'))
    expect(acoesDaLinha(tabela.records[0])).not.toContain('result')
  })

  it('gatilho que nunca disparou não oferece "Ver resposta"', async () => {
    await render()

    expect(acoesDaLinha(tabela.records[0])).not.toContain('result')
  })
})
