/**
 * "Gerar com IA" sem chave de LLM: o backend responde RUN_ERROR e o CopilotKit só avisa os
 * assinantes `onError` (o runAgent não rejeita). A ponte precisa transformar isso num toast de erro
 * e fechar o "montando seu fluxo" — antes a falha era silenciosa.
 *
 * <p>Arquivo .ts (createElement em vez de JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'

type Subscriber = {
  onAgentRunStarted?: () => void
  onError?: (e: { code: string; error: Error; context: Record<string, unknown> }) => void
}

const notificationsMock = vi.hoisted(() => ({ show: vi.fn(), hide: vi.fn() }))
const kit = vi.hoisted(() => ({
  subscribers: [] as Subscriber[],
  runAgent: vi.fn(),
  agent: { addMessage: vi.fn() },
}))

vi.mock('@mantine/notifications', () => ({ notifications: notificationsMock }))
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (k: string) => k }) }))
vi.mock('@copilotkit/react-core/v2', () => ({
  useAgent: () => ({ agent: kit.agent }),
  useCopilotKit: () => ({
    copilotkit: {
      runAgent: kit.runAgent,
      subscribe: (s: Subscriber) => {
        kit.subscribers.push(s)
        return { unsubscribe: () => { kit.subscribers = kit.subscribers.filter((x) => x !== s) } }
      },
    },
  }),
}))

import CopilotGenerateBridge, {
  COPILOT_GENERATE_NOTIFICATION_ID,
  describeCopilotRunError,
  isCopilotAbort,
  newCopilotGenerateNotificationId,
  requestCopilotGeneration,
} from '../CopilotGenerateBridge'

const t = (k: string) => k

/** Simula o CopilotKit: início da execução de topo + o(s) erro(s) que ele emite. */
function emit(code: string, message: string | Error, context: Record<string, unknown> = {}) {
  const error = message instanceof Error ? message : new Error(message)
  for (const s of kit.subscribers) s.onError?.({ code, error, context })
}

/** O que o @ag-ui/client produz quando o fetch é abortado pelo "Parar" do chat. */
function abortError(): Error {
  const e = new Error('signal is aborted without reason')
  e.name = 'AbortError'
  return e
}
function runStarted() {
  for (const s of kit.subscribers) s.onAgentRunStarted?.()
}

let root: Root | null = null
let container: HTMLDivElement

beforeEach(async () => {
  vi.clearAllMocks()
  kit.subscribers = []
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  await act(async () => { root!.render(h(CopilotGenerateBridge)) })
})

afterEach(async () => {
  await act(async () => { root?.unmount() })
  root = null
  container.remove()
})

describe('describeCopilotRunError', () => {
  it('acrescenta a dica de configuração quando a mensagem fala de chave de API', () => {
    const r = describeCopilotRunError(new Error('API key is required for OpenAI'), true, t)
    expect(r.title).toBe('copilot.errors.generateFailed')
    expect(r.message).toBe('API key is required for OpenAI — copilot.errors.apiKeyHint')
  })

  it('não inventa dica para outros erros e usa o título do chat fora da geração', () => {
    const r = describeCopilotRunError(new Error('rate limited'), false, t)
    expect(r).toEqual({ title: 'copilot.errors.runFailed', message: 'rate limited' })
  })

  it('erro sem mensagem vira texto genérico, nunca toast vazio', () => {
    expect(describeCopilotRunError(new Error(''), true, t).message).toBe('copilot.errors.unknown')
  })
})

describe('isCopilotAbort', () => {
  it('reconhece o abort pelo nome, pelo code copiado do evento e pelo runtimeErrorCode', () => {
    expect(isCopilotAbort(abortError())).toBe(true)
    expect(isCopilotAbort(Object.assign(new Error('x'), { code: 'abort' }))).toBe(true)
    expect(isCopilotAbort(new Error('x'), { runtimeErrorCode: 'abort' })).toBe(true)
    expect(isCopilotAbort(new Error('Fetch is aborted'))).toBe(true)
  })

  it('não confunde falha real com abort', () => {
    expect(isCopilotAbort(new Error('API key is required for OpenAI'), { runtimeErrorCode: 'INTERNAL' })).toBe(false)
    expect(isCopilotAbort('abort')).toBe(false)
  })
})

describe('newCopilotGenerateNotificationId', () => {
  it('gera um id novo a cada geração, com o prefixo do toast de "montando"', () => {
    const a = newCopilotGenerateNotificationId()
    const b = newCopilotGenerateNotificationId()
    expect(a.startsWith(`${COPILOT_GENERATE_NOTIFICATION_ID}-`)).toBe(true)
    expect(a).not.toBe(b)
  })
})

describe('CopilotGenerateBridge', () => {
  it('RUN_ERROR durante "Gerar com IA" fecha o toast de "montando" e mostra o erro uma vez', async () => {
    let finish!: () => void
    kit.runAgent.mockImplementation(() => {
      runStarted()
      // O CopilotKit emite o RUN_ERROR e depois a falha da execução: dois avisos, um toast.
      emit('agent_run_error_event', 'API key is required for OpenAI')
      emit('agent_run_failed', 'API key is required for OpenAI')
      return new Promise<void>((resolve) => { finish = resolve })
    })

    await act(async () => { requestCopilotGeneration('monte um fluxo', 'toast-montando-1') })

    expect(kit.agent.addMessage).toHaveBeenCalledWith(expect.objectContaining({ role: 'user', content: 'monte um fluxo' }))
    expect(notificationsMock.hide).toHaveBeenCalledWith('toast-montando-1')
    expect(notificationsMock.show).toHaveBeenCalledTimes(1)
    expect(notificationsMock.show).toHaveBeenCalledWith(expect.objectContaining({
      color: 'red',
      title: 'copilot.errors.generateFailed',
      message: 'API key is required for OpenAI — copilot.errors.apiKeyHint',
    }))
    await act(async () => { finish() })
  })

  it('erro de execução vinda do chat lateral também aparece, com título do chat', async () => {
    await act(async () => {
      runStarted()
      emit('agent_run_error_event', 'boom')
    })

    expect(notificationsMock.hide).not.toHaveBeenCalled()
    expect(notificationsMock.show).toHaveBeenCalledWith(expect.objectContaining({
      title: 'copilot.errors.runFailed', message: 'boom',
    }))
  })

  it('ignora erros que não são de execução (ex.: conexão ao montar o layout)', async () => {
    await act(async () => { emit('agent_connect_failed', 'offline') })
    expect(notificationsMock.show).not.toHaveBeenCalled()
  })

  it('uma nova execução volta a avisar', async () => {
    await act(async () => {
      runStarted()
      emit('agent_run_error_event', 'primeira')
      runStarted()
      emit('agent_run_error_event', 'segunda')
    })
    expect(notificationsMock.show).toHaveBeenCalledTimes(2)
  })

  it('"Parar" no chat não vira toast de falha (AbortError convertido em RUN_ERROR code abort)', async () => {
    await act(async () => {
      runStarted()
      // Como o @ag-ui/client 0.0.57 + CopilotKit entregam: rawEvent = o AbortError, code copiado.
      emit('agent_run_error_event', Object.assign(abortError(), { code: 'abort' }), {
        source: 'onRunErrorEvent', runtimeErrorCode: 'abort',
      })
    })
    expect(notificationsMock.show).not.toHaveBeenCalled()
  })

  it('abort reconhecido só pelo code ou só pelo runtimeErrorCode também é ignorado', async () => {
    await act(async () => {
      runStarted()
      emit('agent_run_error_event', Object.assign(new Error('aborted'), { code: 'abort' }))
      emit('agent_run_error_event', 'aborted', { runtimeErrorCode: 'abort' })
    })
    expect(notificationsMock.show).not.toHaveBeenCalled()
  })

  it('parar durante "Gerar com IA" não mostra erro nem fecha o toast por engano', async () => {
    kit.runAgent.mockImplementation(async () => {
      runStarted()
      emit('agent_run_error_event', abortError(), { runtimeErrorCode: 'abort' })
    })
    await act(async () => { requestCopilotGeneration('monte um fluxo', 'toast-montando-2') })
    expect(notificationsMock.show).not.toHaveBeenCalled()
    expect(notificationsMock.hide).not.toHaveBeenCalled()
  })
})
