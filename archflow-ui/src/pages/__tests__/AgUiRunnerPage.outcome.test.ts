/**
 * AG-UI Runner: o badge de desfecho vem do result.status do RUN_FINISHED. Um fluxo FAILED (passo sem
 * chave) ou PAUSED (aprovação humana) também termina com RUN_FINISHED e mostrava "concluído" em verde.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { entrarComo } from '../../lib/__tests__/papeisDeTeste'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'
import type { AgUiEvent } from '../../services/agui-client'

const runMock = vi.hoisted(() => vi.fn((_id: string, _input: unknown, _onEvent: (ev: AgUiEvent) => void) => () => {}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('../../services/agui-client', () => ({ runAgUiWorkflow: runMock }))

import AgUiRunnerPage from '../AgUiRunnerPage'

let root: Root | null = null
let container: HTMLDivElement

beforeEach(() => {
  // As ações que acionam o servidor só aparecem para quem tem a permissão (QA2 RCHFLW-136/158).
  entrarComo('ADMIN')
  vi.clearAllMocks()
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  ;(globalThis as any).ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} }
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  Element.prototype.scrollTo ??= function () {}
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container?.remove()
})

async function run(): Promise<(ev: AgUiEvent) => void> {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => { root!.render(h(MantineProvider, null, h(AgUiRunnerPage))) })
  await act(async () => { fireEvent.click(within(container).getByTestId('agui-run')) })
  await waitFor(() => expect(runMock).toHaveBeenCalledTimes(1))
  const onEvent = runMock.mock.calls[0][2]
  return (ev) => { act(() => { onEvent(ev) }) }
}

describe('AgUiRunnerPage — desfecho do run', () => {
  it.each([
    [{ status: 'COMPLETED' }, 'agui.outcome.finished'],
    [undefined, 'agui.outcome.finished'],
    [{ status: 'FAILED' }, 'agui.outcome.failed'],
    [{ status: 'PAUSED' }, 'agui.outcome.paused'],
    [{ status: 'CANCELLED' }, 'agui.outcome.cancelled'],
  ])('RUN_FINISHED com result %j mostra %s', async (result, label) => {
    const emit = await run()
    emit({ type: 'RUN_STARTED', runId: 'exec-1' })
    emit(result ? { type: 'RUN_FINISHED', result } : { type: 'RUN_FINISHED' })
    const badge = await waitFor(() => within(container).getByTestId('agui-outcome'))
    expect(badge.textContent).toBe(label)
  })

  it('RUN_ERROR mostra erro', async () => {
    const emit = await run()
    emit({ type: 'RUN_ERROR', message: 'boom' })
    expect((await waitFor(() => within(container).getByTestId('agui-outcome'))).textContent).toBe('agui.outcome.error')
  })
})
