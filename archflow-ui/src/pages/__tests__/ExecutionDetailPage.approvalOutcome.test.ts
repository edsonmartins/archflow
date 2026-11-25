/**
 * O detalhe de uma execução PAUSED (aprovação humana) acompanha a decisão tomada na fila de
 * Aprovações sem F5, e uma recusa aparece como encerramento — com quem recusou e por quê —, não
 * como "A execução falhou".
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'

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

const paused = { id: 'exec-1', workflowId: 'wf-1', workflowName: 'Fluxo', status: 'PAUSED', steps: [] }
const rejected = {
  ...paused, status: 'STOPPED', completedAt: '2026-11-24T12:00:00Z',
  error: "Rejected at approval gate 'gate' by diretor: desconto alto demais",
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
  workflowApiMock.get.mockResolvedValue({ steps: [] })
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
    root!.render(
      h(MantineProvider, null,
        h(MemoryRouter, { initialEntries: ['/executions/exec-1'] },
          h(Routes, null,
            h(Route, { path: '/executions/:id', element: h(ExecutionDetailPage) })))),
    )
  })
}

async function tick(ms: number) {
  await act(async () => { await vi.advanceTimersByTimeAsync(ms) })
}

describe('ExecutionDetailPage — desfecho da aprovação humana', () => {
  it('PAUSED se recarrega sozinho e mostra a recusa como encerramento, não como falha', async () => {
    executionApiMock.get.mockResolvedValueOnce(paused).mockResolvedValue(rejected)
    await render()
    expect(executionApiMock.get).toHaveBeenCalledTimes(1)
    expect(container.textContent).toContain('executionDetail.hints.paused')

    await tick(5000)

    expect(executionApiMock.get).toHaveBeenCalledTimes(2)
    const alert = container.querySelector('[data-testid="execution-error"]')
    expect(alert?.textContent).toContain('executionDetail.stoppedTitle')
    expect(alert?.textContent).not.toContain('executionDetail.errorTitle')
    expect(alert?.textContent).toContain('diretor: desconto alto demais')

    // Terminal: para de recarregar.
    await tick(20000)
    expect(executionApiMock.get).toHaveBeenCalledTimes(2)
  })

  it('uma falha de verdade continua sob "A execução falhou"', async () => {
    executionApiMock.get.mockResolvedValue({ ...paused, status: 'FAILED', error: 'boom' })
    await render()

    const alert = container.querySelector('[data-testid="execution-error"]')
    expect(alert?.textContent).toContain('executionDetail.errorTitle')
    await tick(10000)
    expect(executionApiMock.get).toHaveBeenCalledTimes(1)
  })
})
