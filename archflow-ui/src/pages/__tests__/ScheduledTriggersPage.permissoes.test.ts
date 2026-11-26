/**
 * QA2 RCHFLW-158: os gatilhos só oferecem o que o servidor aceitaria
 * (`WorkflowPermissionInterceptor`): criar, editar e apagar o agendamento exigem `workflow:update`;
 * "Disparar agora" executa o fluxo e exige `workflow:execute`. Antes um VIEWER via e clicava tudo, e cada
 * clique voltava 403.
 *
 * <p>A tabela é a real: no jsdom ela desenha só o espelho acessível, em que cada ação visível é um botão.</p>
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { entrarComo } from '../../lib/__tests__/papeisDeTeste'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'

const triggersMock = vi.hoisted(() => ({
  list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn(), fireNow: vi.fn(),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn() } }))
vi.mock('../../components/PropertyPanel/useCatalog', () => ({
  useCatalog: () => ({
    agents: [], assistants: [], tools: [], embeddings: [], memories: [], vectorstores: [], chains: [],
    loading: false, usingFallback: false,
  }),
}))
vi.mock('../../services/triggers-api', () => ({ triggersApi: triggersMock }))

import ScheduledTriggersPage from '../ScheduledTriggersPage'

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
  triggersMock.list.mockResolvedValue([{
    id: 'trg-1', name: 'Diário', cronExpression: '0 0 2 * * ?', tenantId: '__default__',
    agentId: 'conversational-agent', payload: {}, enabled: true, createdAt: '2026-11-24T10:00:00Z',
  }])
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
  await waitFor(() => ui.getByTestId('trigger-trg-1'))
  return ui
}

function acoes(ui: ReturnType<typeof within>) {
  const linha = within(ui.getByTestId('trigger-trg-1'))
  const novo = ui.getByTestId('trigger-new') as HTMLButtonElement
  return {
    novo: !novo.disabled,
    disparar: linha.queryByRole('button', { name: 'triggers.fireNow' }) !== null,
    editar: linha.queryByRole('button', { name: 'triggers.edit' }) !== null,
    apagar: linha.queryByRole('button', { name: 'triggers.delete' }) !== null,
  }
}

describe('ScheduledTriggersPage — ações por papel (QA2 RCHFLW-158)', () => {
  it('VIEWER: só lê — Novo desabilitado (workflow:update), sem disparar, editar nem apagar', async () => {
    entrarComo('VIEWER')
    const ui = await render()

    expect(acoes(ui)).toEqual({ novo: false, disparar: false, editar: false, apagar: false })
    expect(ui.getByTestId('trigger-new').closest('[data-permission-missing]')
      ?.getAttribute('data-permission-missing')).toBe('workflow:update')
    // Clicar na linha também abria a edição.
    await act(async () => { fireEvent.click(ui.getByTestId('trigger-trg-1')) })
    expect(within(document.body).queryByTestId('trigger-save')).toBeNull()
  })

  it('EXECUTOR: dispara (workflow:execute), mas não cria, edita nem apaga', async () => {
    entrarComo('EXECUTOR')
    const ui = await render()
    expect(acoes(ui)).toEqual({ novo: false, disparar: true, editar: false, apagar: false })
  })

  it('DESIGNER e ADMIN: todas as ações', async () => {
    for (const papel of ['DESIGNER', 'ADMIN']) {
      entrarComo(papel)
      const ui = await render()
      expect(acoes(ui)).toEqual({ novo: true, disparar: true, editar: true, apagar: true })
      await act(async () => { root?.unmount() })
      container.remove()
    }
    root = null
  })
})
