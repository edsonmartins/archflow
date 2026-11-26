/**
 * RCHFLW-122 — o diálogo de exclusão dizia só "Tem certeza? Esta ação não pode ser desfeita.": não dizia qual fluxo
 * saía nem que as execuções iam junto (e iam). Agora as execuções ficam; o diálogo nomeia o fluxo e diz que o
 * histórico é mantido, e o 409 do servidor (execução em andamento ou aguardando aprovação) aparece no próprio
 * diálogo, que continua aberto e mantém o fluxo na lista.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { entrarComo } from '../../lib/__tests__/papeisDeTeste'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'

const workflowApiMock = vi.hoisted(() => ({
  list: vi.fn(), execute: vi.fn(), setStatus: vi.fn(), get: vi.fn(), create: vi.fn(),
  update: vi.fn(), delete: vi.fn(), deployment: vi.fn(), versions: vi.fn(),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, o?: Record<string, string>) => (o ? `${k}[${Object.values(o).join('|')}]` : k),
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
import { ApiError } from '../../services/api'
import ptBR from '../../i18n/locales/pt-BR.json'
import en from '../../i18n/locales/en.json'

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
  entrarComo('ADMIN')
  vi.clearAllMocks()
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  ;(globalThis as any).ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} }
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  useWorkflowStore.setState({ workflows: [], loading: false, error: null })
  workflowApiMock.list.mockResolvedValue([
    { id: 'wf-1', name: 'Reembolso com aprovação', description: '', status: 'draft', version: '1.0.0', stepCount: 3,
      updatedAt: '2026-11-25T00:00:00Z' },
  ])
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function abrirExclusao() {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, null,
      h(MemoryRouter, { initialEntries: ['/workflows'] },
        h(Routes, null, h(Route, { path: '/workflows', element: h(WorkflowListPage) })))))
  })
  const ui = within(container)
  const excluir = await waitFor(() => ui.getByRole('button', { name: 'workflows.actions.delete' }))
  await act(async () => { fireEvent.click(excluir) })
  const body = within(document.body)
  await waitFor(() => body.getByTestId('delete-workflow-confirm'))
  return body
}

describe('WorkflowListPage — Excluir (RCHFLW-122)', () => {
  it('o diálogo nomeia o fluxo; confirmado, o fluxo sai da lista', async () => {
    workflowApiMock.delete.mockResolvedValue(undefined)
    const body = await abrirExclusao()

    expect(body.getByTestId('delete-workflow-message').textContent)
      .toBe('workflows.deleteModal.message[Reembolso com aprovação]')
    await act(async () => { fireEvent.click(body.getByTestId('delete-workflow-confirm')) })

    expect(workflowApiMock.delete).toHaveBeenCalledWith('wf-1')
    await waitFor(() => expect(useWorkflowStore.getState().workflows).toHaveLength(0))
    await waitFor(() => expect(body.queryByTestId('delete-workflow-message')).toBeNull())
  })

  it('409 com execução ativa: o motivo aparece no diálogo, que fica aberto, e o fluxo fica na lista', async () => {
    workflowApiMock.delete.mockRejectedValue(new ApiError(409, 'O fluxo tem uma execução…', JSON.stringify({
      error: 'O fluxo tem uma execução…', code: 'WORKFLOW_HAS_ACTIVE_EXECUTIONS', executions: ['exec-1'],
    })))
    const body = await abrirExclusao()

    await act(async () => { fireEvent.click(body.getByTestId('delete-workflow-confirm')) })

    expect((await waitFor(() => body.getByTestId('delete-workflow-error'))).textContent)
      .toBe('workflows.deleteModal.blocked')
    expect(body.getByTestId('delete-workflow-message')).toBeTruthy()
    expect(useWorkflowStore.getState().workflows.map(w => w.id)).toEqual(['wf-1'])
  })

  it('o texto do diálogo diz, nas duas línguas, que o histórico de execuções fica', () => {
    expect(ptBR.workflows.deleteModal.message).toContain('{{name}}')
    expect(ptBR.workflows.deleteModal.message).toContain('histórico de execuções')
    expect(en.workflows.deleteModal.message).toContain('{{name}}')
    expect(en.workflows.deleteModal.message).toContain('execution history')
    expect(ptBR.workflows.deleteModal.blocked).toBeTruthy()
    expect(en.workflows.deleteModal.blocked).toBeTruthy()
  })
})
