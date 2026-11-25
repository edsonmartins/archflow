/**
 * O detalhe da execução mostra o consumo de modelo medido (RCHFLW-75): tokens de entrada e saída e o
 * custo pela tabela de preços — ou "sem preço" quando o custo é nulo, nunca R$ 0.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'

const workflowApiMock = vi.hoisted(() => ({ execute: vi.fn(), get: vi.fn() }))
const executionApiMock = vi.hoisted(() => ({ get: vi.fn() }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string) => k,
    i18n: { language: 'en', resolvedLanguage: 'en' },
  }),
}))
vi.mock('../../services/api', () => ({
  workflowApi:  workflowApiMock,
  executionApi: executionApiMock,
  ApiError: class extends Error {},
}))
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn() } }))

import ExecutionDetailPage from '../ExecutionDetailPage'

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
  workflowApiMock.get.mockResolvedValue({ steps: [{ id: 'agente', label: 'Agente' }] })
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
    root!.render(
      h(MantineProvider, null,
        h(MemoryRouter, { initialEntries: ['/executions/exec-1'] },
          h(Routes, null, h(Route, { path: '/executions/:id', element: h(ExecutionDetailPage) })))))
  })
  for (let i = 0; i < 20 && !document.body.querySelector('[data-testid="execution-detail"]'); i++) {
    await act(async () => { await new Promise((r) => setTimeout(r, 10)) })
  }
}

const execution = (usage?: Record<string, unknown>) => ({
  id: 'exec-1', workflowId: 'wf-1', workflowName: 'Fluxo', status: 'COMPLETED',
  startedAt: '2026-11-25T12:00:00Z', completedAt: '2026-11-25T12:00:05Z', duration: 5000,
  steps: [{ stepId: 'agente', status: 'COMPLETED', durationMs: 4000 }],
  ...(usage ? { usage } : {}),
})

const usageCard = () => document.body.querySelector('[data-testid="execution-usage"]') as HTMLElement | null

describe('ExecutionDetailPage — uso de modelo', () => {
  it('mostra tokens e custo em reais', async () => {
    executionApiMock.get.mockResolvedValue(execution({
      inputTokens: 1300, outputTokens: 250, costCents: 1, costReais: 0.0018, declaredCostUsd: 0.002,
      withoutPrice: 0,
    }))
    await render()

    const text = usageCard()!.textContent
    expect(text).toContain('1,300')
    expect(text).toContain('250')
    expect(text).toContain('R$0.0018')
    expect(text).toContain('$0.002')
    expect(text).not.toContain('usage.noPrice')
  })

  // RCHFLW-105: com preço declarado, R$ 0,0000214 aparecia como R$ 0,00 (exec-a6578e9b).
  it('custo positivo abaixo de R$ 0,0001 aparece como "< R$0.0001", nunca R$ 0,00', async () => {
    executionApiMock.get.mockResolvedValue(execution({
      inputTokens: 14, outputTokens: 50, costCents: 1, costReais: 0.0000214, declaredCostUsd: null,
      withoutPrice: 0,
    }))
    await render()

    const text = usageCard()!.textContent
    expect(text).toContain('< R$0.0001')
    expect(text).not.toMatch(/R\$0\.00(?!01)/)
  })

  it('custo nulo aparece como "sem preço", nunca R$ 0', async () => {
    executionApiMock.get.mockResolvedValue(execution({
      inputTokens: 400, outputTokens: 80, costCents: null, costReais: null, declaredCostUsd: null,
      withoutPrice: 1,
    }))
    await render()

    const text = usageCard()!.textContent
    expect(text).toContain('usage.noPrice')
    expect(text).not.toContain('R$0')
  })

  it('execução sem chamada a modelo não ganha cartão de uso', async () => {
    executionApiMock.get.mockResolvedValue(execution())
    await render()

    expect(document.body.querySelector('[data-testid="execution-detail"]')).not.toBeNull()
    expect(usageCard()).toBeNull()
  })
})
