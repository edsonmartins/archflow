/**
 * RCHFLW-188, item 2 — a proposta em texto longo (a resposta de um LLM Chat) aparecia como uma linha só de 42.000px,
 * e no modo de edição virava uma string JSON entre aspas, com \n escapados.
 *
 * <p>RCHFLW-188, item 3 — decidir um pedido que outra aba já decidiu mostrava a frase do backend em inglês
 * ("Approval not found: <id>"), e a tela seguia PENDENTE com os botões ativos até recarregar.</p>
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
const confirmMock = vi.hoisted(() => vi.fn((o: { onConfirm: () => void }) => o.onConfirm()))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, opts?: Record<string, unknown>) => (opts && !('defaultValue' in opts) ? `${k}:${JSON.stringify(opts)}` : k),
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))
vi.mock('../../services/approval-api', () => ({ approvalApi: approvalApiMock }))
vi.mock('@mantine/notifications', () => ({ notifications: notificationsMock }))
vi.mock('../../lib/confirm', () => ({ confirmAction: confirmMock }))

import ApprovalDetailPage from '../ApprovalDetailPage'
import { entrarComo } from '../../lib/__tests__/papeisDeTeste'

const TEXTO = 'Prezado cliente,\nseu reembolso de R$ 320,00 foi aprovado.\n' + 'Detalhes do pedido. '.repeat(200)

const pending = {
  requestId: 'req-1', tenantId: 'acme', flowId: 'exec-1', stepId: 'gate', status: 'PENDING',
  title: 'Reembolso', description: 'Confirme o texto', proposal: TEXTO,
  createdAt: '2026-11-24T12:00:00Z', expiresAt: null, flowName: 'QA Aprovação',
}

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
  // Decidir exige execution:manage (RCHFLW-136): quem decide nestes cenários é o ADMIN.
  entrarComo('ADMIN')
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
        h(MemoryRouter, { initialEntries: ['/approvals/req-1?flowId=exec-1'] },
          h(Routes, null,
            h(Route, { path: '/approvals/:id', element: h(ApprovalDetailPage) }),
            h(Route, { path: '/approvals', element: h('div', null, 'fila') })))),
    )
  })
}

const byTestId = (id: string) => container.querySelector(`[data-testid="${id}"]`) as HTMLElement | null

async function click(id: string) {
  await act(async () => { byTestId(id)!.click() })
}

describe('ApprovalDetailPage — proposta em texto (RCHFLW-188, item 2)', () => {
  it('o texto quebra em linhas em vez de virar uma linha só', async () => {
    await render()
    const linha = Array.from(byTestId('approval-proposal')!.querySelectorAll<HTMLElement>('div'))
      .find(d => d.textContent?.includes('Prezado cliente'))!
    expect(linha.style.whiteSpace).toBe('pre-wrap')
  })

  it('editar mostra o texto como ele é, e aprovar editado envia o texto', async () => {
    approvalApiMock.submit.mockResolvedValue({ ...pending, status: 'EDITED', decidedBy: 'maria' })
    await render()
    await click('approval-edit-toggle')

    expect((byTestId('approval-edit-payload') as HTMLTextAreaElement).value).toBe(TEXTO)

    await click('approval-approve')
    expect(approvalApiMock.submit).toHaveBeenCalledWith('req-1', expect.objectContaining({
      decision: 'EDITED', editedPayload: TEXTO,
    }))
  })
})

describe('ApprovalDetailPage — decidido em outra aba (RCHFLW-188, item 3)', () => {
  it('a recusa do servidor relê o pedido: a tela mostra a decisão que vale e a frase do app', async () => {
    await render()
    approvalApiMock.submit.mockRejectedValue(Object.assign(new Error('Approval not found: req-1'), { status: 404 }))
    approvalApiMock.get.mockResolvedValue({
      ...pending, status: 'REJECTED', decidedBy: 'admin', decidedAt: '2026-11-24T12:05:00Z', comment: 'margem negativa',
    })

    await click('approval-approve')

    expect(byTestId('approval-status')!.textContent).toBe('approvals.status.REJECTED')
    expect(byTestId('approval-decided')).not.toBeNull()
    expect(byTestId('approval-approve')).toBeNull()
    const aviso = byTestId('approval-submit-error')!.textContent ?? ''
    expect(aviso).toContain('approvals.detail.alreadyDecided')
    expect(aviso).toContain('admin')
    expect(aviso).not.toContain('Approval not found')
  })

  it('outra falha (rede, 500) continua mostrando o motivo, sem reler', async () => {
    await render()
    approvalApiMock.get.mockClear()
    approvalApiMock.submit.mockRejectedValue(Object.assign(new Error('Internal Server Error'), { status: 500 }))

    await click('approval-approve')

    expect(approvalApiMock.get).not.toHaveBeenCalled()
    expect(byTestId('approval-submit-error')!.textContent).toBe('Internal Server Error')
  })
})
