/**
 * Detalhe da conversa: os painéis "Detalhes" e "Bruto" acompanham a transcrição ao vivo. Antes, a
 * transcrição andava pelo stream e o registro (messageCount, lastMessage, status) só era buscado na
 * carga e no "Atualizar" — Mensagens ficava em 1 e lastMessage em "Conversa iniciada" (RCHFLW-81).
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
import type { ArchflowEvent } from '../../services/event-stream'
import type { Conversation } from '../../services/conversation-api'

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
vi.mock('../../services/conversation-api', () => ({ conversationApi: apiMock }))
vi.mock('../../services/event-stream', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../services/event-stream')>()
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

import ConversationPage from '../ConversationPage'

let root: Root | null = null
let container: HTMLDivElement

function record(messageCount: number, lastMessage: string, status: Conversation['status'] = 'ACTIVE'): Conversation {
  return {
    id: 'conv-1', tenantId: 'default', status, messageCount, lastMessage,
    createdAt: '2026-11-24T10:00:00Z', updatedAt: '2026-11-24T10:00:00Z',
  }
}

let seq = 0
function chatEvent(type: string, data: Record<string, unknown> = {}): ArchflowEvent {
  seq += 1
  return {
    envelope: { domain: 'chat', type: type as ArchflowEvent['envelope']['type'], id: `evt-${seq}`,
      timestamp: '2026-11-24T10:00:05Z' },
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

async function render(id = 'conv-1') {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => {
    root!.render(
      h(MantineProvider, null,
        h(MemoryRouter, { initialEntries: [`/conversations/${id}`] },
          h(Routes, null, h(Route, { path: '/conversations/:id', element: h(ConversationPage) })))),
    )
  })
  return within(container)
}

/** Valor exibido ao lado do rótulo "Mensagens" no painel de detalhes. */
function messageCountShown(): string | null | undefined {
  const label = within(container).getByText('conversations.table.messages')
  return label.parentElement?.lastElementChild?.textContent
}

/** O último stream aberto é o da conversa já carregada (o tenant só chega com o registro). */
async function emit(evt: ArchflowEvent) {
  await act(async () => { streams[streams.length - 1].emit(evt) })
}

describe('ConversationPage — registro acompanha o stream', () => {
  it('envio e resposta do agente atualizam Mensagens e lastMessage sem "Atualizar"', async () => {
    apiMock.getConversation
      .mockResolvedValueOnce(record(1, 'Conversa iniciada')) // carga do ChatPanel (efeito do filho antes)
      .mockResolvedValueOnce(record(1, 'Conversa iniciada')) // página
      .mockResolvedValueOnce(record(2, 'oi'))                // depois do envio
      .mockResolvedValue(record(3, 'Olá! Como posso ajudar?')) // depois do chat/end
    const ui = await render()
    await waitFor(() => expect(messageCountShown()).toBe('1'))

    const input = await waitFor(() => ui.getByLabelText('chat.placeholder'))
    await act(async () => { fireEvent.change(input, { target: { value: 'oi' } }) })
    await act(async () => { fireEvent.click(ui.getByText('chat.send')) })
    await waitFor(() => expect(messageCountShown()).toBe('2'))

    await emit(chatEvent('start'))
    await emit(chatEvent('message', { role: 'assistant', content: 'Olá! Como posso ajudar?' }))
    await emit(chatEvent('end'))

    await waitFor(() => expect(messageCountShown()).toBe('3'))
    // O "Bruto" é o mesmo registro: a última mensagem deixa de ser "Conversa iniciada".
    await waitFor(() => expect(container.textContent).toContain('Olá! Como posso ajudar?'))
    expect(apiMock.getConversation).toHaveBeenCalledTimes(4)
  })

  it('o formulário de suspensão recebido pelo stream atualiza o status', async () => {
    apiMock.getConversation
      .mockResolvedValueOnce(record(1, 'Conversa iniciada'))
      .mockResolvedValueOnce(record(1, 'Conversa iniciada'))
      .mockResolvedValue(record(1, 'Conversa iniciada', 'SUSPENDED'))
    await render()
    await waitFor(() => expect(container.textContent).toContain('conversations.statuses.ACTIVE'))

    seq += 1
    await emit({
      envelope: { domain: 'interaction', type: 'form', id: `evt-${seq}`, timestamp: '2026-11-24T10:00:05Z' },
      data: { formId: 'f1', fields: [], resumeToken: 'tok' },
    })

    await waitFor(() => expect(container.textContent).toContain('conversations.statuses.SUSPENDED'))
  })

  it('uma resposta antiga que chega por último não volta a contagem', async () => {
    let resolveOld: (c: Conversation) => void = () => {}
    apiMock.getConversation
      .mockResolvedValueOnce(record(1, 'Conversa iniciada'))
      .mockResolvedValueOnce(record(1, 'Conversa iniciada'))
      .mockReturnValueOnce(new Promise<Conversation>((r) => { resolveOld = r })) // envio: lenta
      .mockResolvedValue(record(3, 'Olá!'))                                       // chat/end: rápida
    const ui = await render()
    await waitFor(() => expect(messageCountShown()).toBe('1'))

    const input = await waitFor(() => ui.getByLabelText('chat.placeholder'))
    await act(async () => { fireEvent.change(input, { target: { value: 'oi' } }) })
    await act(async () => { fireEvent.click(ui.getByText('chat.send')) })
    await emit(chatEvent('message', { role: 'assistant', content: 'Olá!' }))
    await emit(chatEvent('end'))
    await waitFor(() => expect(messageCountShown()).toBe('3'))

    await act(async () => { resolveOld(record(2, 'oi')) })
    expect(messageCountShown()).toBe('3')
  })
})

describe('ConversationPage — falhas da busca do registro', () => {
  it('um "Atualizar" que falha avisa na tela e mantém o último registro bom', async () => {
    apiMock.getConversation
      .mockResolvedValueOnce(record(1, 'Conversa iniciada')) // carga do ChatPanel (efeito do filho antes)
      .mockResolvedValueOnce(record(1, 'Conversa iniciada')) // página
      .mockRejectedValueOnce(new Error('{"error":"backend fora do ar","status":503}'))
      .mockResolvedValue(record(2, 'oi'))
    const ui = await render()
    await waitFor(() => expect(messageCountShown()).toBe('1'))
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})

    await act(async () => { fireEvent.click(ui.getByTestId('conversation-refresh')) })
    await waitFor(() => expect(container.textContent).toContain('conversationDetail.refreshError'))
    expect(messageCountShown()).toBe('1')

    // Um clique que dá certo limpa o aviso.
    await act(async () => { fireEvent.click(ui.getByTestId('conversation-refresh')) })
    await waitFor(() => expect(messageCountShown()).toBe('2'))
    expect(container.textContent).not.toContain('conversationDetail.refreshError')
    errorSpy.mockRestore()
  })

  it('a falha tardia da carga inicial não apaga um registro mais novo', async () => {
    let rejectInitial: (e: Error) => void = () => {}
    apiMock.getConversation
      // O efeito do filho roda antes do do pai: a 1ª chamada é a carga do ChatPanel, a 2ª a da página.
      .mockResolvedValueOnce(record(1, 'Conversa iniciada'))                               // ChatPanel
      .mockReturnValueOnce(new Promise<Conversation>((_, rej) => { rejectInitial = rej })) // página: lenta
      .mockResolvedValue(record(2, 'oi'))                                                  // depois do envio
    const ui = await render()

    const input = await waitFor(() => ui.getByLabelText('chat.placeholder'))
    await act(async () => { fireEvent.change(input, { target: { value: 'oi' } }) })
    await act(async () => { fireEvent.click(ui.getByText('chat.send')) })
    await waitFor(() => expect(apiMock.getConversation).toHaveBeenCalledTimes(3))

    await act(async () => { rejectInitial(new Error('timeout')) })
    await waitFor(() => expect(messageCountShown()).toBe('2'))
    expect(container.textContent).not.toContain('conversationDetail.noMetadata')
    expect(container.textContent).not.toContain('timeout')
  })
})

describe('ConversationPage — id da conversa (RCHFLW-194)', () => {
  it('o breadcrumb mostra o id inteiro: conversas com o mesmo prefixo de 8 caracteres não se confundem', async () => {
    apiMock.getConversation.mockResolvedValue(record(1, 'Conversa iniciada'))
    await render('qa2-conversas-form-1795667360')

    const trilha = await waitFor(() => {
      const found = container.querySelector('.mantine-Breadcrumbs-root')
      expect(found).not.toBeNull()
      return found as HTMLElement
    })
    expect(trilha.textContent).toContain('qa2-conversas-form-1795667360')
  })
})
