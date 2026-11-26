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
const kbMock = vi.hoisted(() => ({ listBases: vi.fn() }))
const kit = vi.hoisted(() => ({
  subscribers: [] as Subscriber[],
  runAgent: vi.fn(),
  agent: {
    threadId: 'thread-antiga',
    messages: [] as { role: string; content?: string }[],
    addMessage: vi.fn(),
    setMessages: vi.fn(),
  },
  agentIds: [] as (string | undefined)[],
}))

vi.mock('@mantine/notifications', () => ({ notifications: notificationsMock }))
vi.mock('../../../services/knowledge-api', () => ({ knowledgeApi: kbMock }))
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (k: string) => k }) }))
vi.mock('@copilotkit/react-core/v2', () => ({
  useAgent: (opts?: { agentId?: string }) => { kit.agentIds.push(opts?.agentId); return { agent: kit.agent } },
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

import { useFlowStore } from '../../FlowCanvas/store/useFlowStore'
import CopilotGenerateBridge, {
  COPILOT_GENERATE_AGENT_ID,
  COPILOT_GENERATE_NOTIFICATION_ID,
  describeCopilotRunError,
  isCopilotAbort,
  newCopilotGenerateNotificationId,
  requestCopilotGeneration,
} from '../CopilotGenerateBridge'

const t = (k: string) => k

const node = (id: string, nodeType: string, label: string, config: Record<string, unknown> = {}) => ({
  id, type: 'control', position: { x: 0, y: 0 }, data: { label, nodeType, componentId: nodeType, config },
})
const edge = (source: string, target: string, branch?: string) => ({
  id: `e-${source}-${target}-${branch ?? ''}`, source, target, type: 'flow', data: branch ? { branch } : {},
})
/** O que as ferramentas do copiloto deixam no store ao fim da geração. */
function buildOnCanvas(nodes: ReturnType<typeof node>[], edges: ReturnType<typeof edge>[]) {
  useFlowStore.setState({ nodes: nodes as never, edges: edges as never })
}
const summary = (content: string) => { kit.agent.messages = [{ role: 'assistant', content }] }

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
  useFlowStore.setState({ nodes: [], edges: [] })
  kbMock.listBases.mockResolvedValue([])
  kit.subscribers = []
  kit.agentIds = []
  kit.agent.messages = []
  kit.agent.threadId = 'thread-antiga'
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

  it('"Gerar com IA" usa o agente próprio e começa uma conversa limpa a cada geração (RCHFLW-95)', async () => {
    kit.runAgent.mockResolvedValue(undefined)

    await act(async () => { requestCopilotGeneration('monte um fluxo', 'toast-1') })

    // Não é o agente do chat lateral ('archflow'): o histórico dele não entra na geração.
    expect(kit.agentIds).toContain(COPILOT_GENERATE_AGENT_ID)
    expect(kit.agentIds).not.toContain('archflow')
    expect(kit.agent.setMessages).toHaveBeenCalledWith([])
    expect(kit.agent.setMessages.mock.invocationCallOrder[0])
      .toBeLessThan(kit.agent.addMessage.mock.invocationCallOrder[0])
    expect(kit.agent.threadId).not.toBe('thread-antiga')
  })

  it('ao terminar, mostra o resumo do copiloto no lugar do toast de "montando"', async () => {
    kit.runAgent.mockImplementation(async () => {
      buildOnCanvas([node('a', 'input', 'Entrada'), node('b', 'llm-chat', 'Resumo'), node('c', 'output', 'Saída')],
        [edge('a', 'b'), edge('b', 'c')])
      kit.agent.messages = [
        { role: 'user', content: 'monte um fluxo' },
        { role: 'assistant', content: 'Montei 3 nós ligados: Entrada, Resumo e Saída.' },
      ]
    })

    await act(async () => { requestCopilotGeneration('monte um fluxo', 'toast-2') })

    expect(notificationsMock.hide).toHaveBeenCalledWith('toast-2')
    expect(notificationsMock.show).toHaveBeenCalledWith(expect.objectContaining({
      color: 'teal', title: 'editor.ai.done', message: 'Montei 3 nós ligados: Entrada, Resumo e Saída.',
    }))
  })

  // RCHFLW-116: o agente desistiu de corrigir a condição (ou ela já estava no fluxo) — "fluxo montado" seria silêncio.
  it('fluxo montado com condição que o motor recusa: aviso laranja que não some, no lugar do "montado"', async () => {
    kit.runAgent.mockImplementation(async () => {
      buildOnCanvas([node('a', 'input', 'Receber Ticket'),
        node('c', 'condition', 'É Urgente?', { conditionExpression: 'ticket.urgente == true' })], [edge('a', 'c')])
      kit.agent.messages = [{ role: 'assistant', content: 'Montei a triagem.' }]
    })

    try {
      await act(async () => { requestCopilotGeneration('monte a triagem', 'toast-3') })
    } finally {
      useFlowStore.setState({ nodes: [], edges: [] })
    }

    expect(notificationsMock.hide).toHaveBeenCalledWith('toast-3')
    expect(notificationsMock.show).toHaveBeenCalledWith(expect.objectContaining({
      color: 'orange', autoClose: false, title: 'editor.ai.doneInvalid',
      message: 'Montei a triagem. — editor.notif.runBlockedInvalidExpression',
    }))
    expect(notificationsMock.show).not.toHaveBeenCalledWith(expect.objectContaining({ title: 'editor.ai.done' }))
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

describe('CopilotGenerateBridge — "montado" só com o fluxo no canvas', () => {
  // RCHFLW-150: o modelo abria com createFlow (recusado) ou showStatus (encerra o laço); 0 nós e o toast teal.
  it('canvas vazio ao fim da geração: aviso de que nada foi montado, nunca "Fluxo montado"', async () => {
    kit.runAgent.mockImplementation(async () => { summary('Fluxo de triagem montado com sucesso!') })

    await act(async () => { requestCopilotGeneration('Triagem de pedidos de reembolso', 'toast-vazio') })

    expect(notificationsMock.hide).toHaveBeenCalledWith('toast-vazio')
    expect(notificationsMock.show).toHaveBeenCalledWith(expect.objectContaining({
      color: 'orange', title: 'editor.ai.doneEmpty',
    }))
    expect(notificationsMock.show).not.toHaveBeenCalledWith(expect.objectContaining({ title: 'editor.ai.done' }))
  })

  it('canvas vazio e o modelo sem texto: a mensagem diz o que fazer', async () => {
    kit.runAgent.mockResolvedValue(undefined)
    await act(async () => { requestCopilotGeneration('asdf qwerty', 'toast-asdf') })
    expect(notificationsMock.show).toHaveBeenCalledWith(expect.objectContaining({
      title: 'editor.ai.doneEmpty', message: 'editor.ai.doneEmptyMsg',
    }))
  })

  it('fluxo aberto que a geração não mudou: "não alterou", com o texto do modelo (pode ser uma pergunta)', async () => {
    buildOnCanvas([node('a', 'input', 'Entrada'), node('b', 'output', 'Saída')], [edge('a', 'b')])
    kit.runAgent.mockImplementation(async () => { summary('Qual base de conhecimento devo usar?') })

    await act(async () => { requestCopilotGeneration('ajuste a busca', 'toast-igual') })

    expect(notificationsMock.show).toHaveBeenCalledWith(expect.objectContaining({
      color: 'orange', title: 'editor.ai.doneUnchanged', message: 'Qual base de conhecimento devo usar?',
    }))
  })

  // RCHFLW-189 (1): 7 addNode e 1 connectNodes saíam como "montado"; o servidor recusa o nó solto.
  it('nós soltos: "ainda não executa", com o aviso do canvas', async () => {
    kit.runAgent.mockImplementation(async () => {
      buildOnCanvas([node('a', 'input', 'Entrada'), node('b', 'llm-chat', 'Roteia Assunto'),
        node('c', 'approval', 'Aprovação Humana')], [edge('a', 'b')])
      summary('Montei o roteamento.')
    })

    await act(async () => { requestCopilotGeneration('roteamento', 'toast-solto') })

    expect(notificationsMock.show).toHaveBeenCalledWith(expect.objectContaining({
      color: 'orange', title: 'editor.ai.doneInvalid',
      message: 'Montei o roteamento. — editor.disconnectedWarning.message',
    }))
  })

  // RCHFLW-152: knowledgeBaseId 'default' saía com o toast de sucesso e a execução falhava.
  it('base de conhecimento que não existe: "ainda não executa", nomeando o nó', async () => {
    kbMock.listBases.mockResolvedValue([{ id: 'kb-1', name: 'Políticas da loja' }])
    kit.runAgent.mockImplementation(async () => {
      buildOnCanvas([node('a', 'input', 'Pergunta'), node('r', 'rag', 'Busca FAQ', {
        knowledgeBaseId: 'default', llmProvider: 'openai',
      })], [edge('a', 'r')])
      summary('Montei o FAQ.')
    })

    await act(async () => { requestCopilotGeneration('FAQ', 'toast-kb') })

    expect(notificationsMock.show).toHaveBeenCalledWith(expect.objectContaining({
      color: 'orange', title: 'editor.ai.doneInvalid',
      message: 'Montei o FAQ. — "Busca FAQ": editor.properties.fields.knowledgeBaseMissing',
    }))
  })

  it('base que existe: segue para o "montado"', async () => {
    kbMock.listBases.mockResolvedValue([{ id: 'kb-1', name: 'Políticas da loja' }])
    kit.runAgent.mockImplementation(async () => {
      buildOnCanvas([node('a', 'input', 'Pergunta'), node('r', 'rag', 'Busca FAQ', { knowledgeBaseId: 'kb-1' })],
        [edge('a', 'r')])
      summary('Montei o FAQ.')
    })
    await act(async () => { requestCopilotGeneration('FAQ', 'toast-kb-ok') })
    expect(notificationsMock.show)
      .toHaveBeenCalledWith(expect.objectContaining({ color: 'teal', title: 'editor.ai.done' }))
  })

  // RCHFLW-155: alta → aprovação e default → resposta automática; a decisão que falha (nula) cai no default.
  it('default do switch na resposta automática com a aprovação noutro ramo: aviso do caminho humano', async () => {
    kit.runAgent.mockImplementation(async () => {
      buildOnCanvas([
        node('in', 'input', 'E-mail'),
        node('d', 'decision', 'Urgência', {
          provider: 'llm', primary: 'urgencia',
          questions: {
            urgencia: { type: 'choice', instructions: 'Qual a urgência?', criteria: { alta: 'a', baixa: 'b' } },
          },
        }),
        node('sw', 'switch', 'Roteia', { switchExpression: '${d.decision}', switchCases: 'alta' }),
        node('ap', 'approval', 'Aprovação Coordenador'),
        node('auto', 'llm-chat', 'Resposta Automática', { userMessage: '${in}' }),
      ], [edge('in', 'd'), edge('d', 'sw'), edge('sw', 'ap', 'alta'), edge('sw', 'auto', 'default')])
      summary('Caso default (baixa e média) vai para a resposta automática.')
    })

    await act(async () => { requestCopilotGeneration('urgência', 'toast-humano') })

    expect(notificationsMock.show).toHaveBeenCalledWith(expect.objectContaining({
      color: 'orange', title: 'editor.ai.doneHumanPath',
      message: 'Caso default (baixa e média) vai para a resposta automática. — editor.ai.failedDecisionMissesHuman',
    }))
    expect(notificationsMock.show).not.toHaveBeenCalledWith(expect.objectContaining({ title: 'editor.ai.done' }))
  })

  const urgencia = (cases: string) => [
    node('in', 'input', 'E-mail'),
    node('d', 'decision', 'Urgência', {
      provider: 'llm', primary: 'urgencia',
      questions: {
        urgencia: { type: 'choice', instructions: 'Qual a urgência?', criteria: { alta: 'a', media: 'm', baixa: 'b' } },
      },
    }),
    node('sw', 'switch', 'Roteia', { switchExpression: '${d.decision}', switchCases: cases }),
    node('ap', 'approval', 'Aprovação Coordenador'),
    node('auto', 'llm-chat', 'Resposta Automática', { userMessage: '${in}' }),
  ]

  // Revisão: o default ligado à aprovação ao lado do default antigo na resposta. O motor segue os dois — a falha
  // abre a aprovação E responde ao cliente — e o "some" do aviso deixava sair o "Fluxo montado".
  it('default na aprovação E na resposta automática (o replay do RCHFLW-149): aviso do caminho humano', async () => {
    kit.runAgent.mockImplementation(async () => {
      buildOnCanvas(urgencia('alta\nmedia\nbaixa'), [edge('in', 'd'), edge('d', 'sw'), edge('sw', 'ap', 'alta'),
        edge('sw', 'auto', 'default'), edge('sw', 'ap', 'default'), edge('sw', 'auto', 'media'),
        edge('sw', 'auto', 'baixa')])
      summary('alta e default vão para a aprovação.')
    })

    await act(async () => { requestCopilotGeneration('urgência', 'toast-leque') })

    expect(notificationsMock.show).toHaveBeenCalledWith(expect.objectContaining({
      color: 'orange', title: 'editor.ai.doneHumanPath',
    }))
    expect(notificationsMock.show).not.toHaveBeenCalledWith(expect.objectContaining({ title: 'editor.ai.done' }))
  })

  // O exec-3ca41eed: o caso baixa sem aresta termina COMPLETED sem resposta; o resumo dizia que ia para a resposta.
  it('caso declarado do switch sem aresta: aviso dos casos sem ligação, nunca "Fluxo montado"', async () => {
    kit.runAgent.mockImplementation(async () => {
      buildOnCanvas(urgencia('alta\nmedia\nbaixa'), [edge('in', 'd'), edge('d', 'sw'), edge('sw', 'ap', 'alta'),
        edge('sw', 'auto', 'media'), edge('sw', 'ap', 'default')])
      summary('media e baixa vão para a Resposta Automática.')
    })

    await act(async () => { requestCopilotGeneration('urgência', 'toast-casos') })

    expect(notificationsMock.show).toHaveBeenCalledWith(expect.objectContaining({
      color: 'orange', title: 'editor.ai.doneCasesWithoutEdge',
      message: 'media e baixa vão para a Resposta Automática. — editor.ai.casesWithoutEdge',
    }))
    expect(notificationsMock.show).not.toHaveBeenCalledWith(expect.objectContaining({ title: 'editor.ai.done' }))
  })
})
