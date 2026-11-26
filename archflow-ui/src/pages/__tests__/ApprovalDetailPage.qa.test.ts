/**
 * QA da tela de aprovação: JSON editado validado antes da confirmação, com o erro junto do campo e o
 * texto preservado (RCHFLW-128); pedido vencido com Aprovar e Rejeitar desligados e a recusa tardia
 * junto dos botões, sem apagar a tela (RCHFLW-126); status traduzido (RCHFLW-129); e a decisão EDITED
 * mostrando o que foi aprovado (RCHFLW-121).
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { entrarComo } from '../../lib/__tests__/papeisDeTeste'
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

let root: Root | null = null
let container: HTMLDivElement

const pending = {
  requestId: 'req-1', tenantId: 'acme', flowId: 'exec-1', stepId: 'gate', status: 'PENDING',
  title: 'Reembolso', description: 'Confirme o reembolso', proposal: { valor: 800 },
  createdAt: '2026-11-24T12:00:00Z', expiresAt: null, flowName: 'QA Aprovação',
}

beforeEach(() => {
  // As ações que acionam o servidor só aparecem para quem tem a permissão (QA2 RCHFLW-136/158).
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
  approvalApiMock.submit.mockResolvedValue({ ...pending, status: 'EDITED', decidedBy: 'maria' })
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function render(url = '/approvals/req-1?flowId=exec-1') {
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

const byTestId = (id: string) => container.querySelector(`[data-testid="${id}"]`) as HTMLElement | null

async function click(id: string) {
  await act(async () => { byTestId(id)!.click() })
}

/** Digita no textarea controlado do React (o setter nativo dispara o onChange). */
async function type(el: HTMLTextAreaElement, value: string) {
  const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!
  await act(async () => {
    setter.call(el, value)
    el.dispatchEvent(new Event('input', { bubbles: true }))
  })
}

function commentBox(): HTMLTextAreaElement {
  return Array.from(container.querySelectorAll('textarea'))
    .find((t) => t.getAttribute('data-testid') !== 'approval-edit-payload') as HTMLTextAreaElement
}

describe('ApprovalDetailPage — editar antes de aprovar (RCHFLW-128)', () => {
  it('JSON inválido não abre a confirmação: erro junto do campo, texto e comentário preservados', async () => {
    await render()
    await click('approval-edit-toggle')
    const payload = byTestId('approval-edit-payload') as HTMLTextAreaElement
    await type(payload, 'Reembolso aprovado R$ 900 {')
    await type(commentBox(), 'conferi com o financeiro')

    await click('approval-approve')

    expect(confirmMock).not.toHaveBeenCalled()
    expect(approvalApiMock.submit).not.toHaveBeenCalled()
    expect(container.textContent).toContain('approvals.detail.invalidJson')
    // A tela continua: o que foi digitado está lá, para corrigir
    expect((byTestId('approval-edit-payload') as HTMLTextAreaElement).value).toBe('Reembolso aprovado R$ 900 {')
    expect(commentBox().value).toBe('conferi com o financeiro')
    expect(byTestId('approval-approve')).not.toBeNull()
  })

  it('JSON válido confirma e envia EDITED com o objeto editado', async () => {
    await render()
    await click('approval-edit-toggle')
    await type(byTestId('approval-edit-payload') as HTMLTextAreaElement, '{"valor": 450}')

    await click('approval-approve')

    expect(confirmMock).toHaveBeenCalledTimes(1)
    expect(approvalApiMock.submit.mock.calls[0][1]).toMatchObject({ decision: 'EDITED', editedPayload: { valor: 450 } })
  })
})

describe('ApprovalDetailPage — aprovação vencida (RCHFLW-126)', () => {
  it('EXPIRED ainda não varrida: aviso de prazo vencido e Aprovar/Rejeitar desligados', async () => {
    approvalApiMock.get.mockResolvedValue({ ...pending, status: 'EXPIRED', expiresAt: '2026-11-24T12:05:00Z' })
    await render()

    expect(byTestId('approval-expired')?.textContent).toContain('approvals.detail.expiredText')
    expect((byTestId('approval-approve') as HTMLButtonElement).disabled).toBe(true)
    expect((byTestId('approval-reject') as HTMLButtonElement).disabled).toBe(true)
    expect(byTestId('approval-edit-toggle')).toBeNull()
  })

  it('prazo passado na resposta PENDING também desliga os botões', async () => {
    approvalApiMock.get.mockResolvedValue({ ...pending, expiresAt: '2020-01-01T00:00:00Z' })
    await render()

    expect((byTestId('approval-approve') as HTMLButtonElement).disabled).toBe(true)
    expect((byTestId('approval-reject') as HTMLButtonElement).disabled).toBe(true)
  })

  it('recusa do servidor (prazo vencido no meio) aparece junto dos botões, sem apagar a tela', async () => {
    approvalApiMock.submit.mockRejectedValue(new Error('O prazo desta aprovação venceu e ela não pode mais ser decidida'))
    await render()
    await type(commentBox(), 'ok para mim')

    await click('approval-approve')

    expect(byTestId('approval-submit-error')?.textContent).toContain('O prazo desta aprovação venceu')
    // A proposta, os botões e o comentário continuam na tela
    expect(byTestId('approval-approve')).not.toBeNull()
    expect(byTestId('approval-title')?.textContent).toBe('Reembolso')
    expect(commentBox().value).toBe('ok para mim')
  })
})

describe('ApprovalDetailPage — status, fluxo e edição registrada', () => {
  it('status traduzido e o nome do fluxo no lugar do id', async () => {
    await render()

    expect(byTestId('approval-status')?.textContent).toBe('approvals.status.PENDING')
    expect(container.textContent).toContain('QA Aprovação')
  })

  it('EDITED decidida mostra a proposta aprovada', async () => {
    approvalApiMock.get.mockResolvedValue({
      ...pending, status: 'EDITED', proposal: null, decidedBy: 'maria', decidedAt: '2026-11-24T12:01:00Z',
      comment: 'baixei o valor', editedPayload: { valor: 450 },
    })
    await render()

    expect(byTestId('approval-status')?.textContent).toBe('approvals.status.EDITED')
    expect(byTestId('approval-decided')?.textContent).toContain('maria')
    expect(byTestId('approval-edited-payload')?.textContent).toContain('450')
  })
})
