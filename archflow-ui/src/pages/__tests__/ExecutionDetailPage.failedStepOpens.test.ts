/**
 * RCHFLW-130: quem chega ao detalhe pelo "Rodar" encontra a execução ainda RUNNING. O passo que falha depois
 * (pelo polling) ficava fechado até recarregar a página, embora a tela diga que os passos com falha já abrem
 * expandidos.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { fireEvent, within } from '@testing-library/dom'

const workflowApiMock = vi.hoisted(() => ({ execute: vi.fn(), get: vi.fn() }))
const executionApiMock = vi.hoisted(() => ({ get: vi.fn() }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
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

const ERRO = 'O provedor do modelo recusou a chamada (HTTP 400, requisição inválida): x is not a valid model ID'
const rodando = {
  id: 'exec-1', workflowId: 'wf-1', workflowName: 'Fluxo', status: 'RUNNING',
  steps: [{ stepId: 'llm', status: 'RUNNING', stepIndex: 0, stepCount: 1 }],
}
const falhou = {
  ...rodando, status: 'FAILED', error: ERRO, completedAt: '2026-11-25T12:00:00Z',
  steps: [{ stepId: 'llm', status: 'FAILED', stepIndex: 0, stepCount: 1, error: ERRO }],
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.useFakeTimers()
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  workflowApiMock.get.mockResolvedValue({ steps: [{ id: 'llm', label: 'Classificar' }] })
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
  vi.useRealTimers()
})

async function render() {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, null,
      h(MemoryRouter, { initialEntries: ['/executions/exec-1'] },
        h(Routes, null, h(Route, { path: '/executions/:id', element: h(ExecutionDetailPage) })))))
  })
}

const passo = () => container.querySelector('[data-testid="execution-step-llm"]') as HTMLElement

describe('ExecutionDetailPage — passo que falha depois da chegada', () => {
  it('abre expandido quando a falha chega pelo polling, e o clique ainda fecha', async () => {
    executionApiMock.get.mockResolvedValueOnce(rodando).mockResolvedValue(falhou)
    await render()
    expect(passo().getAttribute('data-expanded')).toBeNull()

    await act(async () => { await vi.advanceTimersByTimeAsync(2000) })

    expect(executionApiMock.get).toHaveBeenCalledTimes(2)
    expect(passo().getAttribute('data-expanded')).toBe('true')
    expect(passo().textContent).toContain(ERRO)

    await act(async () => { fireEvent.click(within(passo()).getByTestId('execution-step-name')) })
    expect(passo().getAttribute('data-expanded')).toBeNull()
  })

  it('aberto direto numa execução que já falhou, o passo também vem expandido', async () => {
    executionApiMock.get.mockResolvedValue(falhou)
    await render()
    expect(passo().getAttribute('data-expanded')).toBe('true')
  })
})
