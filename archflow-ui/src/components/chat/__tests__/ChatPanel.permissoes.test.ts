/**
 * QA2 RCHFLW-136/158: o chat só oferece o que o servidor aceitaria. Toda escrita em
 * `/api/conversations/**` exige `workflow:execute` (um VIEWER digitava e levava 403 a cada envio), e o
 * envio do formulário que continua uma execução exige também `execution:manage`
 * (`SpringConversationController.resume`) — hoje só o ADMIN. O botão fica desabilitado, com o aviso de
 * qual permissão falta, em vez de sumir: quem vê o formulário sabe que ele existe e o que pedir.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { entrarComo } from '../../../lib/__tests__/papeisDeTeste'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { waitFor, within } from '@testing-library/dom'
import type { Conversation, FormDataType } from '../../../services/conversation-api'

const apiMock = vi.hoisted(() => ({
  getConversation: vi.fn(),
  getMessages: vi.fn(),
  sendMessage: vi.fn(),
  resumeConversation: vi.fn(),
  cancelConversation: vi.fn(),
}))
const streams = vi.hoisted(() => [] as unknown[])

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('../../../services/conversation-api', () => ({ conversationApi: apiMock }))
vi.mock('../../../services/event-stream', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../services/event-stream')>()
  class FakeStream {
    constructor() { streams.push(this) }
    onStatus() { return () => {} }
    onEvent() { return () => {} }
    open() {}
    close() {}
  }
  return { ...actual, ArchflowEventStream: FakeStream }
})

import ChatPanel from '../ChatPanel'

let root: Root | null = null
let container: HTMLDivElement

const FORM: FormDataType = {
  id: 'support-ticket',
  title: 'Contact Support',
  fields: [{ id: 'subject', label: 'Subject', type: 'TEXT', required: false }],
  submitLabel: 'Submit Ticket',
}

function record(status: Conversation['status'], extra: Partial<Conversation> = {}): Conversation {
  return {
    id: 'conv-1', tenantId: 'default', status,
    createdAt: '2026-11-24T10:00:00Z', updatedAt: '2026-11-24T10:00:00Z', ...extra,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  streams.length = 0
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  ;(globalThis as any).ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} }
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  Element.prototype.scrollTo ??= function () {}
  apiMock.getMessages.mockResolvedValue([])
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
    root!.render(h(MantineProvider, null, h(ChatPanel, { conversationId: 'conv-1' })))
  })
  const ui = within(container)
  await waitFor(() => expect(streams.length).toBeGreaterThan(0))
  return ui
}

function botao(ui: ReturnType<typeof within>, testId: string): HTMLButtonElement {
  return ui.getByTestId(testId) as HTMLButtonElement
}

/** A permissão que o aviso em volta do botão diz faltar, ou null quando nada falta. */
function falta(el: HTMLElement): string | null {
  return el.closest('[data-permission-missing]')?.getAttribute('data-permission-missing') ?? null
}

describe('ChatPanel — ações por papel (QA2 RCHFLW-136/158)', () => {
  it('VIEWER: o campo e o Enviar ficam desabilitados, dizendo que falta workflow:execute', async () => {
    entrarComo('VIEWER')
    apiMock.getConversation.mockResolvedValue(record('ACTIVE'))
    const ui = await render()

    const input = ui.getByLabelText('chat.placeholder') as HTMLInputElement
    await waitFor(() => expect(input.disabled).toBe(true))
    const enviar = botao(ui, 'chat-send')
    expect(enviar.disabled).toBe(true)
    expect(falta(enviar)).toBe('workflow:execute')
  })

  it('EXECUTOR: conversa normalmente (sem aviso)', async () => {
    entrarComo('EXECUTOR')
    apiMock.getConversation.mockResolvedValue(record('ACTIVE'))
    const ui = await render()

    const input = ui.getByLabelText('chat.placeholder') as HTMLInputElement
    await waitFor(() => expect(input.disabled).toBe(false))
    expect(falta(botao(ui, 'chat-send'))).toBeNull()
  })

  it('EXECUTOR com formulário que continua uma execução: Enviar desabilitado por execution:manage', async () => {
    entrarComo('EXECUTOR')
    apiMock.getConversation.mockResolvedValue(record('SUSPENDED', {
      formData: FORM, resumeToken: 'tok', executionId: 'exec-1',
    }))
    const ui = await render()
    await waitFor(() => expect(ui.getByText('Contact Support')).toBeTruthy())

    const enviar = botao(ui, 'chat-form-submit')
    expect(enviar.disabled).toBe(true)
    expect(falta(enviar)).toBe('execution:manage')
    // Cancelar a conversa é escrita em /api/conversations: só workflow:execute, que o EXECUTOR tem.
    expect(botao(ui, 'chat-form-cancel').disabled).toBe(false)
  })

  it('EXECUTOR com formulário sem execução: Enviar habilitado — o servidor só exige workflow:execute', async () => {
    entrarComo('EXECUTOR')
    apiMock.getConversation.mockResolvedValue(record('SUSPENDED', { formData: FORM, resumeToken: 'tok' }))
    const ui = await render()
    await waitFor(() => expect(ui.getByText('Contact Support')).toBeTruthy())

    expect(botao(ui, 'chat-form-submit').disabled).toBe(false)
    expect(falta(botao(ui, 'chat-form-submit'))).toBeNull()
  })

  it('VIEWER com formulário: nem Enviar nem Cancelar (falta workflow:execute)', async () => {
    entrarComo('VIEWER')
    apiMock.getConversation.mockResolvedValue(record('SUSPENDED', {
      formData: FORM, resumeToken: 'tok', executionId: 'exec-1',
    }))
    const ui = await render()
    await waitFor(() => expect(ui.getByText('Contact Support')).toBeTruthy())

    expect(botao(ui, 'chat-form-submit').disabled).toBe(true)
    expect(falta(botao(ui, 'chat-form-submit'))).toBe('workflow:execute')
    expect(botao(ui, 'chat-form-cancel').disabled).toBe(true)
  })

  it('ADMIN com formulário que continua uma execução: Enviar habilitado', async () => {
    entrarComo('ADMIN')
    apiMock.getConversation.mockResolvedValue(record('SUSPENDED', {
      formData: FORM, resumeToken: 'tok', executionId: 'exec-1',
    }))
    const ui = await render()
    await waitFor(() => expect(ui.getByText('Contact Support')).toBeTruthy())

    expect(botao(ui, 'chat-form-submit').disabled).toBe(false)
  })
})
