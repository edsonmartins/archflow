/**
 * A tela de aprovação não diz ao servidor quem decidiu — ele resolve pelo token — e mostra em nome de
 * quem a decisão foi registrada (RCHFLW-38). Antes ela mandava `responderId: 'me'` fixo, e o servidor
 * gravava "me" como o decisor de toda decisão.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'

const approvalApiMock = vi.hoisted(() => ({ get: vi.fn(), submit: vi.fn() }))
const notificationsMock = vi.hoisted(() => ({ show: vi.fn() }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, opts?: Record<string, unknown>) => (opts ? `${k}:${JSON.stringify(opts)}` : k),
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))
vi.mock('../../services/approval-api', () => ({ approvalApi: approvalApiMock }))
vi.mock('@mantine/notifications', () => ({ notifications: notificationsMock }))
// Confirmação direto: o que interessa é o corpo enviado.
vi.mock('../../lib/confirm', () => ({ confirmAction: (o: { onConfirm: () => void }) => o.onConfirm() }))

import ApprovalDetailPage from '../ApprovalDetailPage'
import { useTenantStore } from '../../stores/useTenantStore'

let root: Root | null = null
let container: HTMLDivElement

const pending = {
  requestId: 'req-1', tenantId: 'acme', flowId: 'exec-1', stepId: 'gate', status: 'PENDING',
  title: 'Revisar desconto', description: 'Confirme o desconto de 15%', proposal: { desconto: 15 },
  createdAt: '2026-11-24T12:00:00Z', expiresAt: null,
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
  if (!(document as any).fonts) {
    Object.defineProperty(document, 'fonts', {
      value: { addEventListener() {}, removeEventListener() {} }, configurable: true,
    })
  }
  approvalApiMock.get.mockResolvedValue(pending)
  approvalApiMock.submit.mockResolvedValue({
    ...pending, status: 'APPROVED', decidedBy: 'maria', decidedAt: '2026-11-24T12:01:00Z', comment: null,
  })
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function render(url = '/approvals/req-1') {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(
      h(MantineProvider, null,
        h(MemoryRouter, { initialEntries: [url] },
          h(Routes, null,
            h(Route, { path: '/approvals/:id', element: h(ApprovalDetailPage) }),
            h(Route, { path: '/approvals', element: h('div', null, 'fila') })))),
    )
  })
}

describe('ApprovalDetailPage — quem decide é o servidor', () => {
  it('mostra o título do nó e não manda responderId ao aprovar', async () => {
    await render()
    expect(container.querySelector('[data-testid="approval-title"]')?.textContent).toBe('Revisar desconto')

    await act(async () => {
      (container.querySelector('[data-testid="approval-approve"]') as HTMLButtonElement).click()
    })

    expect(approvalApiMock.submit).toHaveBeenCalledTimes(1)
    const body = approvalApiMock.submit.mock.calls[0][1]
    expect(body).not.toHaveProperty('responderId')
    expect(body).toMatchObject({ tenantId: 'acme', decision: 'APPROVED' })
    // A confirmação usa o decisor que o servidor registrou
    const shown = notificationsMock.show.mock.calls[0][0]
    expect(shown.message).toContain('approvals.detail.decidedMsg')
    expect(shown.message).toContain('maria')
  })
})

describe('ApprovalDetailPage — pedido já decidido', () => {
  // Decidido, o pedido sai da fila e o detalhe respondia 404: quem abria o link depois não via quem
  // decidiu. Com a execução no link, o servidor devolve a decisão do histórico durável (RCHFLW-38).
  it('pede o detalhe com a execução do link e mostra quem decidiu, sem os botões de decisão', async () => {
    approvalApiMock.get.mockResolvedValue({
      requestId: 'req-1', tenantId: 'acme', flowId: 'exec-1', stepId: 'gate', status: 'REJECTED',
      title: null, description: null, proposal: null, createdAt: null, expiresAt: null,
      decidedBy: 'diretor.financeiro', decidedAt: '2026-11-24T12:01:00Z', comment: 'desconto alto demais',
    })

    await render('/approvals/req-1?flowId=exec-1')

    // Papel comum (viewer, o inicial): sem tenantId — o servidor usa o da requisição.
    expect(approvalApiMock.get).toHaveBeenCalledWith('req-1', 'exec-1', undefined)
    const decided = container.querySelector('[data-testid="approval-decided"]')
    expect(decided).not.toBeNull()
    expect(decided?.textContent).toContain('diretor.financeiro')
    expect(container.querySelector('[data-testid="approval-decided-comment"]')?.textContent)
      .toBe('desconto alto demais')
    expect(container.querySelector('[data-testid="approval-approve"]')).toBeNull()
    expect(container.querySelector('[data-testid="approval-reject"]')).toBeNull()
  })

  it('pendente continua com os botões e sem o quadro de decisão', async () => {
    await render('/approvals/req-1?flowId=exec-1')

    expect(container.querySelector('[data-testid="approval-decided"]')).toBeNull()
    expect(container.querySelector('[data-testid="approval-approve"]')).not.toBeNull()
  })
})

describe('ApprovalDetailPage — tenant do detalhe', () => {
  afterEach(() => { useTenantStore.setState({ currentRole: 'viewer', impersonating: null }) })

  // A fila do superadmin é a de todos ('all'); o detalhe de um pedido de outro tenant visto ali só é
  // encontrado se for pedido do mesmo jeito — sem o parâmetro, o servidor procura no tenant da
  // requisição e responde 404 (RCHFLW-39).
  it('superadmin pede o detalhe com tenantId=all, como a fila', async () => {
    useTenantStore.setState({ currentRole: 'superadmin', impersonating: null })

    await render('/approvals/req-1?flowId=exec-1')

    expect(approvalApiMock.get).toHaveBeenCalledWith('req-1', 'exec-1', 'all')
  })
})
