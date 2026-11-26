/**
 * Auditoria e traces (2º bug hunt de QA):
 * - RCHFLW-164: a tabela da auditoria mostra o contexto do evento (tenant, execução, nome);
 * - RCHFLW-180 / RCHFLW-195 item 9: o Exportar CSV baixa pela sessão com os filtros da tela — era um
 *   `<a href>` sem token;
 * - RCHFLW-195 item 6: busca sem resultado diz que nada corresponde, não "nenhum trace capturado";
 * - RCHFLW-177: PAUSED aparece como "aguardando aprovação".
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { MemoryRouter } from 'react-router-dom'
import { waitFor } from '@testing-library/dom'

const apiMock = vi.hoisted(() => ({
  listAudit: vi.fn(),
  listTraces: vi.fn(),
  auditExportUrl: vi.fn((f: Record<string, unknown>) => `/api/admin/observability/audit/export?action=${String(f.action ?? '')}`),
}))
const downloadMock = vi.hoisted(() => ({ baixarArquivo: vi.fn(() => Promise.resolve()) }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('../../../../services/observability-api', () => ({ observabilityApi: apiMock }))
vi.mock('../../../../lib/downloadFile', () => downloadMock)
vi.mock('@mantine/notifications', () => ({ notifications: { show: vi.fn() } }))

import AuditLogPage from '../AuditLogPage'
import TracesPage from '../TracesPage'

let root: Root | null = null
let container: HTMLDivElement

const byTestId = (id: string) => document.body.querySelector(`[data-testid="${id}"]`) as HTMLElement | null

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

async function render(page: () => unknown) {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, null, h(MemoryRouter, null, h(page as never))))
  })
}

async function digitar(el: HTMLInputElement, valor: string) {
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!
  await act(async () => {
    setter.call(el, valor)
    el.dispatchEvent(new Event('input', { bubbles: true }))
  })
}

describe('AuditLogPage', () => {
  it('mostra o contexto do evento e exporta pela sessão com o filtro da tela', async () => {
    apiMock.listAudit.mockResolvedValue({ page: 0, pageSize: 50, total: 1, items: [{
      id: 'evt-1', timestamp: '2026-11-26T10:00:00Z', action: 'WORKFLOW_EXECUTE', userId: 'u-1', username: 'admin',
      resourceType: 'workflow', resourceId: 'wf-1', success: true, errorMessage: null,
      context: { executionId: 'exec-5bf47251', tenant: '__default__' }, ipAddress: null, sessionId: null, traceId: null,
    }] })
    await render(AuditLogPage)

    await waitFor(() => expect(byTestId('audit-row-evt-1')).not.toBeNull())
    expect(byTestId('audit-row-evt-1')!.textContent).toContain('executionId=exec-5bf47251; tenant=__default__')
    expect(document.body.textContent).toContain('admin.observability.audit.cols.context')

    await digitar(byTestId('audit-action') as HTMLInputElement, 'LOGIN_SUCCESS')
    await waitFor(() => expect(apiMock.listAudit).toHaveBeenLastCalledWith(
      expect.objectContaining({ action: 'LOGIN_SUCCESS' })))
    const exportar = byTestId('audit-export')!
    expect(exportar.getAttribute('href')).toBeNull()
    await act(async () => { exportar.click() })

    expect(downloadMock.baixarArquivo).toHaveBeenCalledWith(
      '/api/admin/observability/audit/export?action=LOGIN_SUCCESS', 'audit-log.csv')
  })
})

describe('AuditLogPage — vazio com filtro', () => {
  it('filtro sem resultado diz que nada corresponde; sem filtro, que não há registro ainda', async () => {
    apiMock.listAudit.mockResolvedValue({ page: 0, pageSize: 50, total: 0, items: [] })
    await render(AuditLogPage)
    await waitFor(() => expect(document.body.textContent).toContain('admin.observability.audit.empty'))

    await digitar(byTestId('audit-action') as HTMLInputElement, 'LOGIN')
    await waitFor(() => expect(document.body.textContent).toContain('admin.observability.audit.noMatch'))
    expect(document.body.textContent).not.toContain('admin.observability.audit.emptyHint')

    await digitar(byTestId('audit-action') as HTMLInputElement, '')
    await digitar(byTestId('audit-actor') as HTMLInputElement, 'adm')
    await waitFor(() => expect(document.body.textContent).toContain('admin.observability.audit.noMatchHint'))
  })
})

describe('TracesPage', () => {
  it('busca sem resultado diz que nada corresponde; sem busca, que nada foi capturado', async () => {
    apiMock.listTraces.mockResolvedValue({ page: 0, pageSize: 50, total: 0, items: [] })
    await render(TracesPage)
    await waitFor(() => expect(document.body.textContent).toContain('admin.observability.traces.empty'))

    await digitar(byTestId('traces-search') as HTMLInputElement, 'QA2 obs triagem')
    await waitFor(() => expect(document.body.textContent).toContain('admin.observability.traces.noMatch'))
    expect(document.body.textContent).not.toContain('admin.observability.traces.emptyHint')
  })

  it('trace PAUSED aparece como aguardando aprovação, não como erro', async () => {
    apiMock.listTraces.mockResolvedValue({ page: 0, pageSize: 50, total: 1, items: [{
      traceId: 'trace-f347bad6-756', tenantId: 'acme', personaId: null, flowId: 'wf-1', executionId: 'exec-1',
      startedAt: new Date().toISOString(), durationMs: 14, status: 'PAUSED', spanCount: 1, error: null,
    }] })
    await render(TracesPage)

    await waitFor(() => expect(byTestId('trace-row-trace-f347bad6-756')).not.toBeNull())
    const linha = byTestId('trace-row-trace-f347bad6-756')!.textContent ?? ''
    expect(linha).toContain('admin.observability.traces.statusPaused')
    expect(linha).not.toContain('admin.observability.traces.statusError')
  })
})
