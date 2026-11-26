/**
 * AG-UI Runner: a entrada do run segue a regra do modal "Entrada da execução" do editor
 * (lib/runInput.toAgUiRunInput). Antes a página mandava sempre messages=[{content:'run'}] e o fluxo
 * julgava a palavra "run" (RCHFLW-53).
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

describe('AgUiRunnerPage — entrada do run', () => {
  it('texto livre vai como mensagem do usuário (vira ${input}), não a palavra fixa "run"', async () => {
    const [id, input] = await runWith('meu boleto venceu')
    expect(id).toBe('wf-demo-001')
    expect(input).toEqual({ messages: [{ role: 'user', content: 'meu boleto venceu' }] })
  })

  it('objeto JSON vira state (uma variável por chave) e nenhuma mensagem sobrescreve o input', async () => {
    const [, input] = await runWith('{"message": "meu boleto venceu", "cliente": "ACME"}')
    expect(input).toEqual({ state: { message: 'meu boleto venceu', cliente: 'ACME' } })
  })

  it('vazio roda sem entrada, como o Executar do editor', async () => {
    const [, input] = await runWith(null)
    expect(input).toEqual({})
  })

  it('a dica acompanha o que o texto vai virar', async () => {
    await render()
    const hint = () => within(container).getByTestId('agui-input-hint').textContent
    expect(hint()).toBe('runInput.asEmpty')
    await type('{"a": 1}')
    expect(hint()).toBe('runInput.asVariables')
    await type('oi')
    expect(hint()).toBe('runInput.asText')
  })

  it('Ctrl+Enter no campo dispara o run com a entrada digitada', async () => {
    await render()
    await type('oi')
    await act(async () => { fireEvent.keyDown(inputField(), { key: 'Enter', ctrlKey: true }) })
    await waitFor(() => expect(runMock).toHaveBeenCalledTimes(1))
    expect(runMock.mock.calls[0][1]).toEqual({ messages: [{ role: 'user', content: 'oi' }] })
  })
})
