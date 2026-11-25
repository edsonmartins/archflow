/**
 * O detalhe da execução mostra o portão de aprovação à espera como "aguardando aprovação" — não
 * "concluído" com ✓ e duração (RCHFLW-32) —, leva ao pedido aberto na fila e diz quem decidiu cada
 * portão, a partir do estado durável que o backend devolve em `approvals` (RCHFLW-38).
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
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
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
  workflowApiMock.get.mockResolvedValue({ steps: [{ id: 'gate-a', label: 'Revisar desconto' }] })
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
          h(Routes, null,
            h(Route, { path: '/executions/:id', element: h(ExecutionDetailPage) })))),
    )
  })
}

const base = { id: 'exec-1', workflowId: 'wf-1', workflowName: 'Desconto' }

describe('ExecutionDetailPage — portão de aprovação', () => {
  it('portão à espera: "aguardando aprovação", sem duração, e o link para o pedido na fila', async () => {
    executionApiMock.get.mockResolvedValue({
      ...base, status: 'PAUSED',
      steps: [{ stepId: 'gate-a', status: 'PAUSED', durationMs: 4321, output: '{"requestId":"req-1"}' }],
      pendingApproval: { requestId: 'req-1', stepId: 'gate-a' },
    })
    await render()

    const step = container.querySelector('[data-testid="execution-step-gate-a"]')!
    expect(step.textContent).toContain('executionDetail.step.awaiting')
    expect(step.textContent).not.toContain('executionDetail.step.completed')
    expect(step.textContent).not.toContain('4.3')
    const pending = container.querySelector('[data-testid="execution-pending-approval"]')!
    // A execução vai no link: decidido o pedido, o detalhe ainda mostra quem decidiu.
    expect(pending.querySelector('a')?.getAttribute('href')).toBe('/approvals/req-1?flowId=exec-1')
  })

  it('mostra quem decidiu cada portão, e a recusa pelo prazo sem um "decisor"', async () => {
    executionApiMock.get.mockResolvedValue({
      ...base, status: 'STOPPED',
      steps: [{ stepId: 'gate-a', status: 'REJECTED', decidedBy: 'system:timeout' }],
      approvals: [
        { stepId: 'gate-a', requestId: 'req-1', decision: 'APPROVED', decidedBy: 'maria',
          comment: 'dentro da política', decidedAt: '2026-11-24T12:00:00Z' },
        { stepId: 'gate-b', requestId: 'req-2', decision: 'REJECTED', decidedBy: 'system:timeout',
          comment: 'expirada apos PT1M', decidedAt: '2026-11-24T12:05:00Z' },
      ],
    })
    await render()

    const first = container.querySelector('[data-testid="execution-approval-req-1"]')!
    expect(first.textContent).toContain('executionDetail.approvals.approved')
    expect(first.textContent).toContain('maria')
    expect(first.textContent).toContain('dentro da política')
    // O nome do nó, não o id
    expect(first.textContent).toContain('Revisar desconto')

    const second = container.querySelector('[data-testid="execution-approval-req-2"]')!
    expect(second.textContent).toContain('executionDetail.approvals.timeout')
    expect(second.querySelector('[data-testid="execution-approval-decided-by"]')).toBeNull()

    const step = container.querySelector('[data-testid="execution-step-gate-a"]')!
    expect(step.textContent).toContain('executionDetail.step.rejected')
    expect(container.querySelector('[data-testid="execution-pending-approval"]')).toBeNull()
  })
})
