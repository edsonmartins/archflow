/**
 * Uso e faturamento com o consumo medido (RCHFLW-75): o mês inicial é o corrente (era fixo em 2026-04),
 * a tabela mostra os tenants que o backend mediu e custo nulo aparece como "sem preço", nunca $0.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { recentMonths } from '../../../../lib/usageFormat'

const usageApiMock = vi.hoisted(() => ({ byTenant: vi.fn(), exportCsv: vi.fn(() => '/api/x.csv') }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, o?: Record<string, unknown>) => (o && 'count' in o ? `${k}:${String(o.count)}` : k),
    i18n: { language: 'en', resolvedLanguage: 'en' },
  }),
}))
vi.mock('../../../../services/admin-api', () => ({ usageApi: usageApiMock }))

import UsageBilling from '../UsageBilling'

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
    root!.render(h(MantineProvider, null, h(UsageBilling)))
  })
}

const byTestId = (id: string) => document.body.querySelector(`[data-testid="${id}"]`) as HTMLElement | null

describe('UsageBilling — consumo medido', () => {
  it('pede o mês corrente e, sem medição, mostra a tabela vazia e custo zero — não "sem preço"', async () => {
    usageApiMock.byTenant.mockResolvedValue([])
    await render()

    expect(usageApiMock.byTenant).toHaveBeenCalledWith(recentMonths()[0])
    expect(byTestId('usage-by-tenant')!.querySelectorAll('tbody tr')).toHaveLength(0)
    // "Sem preço" diria que houve consumo sem preço declarado; sem medição nenhuma, o custo é zero.
    expect(document.body.textContent).not.toContain('usage.noPrice')
    expect(document.body.textContent).toContain('R$0.00')
  })

  it('com consumo medido e nenhum preço declarado, o total é "sem preço"', async () => {
    usageApiMock.byTenant.mockResolvedValue([
      { tenantId: 'globex', tenantName: 'globex', executions: 1, tokensInput: 800, tokensOutput: 200,
        costCents: null, costReais: null, declaredCostUsd: null, executionsWithoutPrice: 1, percentOfTotal: 100,
        planLimit: null },
    ])
    await render()

    expect(document.body.textContent).toContain('usage.noPrice')
    expect(document.body.textContent).not.toContain('R$0.00')
  })

  it('mostra cada tenant medido, com custo em reais ou "sem preço"', async () => {
    usageApiMock.byTenant.mockResolvedValue([
      { tenantId: 'acme', tenantName: 'Acme', executions: 2, tokensInput: 3_500, tokensOutput: 1_500,
        costCents: 1, costReais: 0.005, declaredCostUsd: null, executionsWithoutPrice: 1, percentOfTotal: 83.3,
        planLimit: 5_000_000 },
      { tenantId: 'globex', tenantName: 'globex', executions: 1, tokensInput: 800, tokensOutput: 200,
        costCents: null, costReais: null, declaredCostUsd: null, executionsWithoutPrice: 1, percentOfTotal: 16.7,
        planLimit: null },
    ])
    await render()

    const acme = byTestId('usage-row-acme')!.textContent
    expect(acme).toContain('R$0.005')
    expect(acme).toContain('usage.withoutPriceCount:1')
    expect(byTestId('usage-row-globex')!.textContent).toContain('usage.noPrice')
    expect(document.body.textContent).toContain('usage.withoutPriceCount:2')
  })
})

describe('recentMonths', () => {
  it('é o mês corrente e os onze anteriores, atravessando o ano', () => {
    const months = recentMonths(new Date(2026, 1, 15))
    expect(months).toHaveLength(12)
    expect(months[0]).toBe('2026-02')
    expect(months[1]).toBe('2026-01')
    expect(months[2]).toBe('2025-12')
  })
})
