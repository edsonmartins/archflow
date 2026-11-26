/**
 * RCHFLW-199: com a API fora do ar, o Início dizia "Falhas recentes 0 — Nenhuma falha, tudo certo" e "Nenhuma
 * execução ainda": a falha de carregamento virava "vazio", e os fluxos de uma carga anterior continuavam na tela.
 * Agora o erro do store (o mesmo que a lista de fluxos mostra) avisa e troca os números por "—".
 *
 * <p>A tabela (VTable, desenhada em canvas) é simulada; a página e o store são os reais.</p>
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { waitFor, within } from '@testing-library/dom'
import { MemoryDataRouter } from '../../lib/__tests__/roteadorDeTeste'

const workflowApiMock = vi.hoisted(() => ({ list: vi.fn() }))
const executionApiMock = vi.hoisted(() => ({ list: vi.fn() }))
const approvalApiMock = vi.hoisted(() => ({ pendingCount: vi.fn() }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('../../services/api', () => ({
  workflowApi: workflowApiMock,
  executionApi: executionApiMock,
  ApiError: class extends Error {},
}))
vi.mock('../../services/approval-api', () => ({ approvalApi: approvalApiMock }))
vi.mock('../../components/VTable', async () => {
  const { createElement } = await import('react')
  return {
    VxTable: ({ records, emptyMessage, emptyAction }:
      { records: Array<{ id: string }>; emptyMessage: string; emptyAction?: unknown }) =>
      createElement('div', { 'data-testid': 'tabela' },
        records.length === 0
          ? [createElement('span', { key: 'vazio', 'data-testid': 'vazio' }, emptyMessage),
             emptyAction ? createElement('span', { key: 'cta', 'data-testid': 'cta' }, 'cta') : null]
          : records.map((r) => createElement('span', { key: r.id, 'data-testid': `linha-${r.id}` }, r.id))),
    colunaApagada: (c: unknown) => c,
    colunaStatus: (c: unknown) => c,
  }
})

import DashboardPage from '../DashboardPage'
import { useWorkflowStore } from '../../stores/workflow-store'

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
  // O que sobrou de uma carga anterior, como na tela do relato (351 fluxos com a API já fora).
  useWorkflowStore.setState({
    workflows: [{ id: 'wf-velho', name: 'Velho', publishedVersion: 1 } as any],
    executions: [{ id: 'exec-velha', workflowName: 'Velho', status: 'COMPLETED' } as any],
    loading: false, error: null,
  })
  approvalApiMock.pendingCount.mockRejectedValue(new TypeError('Failed to fetch'))
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
    root!.render(h(MantineProvider, null, h(MemoryDataRouter, { initialEntries: ['/'] }, h(DashboardPage))))
  })
  return within(container)
}

describe('Início com a API fora do ar (RCHFLW-199)', () => {
  it('avisa, troca os números por "—" e não diz "tudo certo" nem "nenhuma execução"', async () => {
    workflowApiMock.list.mockRejectedValue(new TypeError('Failed to fetch'))
    executionApiMock.list.mockRejectedValue(new TypeError('Failed to fetch'))

    const ui = await render()

    await waitFor(() => expect(ui.getByTestId('dashboard-load-failed')).toBeTruthy())
    expect(ui.getByTestId('dashboard-load-failed').textContent).toContain('dashboard.loadFailed')
    expect(container.textContent).not.toContain('dashboard.hints.failedNone')
    expect(container.textContent).not.toContain('executions.empty')
    expect(ui.queryByTestId('linha-exec-velha')).toBeNull()
    expect(ui.getByTestId('vazio').textContent).toBe('dashboard.hints.unavailable')
    expect(ui.queryByTestId('cta')).toBeNull()
  })

  it('com a API respondendo, segue como antes: números reais e o vazio com o atalho', async () => {
    workflowApiMock.list.mockResolvedValue([])
    executionApiMock.list.mockResolvedValue([])
    approvalApiMock.pendingCount.mockResolvedValue({ count: 0 })

    const ui = await render()

    await waitFor(() => expect(ui.getByTestId('vazio').textContent).toBe('executions.empty'))
    expect(ui.queryByTestId('dashboard-load-failed')).toBeNull()
    expect(container.textContent).toContain('dashboard.hints.failedNone')
    expect(ui.getByTestId('cta')).toBeTruthy()
  })
})
