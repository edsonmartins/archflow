/**
 * "Executar novamente" pede a entrada (RunInputModal) e a manda no corpo do POST /execute.
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

const workflowApiMock = vi.hoisted(() => ({ execute: vi.fn() }))
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
  // Papel que concede editar e executar: sem ele as ações somem (RCHFLW-74).
  entrarComo('DESIGNER')
  vi.clearAllMocks()
  sessionStorage.clear()
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  executionApiMock.get.mockResolvedValue({
    id: 'exec-1', workflowId: 'wf-1', workflowName: 'Fluxo', status: 'COMPLETED', steps: [],
  })
  workflowApiMock.execute.mockResolvedValue({ executionId: 'exec-2', status: 'RUNNING', workflowVersionId: null })
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
            h(Route, { path: '/executions/:id', element: h(ExecutionDetailPage) }),
            h(Route, { path: '/executions/exec-2', element: h('div', null, 'nova') })))),
    )
  })
  return within(container)
}

async function rerunWith(text: string) {
  const ui = await render()
  const button = await waitFor(() => ui.getByTestId('execution-rerun'))
  await act(async () => { fireEvent.click(button) })
  const modal = within(document.body)
  const textarea = await waitFor(() => modal.getByTestId('run-input-text'))
  expect(workflowApiMock.execute).not.toHaveBeenCalled()
  await act(async () => { fireEvent.change(textarea, { target: { value: text } }) })
  await act(async () => { fireEvent.click(modal.getByTestId('run-input-confirm')) })
  await waitFor(() => expect(workflowApiMock.execute).toHaveBeenCalledTimes(1))
}

describe('ExecutionDetailPage — Executar novamente com entrada', () => {
  it('objeto JSON vai como mapa de variáveis', async () => {
    await rerunWith('{"pedido": 7}')
    expect(workflowApiMock.execute).toHaveBeenCalledWith('wf-1', { pedido: 7 })
  })

  it('texto livre vai como {input}', async () => {
    await rerunWith('reprocessar')
    expect(workflowApiMock.execute).toHaveBeenCalledWith('wf-1', { input: 'reprocessar' })
    expect(sessionStorage.getItem('archflow.runInput.wf-1')).toBe('reprocessar')
  })

  it('vazio executa sem corpo, como antes', async () => {
    await rerunWith('')
    expect(workflowApiMock.execute).toHaveBeenCalledWith('wf-1', undefined)
  })
})

describe('ExecutionDetailPage — Executar novamente por papel (RCHFLW-74)', () => {
  it('VIEWER vê a execução, mas não o botão de reexecutar', async () => {
    entrarComo('VIEWER')
    const ui = await render()
    await waitFor(() => expect(executionApiMock.get).toHaveBeenCalled())
    await waitFor(() => expect(container.textContent).toContain('Fluxo'))
    expect(ui.queryByTestId('execution-rerun')).toBeNull()
  })

  it('EXECUTOR reexecuta', async () => {
    entrarComo('EXECUTOR')
    const ui = await render()
    await waitFor(() => expect(ui.getByTestId('execution-rerun')).toBeTruthy())
  })
})
