/**
 * Chat da conversa: formulário de suspensão (RCHFLW-67) e resposta em streaming (RCHFLW-68).
 *
 * <p>A conversa suspensa mostra o formulário — pelo GET da conversa ou pelo evento
 * interaction/form do stream — e trava o campo de mensagem; o envio retoma a conversa e a resposta
 * chega pelo stream. A resposta do agente chega em chat/delta, acumulados na bolha com o indicador
 * de streaming até o chat/end.</p>
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { entrarComo } from '../../../lib/__tests__/papeisDeTeste'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MantineProvider } from '@mantine/core'
import { fireEvent, waitFor, within } from '@testing-library/dom'
import type { ArchflowEvent } from '../../../services/event-stream'
import type { Conversation, FormDataType } from '../../../services/conversation-api'

const apiMock = vi.hoisted(() => ({
  getConversation: vi.fn(),
  getMessages: vi.fn(),
  sendMessage: vi.fn(),
  resumeConversation: vi.fn(),
  cancelConversation: vi.fn(),
}))

/** Stream falso: guarda o listener para o teste empurrar eventos como o SSE faria. */
const streams = vi.hoisted(() => [] as { emit: (evt: unknown) => void }[])

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (k: string) => k, i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' } }),
}))
vi.mock('../../../services/conversation-api', () => ({ conversationApi: apiMock }))
vi.mock('../../../services/event-stream', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../services/event-stream')>()
  class FakeStream {
    private listeners: ((evt: unknown) => void)[] = []
    constructor() { streams.push({ emit: (evt) => this.listeners.forEach((l) => l(evt)) }) }
    onStatus() { return () => {} }
    onEvent(l: (evt: unknown) => void) { this.listeners.push(l); return () => {} }
    open() {}
    close() {}
  }
  return { ...actual, ArchflowEventStream: FakeStream }
})

import ChatPanel from '../ChatPanel'
import { ApiError } from '../../../services/api'

let root: Root | null = null
let container: HTMLDivElement

const FORM: FormDataType = {
  id: 'support-ticket',
  title: 'Contact Support',
  fields: [{ id: 'subject', label: 'Subject', type: 'TEXT', required: true }],
  submitLabel: 'Submit Ticket',
}

function record(status: Conversation['status'], extra: Partial<Conversation> = {}): Conversation {
  return {
    id: 'conv-1', tenantId: 'default', status,
    createdAt: '2026-11-24T10:00:00Z', updatedAt: '2026-11-24T10:00:00Z', ...extra,
  }
}

let seq = 0
function event(domain: string, type: string, data: Record<string, unknown> = {}): ArchflowEvent {
  seq += 1
  return {
    envelope: {
      domain: domain as ArchflowEvent['envelope']['domain'],
      type: type as ArchflowEvent['envelope']['type'],
      id: `evt-${seq}`,
      timestamp: '2026-11-24T10:00:05Z',
    },
    data,
  }
}

beforeEach(() => {
  // As ações que acionam o servidor só aparecem para quem tem a permissão (QA2 RCHFLW-136/158).
  entrarComo('ADMIN')
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
  apiMock.getMessages.mockResolvedValue([
    { id: 'm1', role: 'system', content: 'Conversa iniciada', timestamp: '2026-11-24T10:00:00Z' },
  ])
  apiMock.sendMessage.mockResolvedValue({ messageId: 'm2' })
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
  // O stream só abre depois da carga do histórico.
  await waitFor(() => expect(streams.length).toBeGreaterThan(0))
  return ui
}

async function emit(evt: ArchflowEvent) {
  await act(async () => { streams[streams.length - 1].emit(evt) })
}

function messageInput(): HTMLInputElement {
  return within(container).getByLabelText('chat.placeholder') as HTMLInputElement
}

describe('ChatPanel — conversa suspensa (RCHFLW-67)', () => {
  it('o formulário do GET da conversa aparece e o campo de mensagem fica travado, dizendo por quê', async () => {
    apiMock.getConversation.mockResolvedValue(record('SUSPENDED', { formData: FORM, resumeToken: 'tok' }))
    const ui = await render()

    await waitFor(() => expect(ui.getByText('Contact Support')).toBeTruthy())
    expect(ui.getByText('Submit Ticket')).toBeTruthy()
    expect(messageInput().disabled).toBe(true)
    expect(messageInput().placeholder).toBe('chat.awaitingForm')
  })

  it('o formulário do evento interaction/form usa título e rótulos dos dados e trava o campo', async () => {
    apiMock.getConversation.mockResolvedValue(record('ACTIVE'))
    const ui = await render()
    expect(messageInput().disabled).toBe(false)

    await emit(event('interaction', 'form', {
      formId: 'support-ticket', resumeToken: 'tok', title: 'Contact Support', fields: FORM.fields,
      submitLabel: 'Submit Ticket',
    }))

    await waitFor(() => expect(ui.getByText('Contact Support')).toBeTruthy())
    expect(ui.queryByText('Input required')).toBeNull()
    expect(ui.getByText('Submit Ticket')).toBeTruthy()
    expect(messageInput().disabled).toBe(true)
  })

  it('enviar retoma pelo token e a resposta do fluxo chega pelo stream, sem sumir com a resposta HTTP', async () => {
    apiMock.getConversation.mockResolvedValue(record('SUSPENDED', { formData: FORM, resumeToken: 'tok' }))
    let resolveResume: (r: unknown) => void = () => {}
    apiMock.resumeConversation.mockReturnValue(new Promise((r) => { resolveResume = r }))
    const ui = await render()
    await waitFor(() => expect(ui.getByText('Contact Support')).toBeTruthy())

    await act(async () => {
      fireEvent.change(ui.getByLabelText(/Subject/), { target: { value: 'Fatura em dobro' } })
    })
    await act(async () => { fireEvent.click(ui.getByText('Submit Ticket')) })
    expect(apiMock.resumeConversation).toHaveBeenCalledWith('tok', { subject: 'Fatura em dobro' })

    // O fluxo responde antes de a resposta HTTP da retomada chegar.
    await emit(event('chat', 'start'))
    await emit(event('chat', 'message', {
      role: 'assistant', content: 'Chamado aberto: Fatura em dobro', messageId: 'srv-9',
    }))
    await emit(event('chat', 'end'))
    await act(async () => {
      // Papéis em minúsculas, como o servidor devolve (ConversationControllerImpl.toMessageResponse).
      resolveResume({
        conversationId: 'conv-1', status: 'RESUMED', messages: [
          { id: 'm1', role: 'system', content: 'Conversa iniciada', timestamp: '2026-11-24T10:00:00Z' },
          { id: 'm3', role: 'user', content: 'Form submitted: subject=Fatura em dobro',
            timestamp: '2026-11-24T10:00:04Z' },
        ],
      })
    })

    await waitFor(() => expect(container.textContent).toContain('Form submitted: subject=Fatura em dobro'))
    expect(container.textContent).toContain('Chamado aberto: Fatura em dobro')
    expect(ui.queryByText('Submit Ticket')).toBeNull()
    expect(messageInput().disabled).toBe(false)
  })

  it('envio recusado pelo servidor: o formulário volta, o campo segue travado e o aviso é a frase do servidor', async () => {
    apiMock.getConversation.mockResolvedValue(record('SUSPENDED', { formData: FORM, resumeToken: 'tok' }))
    const recusa = 'A execução e1 não está aguardando uma decisão humana (status COMPLETED)'
    apiMock.resumeConversation.mockRejectedValue(new ApiError(409, recusa))
    const ui = await render()
    await waitFor(() => expect(ui.getByText('Contact Support')).toBeTruthy())

    await act(async () => {
      fireEvent.change(ui.getByLabelText(/Subject/), { target: { value: 'Fatura em dobro' } })
    })
    await act(async () => { fireEvent.click(ui.getByText('Submit Ticket')) })

    await waitFor(() => expect(container.textContent).toContain(recusa))
    expect(container.textContent).not.toContain('chat.submitFormFailed')
    expect(ui.getByText('Submit Ticket')).toBeTruthy()
    expect(messageInput().disabled).toBe(true)
    expect(messageInput().placeholder).toBe('chat.awaitingForm')
  })

  it('chat/error encerra a espera: o aviso aparece e o campo volta a aceitar mensagens', async () => {
    apiMock.getConversation.mockResolvedValue(record('ACTIVE'))
    const ui = await render()
    await act(async () => { fireEvent.change(messageInput(), { target: { value: 'oi' } }) })
    await act(async () => { fireEvent.click(ui.getByText('chat.send')) })
    expect(messageInput().disabled).toBe(true)

    await emit(event('chat', 'start'))
    await emit(event('chat', 'error', { message: 'A execução e1 falhou ao continuar: sem chave' }))

    await waitFor(() => expect(container.textContent).toContain('A execução e1 falhou ao continuar: sem chave'))
    expect(messageInput().disabled).toBe(false)
  })
})

describe('ChatPanel — resposta em streaming (RCHFLW-68)', () => {
  it('os chat/delta se acumulam na bolha com o indicador de streaming até o chat/end', async () => {
    apiMock.getConversation.mockResolvedValue(record('ACTIVE'))
    await render()

    await emit(event('chat', 'start'))
    await emit(event('chat', 'delta', { content: 'O arch' }))
    await waitFor(() => expect(container.textContent).toContain('O arch'))
    await emit(event('chat', 'delta', { content: 'flow é um framework.' }))
    await waitFor(() => expect(container.textContent).toContain('O archflow é um framework.'))
    expect(container.querySelector('.mantine-Loader-root')).not.toBeNull()

    await emit(event('chat', 'message', { role: 'assistant', content: 'O archflow é um framework.' }))
    await emit(event('chat', 'end'))

    await waitFor(() => expect(container.querySelector('.mantine-Loader-root')).toBeNull())
    expect(container.textContent).toContain('O archflow é um framework.')
  })
})

describe('ChatPanel — outro visualizador e formulário retirado (RCHFLW-194)', () => {
  it('a mensagem do cliente enviada noutra aba chega pelo stream e entra na transcrição', async () => {
    apiMock.getConversation.mockResolvedValue(record('ACTIVE'))
    await render()

    await emit(event('chat', 'message', {
      role: 'user', content: 'Mensagem da aba A: já enviei as fotos', messageId: 'srv-u1',
    }))
    await emit(event('chat', 'start'))
    await emit(event('chat', 'message', { role: 'assistant', content: 'Ótimo, Joana!', messageId: 'srv-a1' }))
    await emit(event('chat', 'end'))

    await waitFor(() => expect(container.textContent).toContain('Ótimo, Joana!'))
    expect(container.textContent).toContain('Mensagem da aba A: já enviei as fotos')
    // A fala do cliente vem antes da resposta, e só uma vez.
    const texto = container.textContent ?? ''
    expect(texto.indexOf('Mensagem da aba A')).toBeLessThan(texto.indexOf('Ótimo, Joana!'))
    expect(texto.split('Mensagem da aba A').length - 1).toBe(1)
  })

  it('quem enviou não vê a própria mensagem duas vezes, chegue antes o evento ou a resposta HTTP', async () => {
    apiMock.getConversation.mockResolvedValue(record('ACTIVE'))
    let resolveSend: (r: unknown) => void = () => {}
    apiMock.sendMessage.mockReturnValueOnce(new Promise((r) => { resolveSend = r }))
    const ui = await render()

    await act(async () => { fireEvent.change(messageInput(), { target: { value: 'primeira' } }) })
    await act(async () => { fireEvent.click(ui.getByText('chat.send')) })
    // O stream entrega a fala antes da resposta do POST...
    await emit(event('chat', 'message', { role: 'user', content: 'primeira', messageId: 'srv-1' }))
    await act(async () => { resolveSend({ messageId: 'srv-1' }) })
    await emit(event('chat', 'start'))
    await emit(event('chat', 'message', { role: 'assistant', content: 'resposta 1', messageId: 'srv-r1' }))
    await emit(event('chat', 'end'))

    // ... e depois o inverso: o POST responde antes do evento.
    apiMock.sendMessage.mockResolvedValueOnce({ messageId: 'srv-2' })
    await act(async () => { fireEvent.change(messageInput(), { target: { value: 'segunda' } }) })
    await act(async () => { fireEvent.click(ui.getByText('chat.send')) })
    await emit(event('chat', 'message', { role: 'user', content: 'segunda', messageId: 'srv-2' }))

    const texto = container.textContent ?? ''
    expect(texto.split('primeira').length - 1).toBe(1)
    expect(texto.split('segunda').length - 1).toBe(1)
  })

  it('interaction/cancel retira o formulário e destrava o campo', async () => {
    apiMock.getConversation.mockResolvedValue(record('SUSPENDED', { formData: FORM, resumeToken: 'tok' }))
    const ui = await render()
    await waitFor(() => expect(ui.getByText('Contact Support')).toBeTruthy())

    await emit(event('interaction', 'cancel', { conversationId: 'conv-1' }))

    await waitFor(() => expect(ui.queryByText('Submit Ticket')).toBeNull())
    expect(messageInput().disabled).toBe(false)
  })

  it('envio recusado porque a execução terminou: o formulário não volta (o servidor o retirou)', async () => {
    apiMock.getConversation.mockResolvedValueOnce(record('SUSPENDED', { formData: FORM, resumeToken: 'tok' }))
    const recusa = 'A execução e1 terminou com STOPPED e não recebe mais este formulário: ele foi retirado da conversa'
    apiMock.resumeConversation.mockRejectedValue(new ApiError(409, recusa))
    apiMock.getConversation.mockResolvedValue(record('ACTIVE'))
    const ui = await render()
    await waitFor(() => expect(ui.getByText('Contact Support')).toBeTruthy())

    await act(async () => {
      fireEvent.change(ui.getByLabelText(/Subject/), { target: { value: 'Fatura em dobro' } })
    })
    await act(async () => { fireEvent.click(ui.getByText('Submit Ticket')) })

    await waitFor(() => expect(container.textContent).toContain(recusa))
    expect(ui.queryByText('Submit Ticket')).toBeNull()
    expect(messageInput().disabled).toBe(false)
  })
})

describe('ChatPanel — conversa cancelada não aceita novos turnos (RCHFLW-194)', () => {
  it('aberta já cancelada: o campo fica travado e diz por quê', async () => {
    apiMock.getConversation.mockResolvedValue(record('CANCELLED'))
    await render()

    await waitFor(() => expect(messageInput().disabled).toBe(true))
    expect(messageInput().placeholder).toBe('chat.cancelledPlaceholder')
  })

  it('Cancelar sob o formulário trava o campo; o aviso gravado aparece uma vez, mesmo que chegue também pelo '
    + 'stream', async () => {
    apiMock.getConversation.mockResolvedValue(record('SUSPENDED', { formData: FORM, resumeToken: 'tok' }))
    apiMock.cancelConversation.mockResolvedValue(undefined)
    const ui = await render()
    await waitFor(() => expect(ui.getByText('Contact Support')).toBeTruthy())
    const aviso = { id: 'm-cancel', role: 'system', content: 'Conversa cancelada', timestamp: '2026-11-24T10:00:06Z' }
    apiMock.getMessages.mockResolvedValue([
      { id: 'm1', role: 'system', content: 'Conversa iniciada', timestamp: '2026-11-24T10:00:00Z' },
      aviso,
    ])

    await act(async () => { fireEvent.click(ui.getByText('chat.form.cancel')) })
    await waitFor(() => expect(container.textContent).toContain('Conversa cancelada'))
    await emit(event('chat', 'start', { conversationId: 'conv-1' }))
    await emit(event('chat', 'message', {
      conversationId: 'conv-1', messageId: 'm-cancel', role: 'system', content: 'Conversa cancelada',
    }))
    await emit(event('chat', 'end', { conversationId: 'conv-1' }))

    expect(messageInput().disabled).toBe(true)
    expect(messageInput().placeholder).toBe('chat.cancelledPlaceholder')
    expect((container.textContent ?? '').split('Conversa cancelada').length - 1).toBe(1)
  })

  it('interaction/cancel de uma conversa que o registro diz cancelada trava o campo (outro visualizador)',
    async () => {
      apiMock.getConversation.mockResolvedValueOnce(record('SUSPENDED', { formData: FORM, resumeToken: 'tok' }))
      apiMock.getConversation.mockResolvedValue(record('CANCELLED'))
      const ui = await render()
      await waitFor(() => expect(ui.getByText('Contact Support')).toBeTruthy())

      await emit(event('interaction', 'cancel', { conversationId: 'conv-1' }))

      await waitFor(() => expect(messageInput().disabled).toBe(true))
      expect(ui.queryByText('Submit Ticket')).toBeNull()
      expect(messageInput().placeholder).toBe('chat.cancelledPlaceholder')
    })
})
