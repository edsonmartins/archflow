/**
 * RCHFLW-89 — o detalhe da execução diz o que rodou: o rascunho ou a vN publicada, como o backend gravou
 * no registro ao criar a execução. Registro antigo, sem essa gravação: a tela não diz nada — deduzir pela
 * versão em produção de hoje poderia nomear a versão errada.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { waitFor, within } from '@testing-library/dom'

const workflowApiMock = vi.hoisted(() => ({ get: vi.fn() }))
const executionApiMock = vi.hoisted(() => ({ get: vi.fn() }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, o?: { number?: number }) => (o?.number !== undefined ? `${k}:${o.number}` : k),
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
  workflowApiMock.get.mockResolvedValue({ id: 'wf-1', steps: [] })
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function render(record: Record<string, unknown>) {
  executionApiMock.get.mockResolvedValue({
    id: 'exec-1', workflowId: 'wf-1', workflowName: 'Fluxo', status: 'COMPLETED', steps: [], ...record,
  })
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, null,
      h(MemoryRouter, { initialEntries: ['/executions/exec-1'] },
        h(Routes, null, h(Route, { path: '/executions/:id', element: h(ExecutionDetailPage) })))))
  })
  const ui = within(container)
  await waitFor(() => ui.getByTestId('execution-detail'))
  return ui
}

describe('ExecutionDetailPage — versão que rodou', () => {
  it('versão publicada: mostra o número gravado no registro', async () => {
    const ui = await render({ workflowDraft: false, workflowVersionId: 'wfv-b', workflowVersionNumber: 2 })
    expect(ui.getByTestId('execution-version').textContent).toBe('executionDetail.version.published:2')
  })

  it('rascunho: diz que rodou o rascunho', async () => {
    const ui = await render({ workflowDraft: true })
    expect(ui.getByTestId('execution-version').textContent).toBe('executionDetail.version.draft')
  })

  it('registro antigo, sem a marca: não afirma versão nenhuma', async () => {
    const ui = await render({ workflowVersionId: 'wfv-a' })
    expect(ui.queryByTestId('execution-version')).toBeNull()
    expect(ui.getByText('executionDetail.openWorkflow')).toBeTruthy()
  })
})
