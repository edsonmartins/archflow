/**
 * AG-UI Runner: Parar chama a função de parar do agui-client, que também cancela a execução no
 * servidor (RCHFLW-54). Se o servidor recusar, a página diz que a execução pode continuar rodando.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'
import type { AgUiEvent } from '../../services/agui-client'

const stopMock = vi.hoisted(() => vi.fn(async (): Promise<'stopped' | 'byDisconnect' | 'failed'> => 'stopped'))
const runMock = vi.hoisted(() => vi.fn((_id: string, _input: unknown, _onEvent: (ev: AgUiEvent) => void) => stopMock))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('../../services/agui-client', () => ({ runAgUiWorkflow: runMock }))

import AgUiRunnerPage from '../AgUiRunnerPage'

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
  Element.prototype.scrollTo ??= function () {}
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
  await act(async () => { root!.render(h(MantineProvider, null, h(AgUiRunnerPage))) })
}

function inputField(): HTMLTextAreaElement {
  const el = within(container).getByTestId('agui-input')
  return (el.tagName === 'TEXTAREA' ? el : el.querySelector('textarea')) as HTMLTextAreaElement
}

async function type(text: string) {
  await act(async () => { fireEvent.change(inputField(), { target: { value: text } }) })
}

async function runWith(text: string | null) {
  await render()
  if (text !== null) await type(text)
  await act(async () => { fireEvent.click(within(container).getByTestId('agui-run')) })
  await waitFor(() => expect(runMock).toHaveBeenCalledTimes(1))
  return runMock.mock.calls[0]
}

describe('AgUiRunnerPage — Parar', () => {
  it('Parar chama a função de parar do cliente (que cancela no servidor)', async () => {
    await runWith(null)
    const onEvent = runMock.mock.calls[0][2]
    await act(async () => { onEvent({ type: 'RUN_STARTED', runId: 'exec-1' }) })

    await act(async () => { fireEvent.click(within(container).getByTestId('agui-stop')) })

    expect(stopMock).toHaveBeenCalledTimes(1)
    expect(within(container).queryByTestId('agui-stop-error')).toBeNull()
    expect(within(container).getByTestId('agui-run')).toBeTruthy()
  })

  it('servidor recusa o cancelamento → a página avisa que a execução pode continuar', async () => {
    stopMock.mockResolvedValueOnce('failed')
    await runWith(null)

    await act(async () => { fireEvent.click(within(container).getByTestId('agui-stop')) })

    await waitFor(() => expect(within(container).getByTestId('agui-stop-error').textContent).toBe('agui.stopError'))
  })

  it('sem permissão de cancelar pela API (403) → diz que a desconexão interrompe, sem o erro', async () => {
    stopMock.mockResolvedValueOnce('byDisconnect')
    await runWith(null)

    await act(async () => { fireEvent.click(within(container).getByTestId('agui-stop')) })

    await waitFor(() => expect(within(container).getByTestId('agui-stop-by-disconnect').textContent)
      .toBe('agui.stopByDisconnect'))
    expect(within(container).queryByTestId('agui-stop-error')).toBeNull()
  })
})
