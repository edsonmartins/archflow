/**
 * Visão geral do workspace com números medidos (RCHFLW-75): sem cadastro do tenant não há plano nem
 * limite — a tela diz isso, em vez das barras contra 5M tokens fixos no código; com cadastro, as barras
 * usam os limites do plano real. Custo nulo de consumo medido aparece como "sem preço"; sem medição, zero.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'

const workspaceApiMock = vi.hoisted(() => ({ summary: vi.fn() }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, o?: Record<string, unknown>) => (o && 'limit' in o ? `${k}:${String(o.limit)}` : k),
    i18n: { language: 'en', resolvedLanguage: 'en' },
  }),
}))
vi.mock('../../../../services/admin-api', () => ({ workspaceApi: workspaceApiMock }))

import WorkspaceOverview from '../WorkspaceOverview'

const EMPTY_USAGE = {
  executions: 0, tokensInput: 0, tokensOutput: 0, costCents: null, costReais: null, declaredCostUsd: null,
  executionsWithoutPrice: 0,
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
    root!.render(h(MantineProvider, null, h(WorkspaceOverview)))
  })
}

const byTestId = (id: string) => document.body.querySelector(`[data-testid="${id}"]`) as HTMLElement | null

describe('WorkspaceOverview — uso medido', () => {
  it('sem cadastro do tenant: zeros medidos, sem plano, sem barras e sem limite inventado', async () => {
    workspaceApiMock.summary.mockResolvedValue({
      tenantId: 'acme', tenantName: 'acme', plan: null, status: null, executionsToday: 0, tokensThisMonth: 0,
      workflowCount: 0, userCount: 1, apiKeyCount: 0, usageThisMonth: EMPTY_USAGE, limits: null,
    })
    await render()

    const text = document.body.textContent ?? ''
    expect(byTestId('workspace-no-limits')).not.toBeNull()
    expect(byTestId('workspace-plan')).toBeNull()
    expect(text).not.toContain('4.2M')
    expect(text).not.toContain('5,000,000')
    expect(text).toContain('admin.tenant.workspace.hints.noLimit')
    // Nada medido: custo zero, como os tokens. "Sem preço" diria que houve consumo sem preço.
    expect(text).not.toContain('usage.noPrice')
    expect(text).toContain('R$0.00')
    expect(document.body.querySelector('.mantine-Progress-root')).toBeNull()
  })

  it('consumo medido sem preço declarado: o custo do mês é "sem preço"', async () => {
    workspaceApiMock.summary.mockResolvedValue({
      tenantId: 'acme', tenantName: 'acme', plan: null, status: null, executionsToday: 1, tokensThisMonth: 900,
      workflowCount: 1, userCount: 1, apiKeyCount: 0,
      usageThisMonth: { ...EMPTY_USAGE, executions: 1, tokensInput: 700, tokensOutput: 200, executionsWithoutPrice: 1 },
      limits: null,
    })
    await render()

    const text = document.body.textContent ?? ''
    expect(text).toContain('usage.noPrice')
    expect(text).not.toContain('R$0.00')
  })

  it('com cadastro: plano e barras contra os limites do plano real, tokens e custo medidos', async () => {
    workspaceApiMock.summary.mockResolvedValue({
      tenantId: 'acme', tenantName: 'Acme', plan: 'enterprise', status: 'active', executionsToday: 3,
      tokensThisMonth: 1_550, workflowCount: 2, userCount: 2, apiKeyCount: 1,
      usageThisMonth: { ...EMPTY_USAGE, executions: 1, tokensInput: 1_300, tokensOutput: 250, costCents: 1,
        costReais: 0.0018 },
      limits: { executionsPerDay: 50, tokensPerMonth: 2_000_000, maxWorkflows: 7, maxUsers: 4, allowedModels: [] },
    })
    await render()

    const text = document.body.textContent ?? ''
    expect(byTestId('workspace-plan')!.textContent).toContain('admin.superadmin.tenants.plans.enterprise')
    expect(byTestId('workspace-no-limits')).toBeNull()
    expect(document.body.querySelectorAll('.mantine-Progress-root')).toHaveLength(4)
    expect(text).toContain('2K')
    expect(text).toContain('2,000,000')
    expect(text).toContain('R$0.0018')
    expect(text).not.toContain('usage.noPrice')
  })
})
