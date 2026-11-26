/**
 * QA de aprovações no detalhe da execução: a decisão EDITED aparece como aprovação com a proposta que
 * seguiu no fluxo (RCHFLW-121), e o pedido vencido antes da varredura não é mais "aguardando decisão"
 * (RCHFLW-126).
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

describe('ExecutionDetailPage — QA de aprovações', () => {
  it('EDITED é aprovação com edição e mostra a proposta aprovada', async () => {
    executionApiMock.get.mockResolvedValue({
      ...base, status: 'COMPLETED', steps: [],
      approvals: [
        { stepId: 'gate-a', requestId: 'req-1', decision: 'EDITED', decidedBy: 'maria',
          comment: 'baixei o valor', decidedAt: '2026-11-24T12:00:00Z', editedPayload: { valor: 450 } },
      ],
    })
    await render()

    const card = container.querySelector('[data-testid="execution-approval-req-1"]')!
    // Antes: EDITED caía em "Recusado"
    expect(card.textContent).toContain('executionDetail.approvals.edited')
    expect(card.textContent).not.toContain('executionDetail.approvals.rejected')
    expect(card.textContent).toContain('maria')
    const payload = card.querySelector('[data-testid="execution-approval-edited-payload"]')
    expect(payload?.textContent).toContain('450')
  })

  it('pedido vencido e ainda não varrido: diz que o prazo venceu, não que aguarda decisão', async () => {
    executionApiMock.get.mockResolvedValue({
      ...base, status: 'PAUSED', steps: [],
      pendingApproval: { requestId: 'req-1', stepId: 'gate-a', expiresAt: '2026-11-24T12:05:00Z', expired: true },
    })
    await render()

    const pending = container.querySelector('[data-testid="execution-pending-approval"]')!
    expect(pending.textContent).toContain('executionDetail.pendingApproval.expiredTitle')
    expect(pending.textContent).toContain('executionDetail.pendingApproval.expiredText')
    expect(pending.textContent).not.toContain('executionDetail.pendingApproval.title')
  })
})
