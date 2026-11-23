/**
 * Detalhe da execução: cada passo aparece com o nome do nó (não só o id "node-…"), com a saída
 * gravada pelo backend, e os passos do fluxo que não rodaram entram como "Não executado".
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'

const workflowApiMock = vi.hoisted(() => ({ execute: vi.fn(), get: vi.fn() }))
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

function execution(status: string) {
  return {
    id: 'exec-1', workflowId: 'wf-1', workflowName: 'Fluxo', status,
    steps: [
      { stepId: 'node-a', status: 'COMPLETED', stepIndex: 0, stepCount: 2, durationMs: 5,
        output: '{"resposta":"oi","nota":7}' },
      { stepId: 'node-b', status: 'COMPLETED', stepIndex: 1, stepCount: 2, output: 'texto puro' },
    ],
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  executionApiMock.get.mockResolvedValue(execution('COMPLETED'))
  workflowApiMock.get.mockResolvedValue({
    id: 'wf-1', metadata: { name: 'Fluxo' }, configuration: {},
    steps: [
      { id: 'node-a', label: 'Entrada' },
      { id: 'node-b', label: 'Responder' },
      { id: 'node-c', label: 'Escalar' },
    ],
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
    root!.render(
      h(MantineProvider, null,
        h(MemoryRouter, { initialEntries: ['/executions/exec-1'] },
          h(Routes, null, h(Route, { path: '/executions/:id', element: h(ExecutionDetailPage) })))),
    )
  })
  return within(container)
}

describe('ExecutionDetailPage — passos', () => {
  it('mostra "nome (id)" de cada passo, buscando o documento do fluxo', async () => {
    const ui = await render()
    await waitFor(() => expect(workflowApiMock.get).toHaveBeenCalledWith('wf-1'))
    const stepA = await waitFor(() => ui.getByTestId('execution-step-node-a'))
    await waitFor(() =>
      expect(within(stepA).getByTestId('execution-step-name').textContent).toBe('Entrada (node-a)'))
  })

  it('acrescenta os passos do fluxo sem registro como "Não executado"', async () => {
    const ui = await render()
    const stepC = await waitFor(() => ui.getByTestId('execution-step-node-c'))
    expect(stepC.getAttribute('data-not-executed')).toBe('true')
    expect(stepC.textContent).toContain('Escalar (node-c)')
    expect(stepC.textContent).toContain('executionDetail.step.notExecuted')
    expect(ui.getByTestId('execution-step-node-a').getAttribute('data-not-executed')).toBeNull()
  })

  it('não marca passos como não executados enquanto a execução roda', async () => {
    executionApiMock.get.mockResolvedValue(execution('RUNNING'))
    const ui = await render()
    await waitFor(() => ui.getByTestId('execution-step-node-a'))
    await waitFor(() => expect(workflowApiMock.get).toHaveBeenCalled())
    expect(ui.queryByTestId('execution-step-node-c')).toBeNull()
  })

  it.each(['PAUSED', 'AWAITING_APPROVAL'])(
    'execução suspensa (%s) não marca os passos depois do portão como não executados',
    async (status) => {
      // Aprovação humana: o fluxo parou, mas os passos sem registro ainda vão rodar.
      executionApiMock.get.mockResolvedValue(execution(status))
      const ui = await render()
      await waitFor(() => ui.getByTestId('execution-step-node-a'))
      await waitFor(() => expect(workflowApiMock.get).toHaveBeenCalled())
      await waitFor(() =>
        expect(within(ui.getByTestId('execution-step-node-a')).getByTestId('execution-step-name').textContent)
          .toBe('Entrada (node-a)'))
      expect(ui.queryByTestId('execution-step-node-c')).toBeNull()
    })

  it.each(['FAILED', 'CANCELLED', 'STOPPED'])(
    'execução terminal (%s) também lista os passos não executados',
    async (status) => {
      executionApiMock.get.mockResolvedValue(execution(status))
      const ui = await render()
      const stepC = await waitFor(() => ui.getByTestId('execution-step-node-c'))
      expect(stepC.getAttribute('data-not-executed')).toBe('true')
    })

  it('ao expandir o passo, mostra a saída gravada (JSON como árvore, texto como texto)', async () => {
    const ui = await render()
    const stepA = await waitFor(() => ui.getByTestId('execution-step-node-a'))
    await act(async () => { fireEvent.click(within(stepA).getByTestId('execution-step-name')) })
    const outA = await waitFor(() => within(stepA).getByTestId('execution-step-output'))
    expect(outA.textContent).toContain('resposta')
    expect(outA.textContent).toContain('oi')

    const stepB = ui.getByTestId('execution-step-node-b')
    await act(async () => { fireEvent.click(within(stepB).getByTestId('execution-step-name')) })
    const outB = await waitFor(() => within(stepB).getByTestId('execution-step-output'))
    expect(outB.textContent).toContain('texto puro')
  })

  it('sem o documento do fluxo, a página segue com os ids', async () => {
    workflowApiMock.get.mockRejectedValue(new Error('404'))
    const ui = await render()
    const stepA = await waitFor(() => ui.getByTestId('execution-step-node-a'))
    await waitFor(() => expect(workflowApiMock.get).toHaveBeenCalled())
    expect(within(stepA).getByTestId('execution-step-name').textContent).toBe('node-a')
    expect(ui.queryByTestId('execution-step-node-c')).toBeNull()
  })
})
