/**
 * QA2 RCHFLW-136: decidir uma aprovação (aprovar, rejeitar, editar antes de aprovar) retoma ou encerra a
 * execução parada no portão — o servidor exige `execution:manage`, que hoje só o ADMIN tem (decisão de
 * produto em aberto na RCHFLW-196). Quem não a tem continua vendo o pedido, mas os botões ficam
 * desabilitados com o aviso da permissão que falta, em vez de oferecer uma decisão que vira 403.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { entrarComo } from '../../lib/__tests__/papeisDeTeste'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { waitFor } from '@testing-library/dom'

const approvalApiMock = vi.hoisted(() => ({ get: vi.fn(), submit: vi.fn() }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('../../services/approval-api', () => ({ approvalApi: approvalApiMock }))
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn() } }))
vi.mock('../../lib/confirm', () => ({ confirmAction: (o: { onConfirm: () => void }) => o.onConfirm() }))

import ApprovalDetailPage from '../ApprovalDetailPage'

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
  approvalApiMock.get.mockResolvedValue({
    requestId: 'req-1', tenantId: 'acme', flowId: 'exec-1', stepId: 'gate', status: 'PENDING',
    title: 'Revisar desconto', description: 'Confirme o desconto', proposal: { desconto: 15 },
    createdAt: '2026-11-24T12:00:00Z', expiresAt: null,
  })
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
    root!.render(h(MantineProvider, null,
      h(MemoryRouter, { initialEntries: ['/approvals/req-1'] },
        h(Routes, null, h(Route, { path: '/approvals/:id', element: h(ApprovalDetailPage) })))))
  })
  await waitFor(() => expect(container.querySelector('[data-testid="approval-approve"]')).not.toBeNull())
}

function botao(testId: string): HTMLButtonElement {
  return container.querySelector(`[data-testid="${testId}"]`) as HTMLButtonElement
}

function falta(testId: string): string | null {
  return botao(testId).closest('[data-permission-missing]')?.getAttribute('data-permission-missing') ?? null
}

const DECISOES = ['approval-approve', 'approval-reject', 'approval-edit-toggle']

describe('ApprovalDetailPage — decidir exige execution:manage (QA2 RCHFLW-136)', () => {
  for (const papel of ['VIEWER', 'EXECUTOR', 'DESIGNER']) {
    it(`${papel}: vê o pedido, mas Aprovar, Rejeitar e Editar ficam desabilitados com o aviso`, async () => {
      entrarComo(papel)
      await render()

      expect(container.querySelector('[data-testid="approval-title"]')?.textContent).toBe('Revisar desconto')
      for (const id of DECISOES) {
        expect(botao(id).disabled, id).toBe(true)
        expect(falta(id), id).toBe('execution:manage')
      }
      botao('approval-approve').click()
      expect(approvalApiMock.submit).not.toHaveBeenCalled()
    })
  }

  it('ADMIN: os três habilitados, sem aviso, e aprovar envia a decisão', async () => {
    entrarComo('ADMIN')
    approvalApiMock.submit.mockResolvedValue({ requestId: 'req-1', status: 'APPROVED', decidedBy: 'root' })
    await render()

    for (const id of DECISOES) {
      expect(botao(id).disabled, id).toBe(false)
      expect(falta(id), id).toBeNull()
    }
    await act(async () => { botao('approval-approve').click() })
    expect(approvalApiMock.submit).toHaveBeenCalledTimes(1)
  })
})
