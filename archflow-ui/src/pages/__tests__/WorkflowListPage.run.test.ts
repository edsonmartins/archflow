/**
 * "Executar" da lista de fluxos: pede a entrada (RunInputModal), manda o corpo certo ao
 * POST /workflows/{id}/execute e abre o DETALHE da execução. Antes ia para /executions?id=…, uma
 * rota que ignora o id e mostra a lista inteira.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'

const workflowApiMock = vi.hoisted(() => ({
  list: vi.fn(), execute: vi.fn(), setStatus: vi.fn(), get: vi.fn(), create: vi.fn(),
  update: vi.fn(), delete: vi.fn(), deployment: vi.fn(), versions: vi.fn(),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, o?: { number?: number }) => (o?.number !== undefined ? `${k}:${o.number}` : k),
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))
vi.mock('../../services/api', async (importOriginal) => ({
  ApiError: (await importOriginal<typeof import('../../services/api')>()).ApiError,
  workflowApi: workflowApiMock,
  executionApi: { list: vi.fn() },
}))

import WorkflowListPage from '../WorkflowListPage'
import { useWorkflowStore } from '../../stores/workflow-store'

function DetailProbe() {
  const { id } = useParams()
  return h('div', { 'data-testid': 'detail' }, `detalhe:${id}`)
}

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
  sessionStorage.clear()
  useWorkflowStore.setState({ workflows: [], loading: false, error: null })
  workflowApiMock.list.mockResolvedValue([
    { id: 'wf-1', name: 'Triagem', description: '', status: 'draft', version: '1.0.0', stepCount: 2, updatedAt: '2026-11-23T00:00:00Z' },
  ])
  workflowApiMock.execute.mockResolvedValue({ executionId: 'exec-42', status: 'RUNNING' })
  workflowApiMock.deployment.mockResolvedValue({ workflowId: 'wf-1', environment: 'PRODUCTION', versionId: 'wfv-3' })
  workflowApiMock.versions.mockResolvedValue([
    { id: 'wfv-3', workflowId: 'wf-1', number: 3, document: {}, comment: null, createdAt: '' },
  ])
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

describe('WorkflowListPage — Executar', () => {
  it('pede a entrada, envia o objeto e abre o detalhe da execução', async () => {
    container = document.createElement('div')
    document.body.appendChild(container)
    root = createRoot(container)
    await act(async () => {
      root!.render(h(MantineProvider, null,
        h(MemoryRouter, { initialEntries: ['/workflows'] },
          h(Routes, null,
            h(Route, { path: '/workflows', element: h(WorkflowListPage) }),
            h(Route, { path: '/executions/:id', element: h(DetailProbe) }),
            h(Route, { path: '/executions', element: h('div', { 'data-testid': 'history' }) })))))
    })
    const ui = within(container)
    const run = await waitFor(() => ui.getByRole('button', { name: 'workflows.actions.execute' }))
    await act(async () => { fireEvent.click(run) })

    const body = within(document.body)
    const text = await waitFor(() => body.getByTestId('run-input-text'))
    // RCHFLW-89: a lista roda a versão em produção, não o rascunho — e o modal diz qual, com o número.
    await waitFor(() => expect(body.getByTestId('run-input-target').textContent).toBe('runInput.target.published:3'))
    await act(async () => { fireEvent.change(text, { target: { value: '{"message":"meu boleto venceu"}' } }) })
    await act(async () => { fireEvent.click(body.getByTestId('run-input-confirm')) })

    await waitFor(() => expect(workflowApiMock.execute).toHaveBeenCalledWith('wf-1', { message: 'meu boleto venceu' }))
    expect((await waitFor(() => ui.getByTestId('detail'))).textContent).toBe('detalhe:exec-42')
    expect(ui.queryByTestId('history')).toBeNull()
  })
})
