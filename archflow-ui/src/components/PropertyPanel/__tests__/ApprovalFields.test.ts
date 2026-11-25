/**
 * Os campos do nó de Aprovação humana só prometem o que o motor faz (RCHFLW-60).
 *
 * <p>O painel oferecia "Aprovação automática" no prazo vencido — o motor sempre recusa, e por
 * segurança não pode aprovar ("ninguém olhou" nunca vira "pode executar") —, e mostrava valores que
 * não estavam gravados no nó (prazo 30, título "Please review", proposta "{}"): o que aparecia na
 * tela não era o que chegava ao motor.</p>
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { within } from '@testing-library/dom'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string) => k,
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))

import { ApprovalFields } from '../fields/ApprovalFields'
import type { FlowNodeData } from '../../FlowCanvas/types'
import ptBR from '../../../i18n/locales/pt-BR.json'
import en from '../../../i18n/locales/en.json'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

let root: Root | null = null
let container: HTMLDivElement

function data(config: Record<string, unknown>): FlowNodeData {
  return { label: 'Aprovação', componentId: 'approval', nodeType: 'approval', status: 'idle', config } as FlowNodeData
}

async function render(config: Record<string, unknown>) {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(h(MantineProvider, null, h(ApprovalFields, { nodeData: data(config), update: vi.fn() })))
  })
  return within(container)
}

describe('ApprovalFields', () => {
  beforeEach(() => {
    ;(globalThis as any).ResizeObserver = ResizeObserverStub
    ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
    window.matchMedia ??= ((q: string) => ({
      matches: false, media: q, onchange: null,
      addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
      dispatchEvent: () => false,
    })) as any
    if (!(document as any).fonts) {
      Object.defineProperty(document, 'fonts', {
        value: { addEventListener() {}, removeEventListener() {} }, configurable: true,
      })
    }
  })

  afterEach(async () => {
    await act(async () => { root?.unmount() })
    root = null
    container?.remove()
  })

  it('não oferece aprovar no prazo vencido — só diz que recusa', async () => {
    const view = await render({})

    expect(view.getByTestId('approval-on-timeout').textContent)
      .toBe('editor.properties.fields.onTimeoutAlwaysReject')
    expect(container.textContent).not.toContain('onTimeoutOptions.approve')
    expect(container.querySelectorAll('input[role="combobox"], select')).toHaveLength(0)
    expect(view.queryByTestId('approval-on-timeout-legacy')).toBeNull()
  })

  it('avisa quando um nó antigo tem "aprovar no timeout" gravado', async () => {
    const view = await render({ approvalOnTimeout: 'approve' })

    expect(view.getByTestId('approval-on-timeout-legacy')).toBeTruthy()
  })

  it('não mostra valores que não estão gravados no nó', async () => {
    const view = await render({})

    // Prazo vazio = o global do servidor; antes aparecia "30", que nunca chegava ao motor.
    const timeout = view.getByTestId('approval-timeout-input') as HTMLInputElement
    expect(timeout.value).toBe('')
    expect(timeout.placeholder).toBe('editor.properties.fields.timeoutMinPlaceholder')
    // Proposta vazia = a saída do passo anterior; antes aparecia "{}".
    expect((view.getByTestId('approval-proposal-input') as HTMLTextAreaElement).value).toBe('')
    expect(container.querySelector('input[value="Please review"]')).toBeNull()

    // E o que o nó tem gravado aparece como está.
    await act(async () => { root?.unmount() })
    container.remove()
    const saved = await render({ approvalTimeoutMinutes: 5, approvalProposal: '{"desconto": 15}' })
    expect((saved.getByTestId('approval-timeout-input') as HTMLInputElement).value).toBe('5')
    expect((saved.getByTestId('approval-proposal-input') as HTMLTextAreaElement).value).toBe('{"desconto": 15}')
  })

  it('as chaves novas existem nos dois idiomas', () => {
    for (const locale of [ptBR, en] as any[]) {
      const fields = locale.editor.properties.fields
      for (const key of ['approvalTitleHint', 'timeoutMinHint', 'timeoutMinPlaceholder',
        'onTimeoutAlwaysReject', 'onTimeoutLegacy']) {
        expect(fields[key], key).toBeTruthy()
      }
      // A opção que prometia aprovar no timeout saiu dos dois idiomas.
      expect(fields.onTimeoutOptions).toBeUndefined()
    }
  })
})
