/**
 * RCHFLW-134 — o título e o breadcrumb do detalhe mostram o id INTEIRO da execução. O corte em 12 caracteres
 * comia o último de exec-xxxxxxxx (13): o título dizia "Execução exec-7164c03" para exec-7164c035, e quem copiava
 * dali levava um id que não existe.
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
    t: (k: string, o?: { id?: string }) => (o?.id !== undefined ? `${k}:${o.id}` : k),
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
    id: 'exec-7164c035', workflowId: 'wf-1', workflowName: 'Fluxo', status: 'COMPLETED', steps: [], ...record,
  })
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, null,
      h(MemoryRouter, { initialEntries: ['/executions/exec-7164c035'] },
        h(Routes, null, h(Route, { path: '/executions/:id', element: h(ExecutionDetailPage) })))))
  })
  const ui = within(container)
  await waitFor(() => ui.getByTestId('execution-detail'))
  return ui
}

describe('ExecutionDetailPage — id da execução inteiro', () => {
  it('título e breadcrumb trazem o id completo, sem cortar o último caractere', async () => {
    const ui = await render({})
    expect(ui.getByRole('heading', { level: 2 }).textContent).toBe('executionDetail.title:exec-7164c035')
    expect(ui.getByText('exec-7164c035')).toBeTruthy()
    expect(ui.queryByText('exec-7164c03')).toBeNull()
  })
})
