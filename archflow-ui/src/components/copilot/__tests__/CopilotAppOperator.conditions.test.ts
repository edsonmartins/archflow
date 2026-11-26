/**
 * RCHFLW-116 — o "Gerar com IA" montou `ticket.urgente == true` depois de um llm-chat: sem ${}, lendo um campo que
 * nenhum passo produz. O pedido urgente seguia o ramo false, sem erro nem aviso. E "adicione uma aprovação humana
 * ANTES da resposta do LLM" pôs a aprovação depois.
 *
 * <p>As ferramentas são as que o CopilotAppOperator registra; o FlowCanvas e o useFlowStore são os reais. O laço é o
 * do CopilotKit com um backend falso e determinístico (o mesmo SSE do AgUiAgentController), como em
 * CopilotAppOperator.configure.test.ts, de onde vem a montagem.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { waitFor } from '@testing-library/dom'
import { HttpAgent } from '@ag-ui/client'

type Tool = { name: string; followUp?: boolean; handler: (args: Record<string, unknown>) => Promise<unknown> }

const tools = vi.hoisted(() => new Map<string, Tool>())
const contexts = vi.hoisted(() => new Map<string, unknown>())
const notificationsMock = vi.hoisted(() => ({ show: vi.fn(), hide: vi.fn(), update: vi.fn(), clean: vi.fn() }))
// O modal de confirmação (Mantine) fica fora: o teste responde por ele. Padrão: o usuário confirma.
const confirmRemoval = vi.hoisted(() => vi.fn(async (_t: unknown, _node: { id: string; label: string }) => true))

vi.mock('@copilotkit/react-core/v2', () => ({
  useAgentContext: ({ description, value }: { description: string; value: unknown }) => {
    contexts.set(description, value)
  },
  useFrontendTool: (tool: Tool) => { tools.set(tool.name, tool) },
}))
vi.mock('@mantine/notifications', () => ({ notifications: notificationsMock }))
vi.mock('../confirmCopilotRemoval', () => ({ confirmCopilotNodeRemoval: confirmRemoval }))
vi.mock('../../../services/api', () => ({
  api: { post: vi.fn() },
  workflowApi: { list: vi.fn(async () => []), create: vi.fn() },
  executionApi: {},
}))
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string) => k,
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))

import { CopilotKitCoreReact } from '@copilotkit/react-core/v2/context'
import CopilotAppOperator from '../CopilotAppOperator'
import { FlowCanvas } from '../../FlowCanvas/FlowCanvas'
import { useFlowStore } from '../../FlowCanvas/store/useFlowStore'
import type { WorkflowData } from '../../FlowCanvas/types'
import { hasRunBlockers, runBlockers } from '../../FlowCanvas/runReadiness'
import { canvasToDocumentSteps } from '../../FlowCanvas/workflowSerde'
import triagemFlow from './triagemPelaRespostaDaDecisao.json'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const roots: Root[] = []
const containers: HTMLElement[] = []

beforeEach(() => {
  vi.clearAllMocks()
  tools.clear()
  contexts.clear()
  ;(globalThis as any).ResizeObserver = ResizeObserverStub
  ;(globalThis as any).DOMMatrixReadOnly ??= class {
    m22 = 1
    constructor() {}
  }
  ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
  window.matchMedia ??= ((q: string) => ({
    matches: false, media: q, onchange: null,
    addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
    dispatchEvent: () => false,
  })) as any
  useFlowStore.setState({
    nodes: [], edges: [], canvasApi: null, pendingCanvasOps: [], pendingCanvasTarget: null, isExecuting: false,
    selectedNodeId: null, selectedNodeData: null,
  })
})

afterEach(async () => {
  for (const r of roots.splice(0)) await act(async () => { r.unmount() })
  for (const c of containers.splice(0)) c.remove()
})

async function mount(element: ReturnType<typeof h>): Promise<HTMLElement> {
  const container = document.createElement('div')
  document.body.appendChild(container)
  containers.push(container)
  const root = createRoot(container)
  roots.push(root)
  await act(async () => { root.render(element) })
  return container
}

const operator = (path = '/editor/wf-1') => mount(h(MemoryRouter, { initialEntries: [path] }, h(CopilotAppOperator)))

const canvas = (workflow: WorkflowData = { steps: [], connections: [] }) =>
  mount(h(MantineProvider, null, h('div', { style: { width: '800px', height: '600px' } },
    h(FlowCanvas, { initialWorkflow: workflow, workflowId: 'wf-1' }))))

/** O editor aberto, com o copiloto montado ao lado (como no AppLayout). */
async function mountEditor(workflow?: WorkflowData) {
  await operator()
  const el = await canvas(workflow)
  await waitFor(() => expect(useFlowStore.getState().canvasApi).not.toBeNull())
  return el
}

async function call<T = Record<string, unknown>>(name: string, args: Record<string, unknown>): Promise<T> {
  let result: unknown
  await act(async () => { result = await tools.get(name)!.handler(args) })
  return result as T
}

const step = (id: string, type: string, config: Record<string, unknown> = {}) => ({
  id, type, componentId: type, label: id, category: 'agent' as const, position: { x: 0, y: 0 }, config,
})

const configOf = (id: string) => useFlowStore.getState().nodes.find(n => n.id === id)?.data.config
/** As regras de classificar e ramificar que o agente recebe no contexto (EXPRESSION_RULES). */
const rules = () => [...contexts.entries()].find(([d]) => d.startsWith('How to classify and branch'))?.[1]
const redToasts = () => notificationsMock.show.mock.calls.filter(([n]) => (n as { color?: string }).color === 'red')

// ── Laço real do CopilotKit com backend falso ──

type AgUiMessage = {
  id: string
  role: string
  content?: string
  toolCallId?: string
  toolCalls?: { id: string; function: { name: string; arguments: string } }[]
}
type RunBody = { messages: AgUiMessage[]; tools: { name: string }[] }
type ModelCall = { name: string; args: Record<string, unknown> }

function sse(events: Record<string, unknown>[]): Response {
  const text = events.map(e => `data: ${JSON.stringify(e)}\n\n`).join('')
  return new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(text))
      controller.close()
    },
  }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
}

async function runCopilot(prompt: string, model: (body: RunBody, turn: number) => ModelCall[] | string) {
  const bodies: RunBody[] = []
  let callSeq = 0
  const fetch = vi.fn(async (_url: string, init?: RequestInit) => {
    const body = JSON.parse(String(init?.body)) as RunBody
    bodies.push(body)
    const reply = model(body, bodies.length)
    const messageId = `msg-${bodies.length}`
    const events: Record<string, unknown>[] = [
      { type: 'RUN_STARTED', threadId: 't', runId: `r${bodies.length}` },
      { type: 'TEXT_MESSAGE_START', messageId, role: 'assistant' },
    ]
    if (typeof reply === 'string') events.push({ type: 'TEXT_MESSAGE_CONTENT', messageId, delta: reply })
    events.push({ type: 'TEXT_MESSAGE_END', messageId })
    if (typeof reply !== 'string') {
      for (const c of reply) {
        const toolCallId = `call-${++callSeq}`
        events.push(
          { type: 'TOOL_CALL_START', toolCallId, toolCallName: c.name, parentMessageId: messageId },
          { type: 'TOOL_CALL_ARGS', toolCallId, delta: JSON.stringify(c.args) },
          { type: 'TOOL_CALL_END', toolCallId })
      }
    }
    events.push({ type: 'RUN_FINISHED', threadId: 't', runId: `r${bodies.length}` })
    return sse(events)
  })
  const agent = new HttpAgent({ url: '/ag-ui/agent', fetch: fetch as unknown as typeof window.fetch })
  const core = new CopilotKitCoreReact({
    agents__unsafe_dev_only: { archflow: agent as any },
    tools: [...tools.values()] as any,
  })
  agent.addMessage({ id: 'u1', role: 'user', content: prompt })
  await act(async () => { await core.runAgent({ agent: agent as any }) })
  return bodies
}

/** Pelo histórico, o id que cada addNode devolveu, por rótulo (como o modelo o leria). */
function idsByLabel(messages: AgUiMessage[]): Map<string, string> {
  const byLabel = new Map<string, string>()
  for (const m of messages) {
    for (const c of m.toolCalls ?? []) {
      if (c.function.name !== 'addNode') continue
      const result = messages.find(r => r.role === 'tool' && r.toolCallId === c.id)
      if (!result?.content) continue
      byLabel.set((JSON.parse(c.function.arguments) as { label: string }).label,
        (JSON.parse(result.content) as { id: string }).id)
    }
  }
  return byLabel
}


/** O resultado (role:tool) de cada chamada, na ordem do histórico, com o nome da ferramenta. */
function toolResults(messages: AgUiMessage[]): { name: string; args: Record<string, unknown>; result: any }[] {
  const out: { name: string; args: Record<string, unknown>; result: any }[] = []
  for (const m of messages) {
    for (const c of m.toolCalls ?? []) {
      const r = messages.find(x => x.role === 'tool' && x.toolCallId === c.id)
      out.push({ name: c.function.name, args: JSON.parse(c.function.arguments), result: r?.content ? JSON.parse(r.content) : undefined })
    }
  }
  return out
}

const edgesOf = () => useFlowStore.getState().getCanvasSnapshot().connections
  .map(e => [e.sourceId, e.targetId, e.branch ?? null])

describe('RCHFLW-116 — a condição gerada é recusada com a forma certa, e o agente corrige', () => {
  it('a condição do defeito volta ao agente com o motivo; a corrigida entra, ligada nos ramos true/false', async () => {
    await mountEditor()

    const bodies = await runCopilot('triagem: mandar para humano quando for urgente', (body, turn) => {
      if (turn === 1) {
        return [
          { name: 'addNode', args: { nodeType: 'input', label: 'Receber Ticket' } },
          { name: 'addNode', args: { nodeType: 'llm-chat', label: 'Classificar Assunto',
            config: { systemPrompt: 'Responda URGENTE se o pedido for urgente; senão NORMAL.' } } },
          { name: 'addNode', args: { nodeType: 'condition', label: 'É Urgente?',
            config: { conditionExpression: 'ticket.urgente == true' } } },
          { name: 'addNode', args: { nodeType: 'output', label: 'Escalar para Humano' } },
          { name: 'addNode', args: { nodeType: 'output', label: 'Responder com LLM' } },
        ]
      }
      if (turn === 2) {
        const refused = toolResults(body.messages).find(r => r.args.label === 'É Urgente?')!.result
        expect(refused.ok).toBe(false)
        expect(refused.error).toContain('conditionExpression not applied')
        expect(refused.error).toContain('"ticket.urgente" is not a value')
        // A recusa traz os ids válidos: é com eles que o agente reescreve a referência.
        expect(refused.nodes.map((n: { label: string }) => n.label)).toContain('Classificar Assunto')
        const ids = idsByLabel(body.messages)
        return [{ name: 'addNode', args: { nodeType: 'condition', label: 'É Urgente?',
          config: { conditionExpression: `\${${ids.get('Classificar Assunto')}} contains 'URGENTE'` } } }]
      }
      if (turn === 3) {
        const ids = idsByLabel(body.messages)
        return [
          { name: 'connectNodes', args: { sourceId: ids.get('Receber Ticket'), targetId: ids.get('Classificar Assunto') } },
          { name: 'connectNodes', args: { sourceId: ids.get('Classificar Assunto'), targetId: ids.get('É Urgente?') } },
          { name: 'connectNodes', args: { sourceId: ids.get('É Urgente?'), targetId: ids.get('Responder com LLM'), branch: 'false' } },
          { name: 'connectNodes', args: { sourceId: ids.get('É Urgente?'), targetId: ids.get('Escalar para Humano'), branch: 'true' } },
        ]
      }
      return 'Triagem montada.'
    })

    expect(bodies).toHaveLength(4)
    const ids = idsByLabel(bodies[3].messages)
    expect(configOf(ids.get('É Urgente?')!)).toEqual({ conditionExpression: `\${${ids.get('Classificar Assunto')}} contains 'URGENTE'` })
    // O ramo é o pedido, não a ordem das ligações: a primeira saída da condição é a do false.
    expect(edgesOf()).toEqual(expect.arrayContaining([
      [ids.get('É Urgente?'), ids.get('Escalar para Humano'), 'true'],
      [ids.get('É Urgente?'), ids.get('Responder com LLM'), 'false'],
    ]))
    // Só um nó de condição: a recusa não deixou um nó vazio para trás.
    expect(useFlowStore.getState().nodes.filter(n => n.data.nodeType === 'condition')).toHaveLength(1)
    // O fluxo gerado passa pela mesma validação do Executar.
    expect(runBlockers(useFlowStore.getState().getCanvasSnapshot().steps).invalidExpressions).toEqual([])
  })

  it('configureNode recusa ler um campo da saída de texto do LLM e um passo pelo rótulo; renomear segue livre', async () => {
    await mountEditor({
      steps: [step('llm', 'llm-chat'), step('cond', 'condition', { conditionExpression: 'urgente' }),
        step('rota', 'switch', { switchCases: 'a\nb' })],
      connections: [],
    })

    const campo = await call<{ ok: boolean; error: string }>('configureNode', {
      nodeId: 'cond', config: { conditionExpression: '${llm.urgente} == true' },
    })
    const rotulo = await call<{ ok: boolean; error: string }>('configureNode', {
      nodeId: 'rota', config: { switchExpression: '${Classificar.categoria}' },
    })
    const renomear = await call<{ ok: boolean }>('configureNode', { nodeId: 'cond', label: 'É urgente?' })

    expect(campo.ok).toBe(false)
    expect(campo.error).toContain('"llm" is an llm-chat node, whose output is the reply TEXT')
    expect(rotulo.ok).toBe(false)
    expect(rotulo.error).toContain('no node in this flow has the id "Classificar"')
    expect(configOf('cond')).toEqual({ conditionExpression: 'urgente' })
    expect(configOf('rota')).toEqual({ switchCases: 'a\nb' })
    // O patch não toca a expressão: a recusa não pode impedir o rótulo.
    expect(renomear.ok).toBe(true)
  })

  it('campo aninhado da entrada: a recusa manda declarar a chave na Entrada, e declarada ela passa', async () => {
    await mountEditor({
      steps: [step('receber', 'input'), step('cond', 'condition', { conditionExpression: '${input} != \'\'' })],
      connections: [],
    })
    const expressao = { nodeId: 'cond', config: { conditionExpression: '${ticket.urgente} == true' } }

    const antes = await call<{ ok: boolean; error: string }>('configureNode', expressao)
    // O agente segue o que a recusa diz; sem os ioFields entrarem na regra, ficaria preso recusado de novo.
    const declarar = await call<{ ok: boolean }>('configureNode', { nodeId: 'receber', config: { ioFields: ['ticket'] } })
    const depois = await call<{ ok: boolean }>('configureNode', expressao)

    expect(antes.ok).toBe(false)
    expect(antes.error).toContain('{"ioFields": ["ticket"]}')
    expect(declarar.ok).toBe(true)
    expect(depois.ok).toBe(true)
    expect(configOf('cond')).toEqual({ conditionExpression: '${ticket.urgente} == true' })
    // E o Executar do editor aceita o mesmo fluxo.
    const steps = useFlowStore.getState().getCanvasSnapshot().steps
      .map(s => ({ id: s.id, type: s.type, componentId: s.componentId, label: s.label, config: s.config }))
    expect(runBlockers(steps).invalidExpressions).toEqual([])
  })

  it('decisão: o ramo da aresta vem do connectNodes, e um ramo que o nó não tem é recusado com os válidos', async () => {
    await mountEditor({
      steps: [step('triagem', 'decision', { questions: { prioridade: { type: 'choice', criteria: { urgente: 'u', normal: 'n' } } } }),
        step('humano', 'approval'), step('responder', 'llm-chat')],
      connections: [],
    })

    const errado = await call<{ ok: boolean; error: string; branches: string[] }>('connectNodes', {
      sourceId: 'triagem', targetId: 'humano', branch: 'urgente',
    })
    const escalar = await call('connectNodes', { sourceId: 'triagem', targetId: 'humano', branch: 'ESCALATE' })
    const auto = await call('connectNodes', { sourceId: 'triagem', targetId: 'responder', branch: 'AUTO' })
    const semRamo = await call<{ ok: boolean; error: string }>('connectNodes', {
      sourceId: 'responder', targetId: 'humano', branch: 'true',
    })

    expect(errado).toEqual(expect.objectContaining({ ok: false, branches: ['AUTO', 'REVIEW', 'ESCALATE'] }))
    expect(errado.error).toContain('branch "urgente" is not an output of node triagem')
    expect(escalar).toEqual(expect.objectContaining({ ok: true, branch: 'ESCALATE' }))
    expect(auto).toEqual(expect.objectContaining({ ok: true, branch: 'AUTO' }))
    expect(semRamo.error).toBe('node responder does not branch: connect it without a branch')
    expect(edgesOf()).toEqual([['triagem', 'humano', 'ESCALATE'], ['triagem', 'responder', 'AUTO']])
  })

  it('na fila (sem fluxo aberto), o formato é conferido e o ramo viaja até a drenagem', async () => {
    await operator()
    const bad = await call<{ ok: boolean; error: string }>('addNode', {
      nodeType: 'condition', config: { conditionExpression: 'ticket.urgente == true' },
    })
    const cond = await call<{ id: string }>('addNode', {
      nodeType: 'condition', config: { conditionExpression: '${outroFluxo.campo} == 1' },
    })
    const fim = await call<{ id: string }>('addNode', { nodeType: 'output' })
    const ramoErrado = await call<{ ok: boolean; branches: string[] }>('connectNodes', {
      sourceId: cond.id, targetId: fim.id, branch: 'AUTO',
    })
    const ligado = await call('connectNodes', { sourceId: cond.id, targetId: fim.id, branch: 'false' })

    expect(bad.ok).toBe(false)
    // Sem destino, o próximo canvas pode ser um fluxo existente: o passo citado pode estar nele — só o formato vale.
    expect(useFlowStore.getState().pendingCanvasOps.filter(op => op.kind === 'add').map(op => op.id))
      .toEqual([cond.id, fim.id])
    expect(ramoErrado).toEqual(expect.objectContaining({ ok: false, branches: ['true', 'false'] }))
    expect(ligado).toEqual(expect.objectContaining({ ok: true, queued: true, branch: 'false' }))

    await canvas()
    await waitFor(() => expect(useFlowStore.getState().nodes).toHaveLength(2))
    await waitFor(() => expect(edgesOf()).toEqual([[cond.id, fim.id, 'false']]))
    expect(configOf(cond.id)).toEqual({ conditionExpression: '${outroFluxo.campo} == 1' })
    // Aberto o canvas, o Executar conhece os passos e recusa a referência que não existe neste fluxo.
    expect(runBlockers(useFlowStore.getState().getCanvasSnapshot().steps).invalidExpressions)
      .toEqual([expect.objectContaining({ id: cond.id, problem: 'UNKNOWN_STEP' })])
  })
})

describe('RCHFLW-116 — "adicione X antes de Y" insere entre o antecessor de Y e Y', () => {
  const triagem: WorkflowData = {
    steps: [step('receber', 'input'), step('urgente', 'condition', { conditionExpression: "${receber} contains 'URGENTE'" }),
      step('humano', 'output'), step('llm', 'llm-chat'), step('fim', 'output')],
    connections: [
      { id: 'e1', sourceId: 'receber', targetId: 'urgente', isErrorPath: false },
      { id: 'e2', sourceId: 'urgente', targetId: 'humano', isErrorPath: false, branch: 'true' },
      { id: 'e3', sourceId: 'urgente', targetId: 'llm', isErrorPath: false, branch: 'false' },
      { id: 'e4', sourceId: 'llm', targetId: 'fim', isErrorPath: false },
    ],
  }

  it('o caso real: a aprovação entra antes da resposta do LLM, no ramo em que o LLM estava', async () => {
    await mountEditor(triagem)

    const bodies = await runCopilot('adicione uma aprovação humana antes da resposta do LLM', (_body, turn) => {
      if (turn === 1) {
        return [{ name: 'addNode', args: { nodeType: 'approval', label: 'Aprovação humana', insertBefore: 'llm' } }]
      }
      return 'Aprovação inserida antes do LLM.'
    })

    expect(bodies).toHaveLength(2)
    const [added] = toolResults(bodies[1].messages)
    expect(added.result).toEqual(expect.objectContaining({ ok: true, insertedBefore: 'llm', rewiredEdges: 1 }))
    const aprovacao = added.result.id as string
    expect(edgesOf()).toEqual([
      ['receber', 'urgente', null],
      ['urgente', 'humano', 'true'],
      ['urgente', aprovacao, 'false'],
      ['llm', 'fim', null],
      [aprovacao, 'llm', null],
    ])
    expect(redToasts()).toEqual([])
  })

  it('id desconhecido e fluxo fechado são recusados, sem criar o nó', async () => {
    await mountEditor(triagem)
    const desconhecido = await call<{ ok: boolean; error: string }>('addNode', { nodeType: 'approval', insertBefore: 'nada' })
    expect(desconhecido).toEqual(expect.objectContaining({ ok: false, error: 'insertBefore: unknown node id nada' }))
    expect(useFlowStore.getState().nodes).toHaveLength(5)

    useFlowStore.setState({ canvasApi: null })
    const semCanvas = await call<{ ok: boolean; error: string }>('addNode', { nodeType: 'approval', insertBefore: 'llm' })
    expect(semCanvas).toEqual(expect.objectContaining({ ok: false, error: 'insertBefore needs the flow open in the editor' }))
    expect(useFlowStore.getState().pendingCanvasOps).toEqual([])
  })
})

/**
 * RCHFLW-116, 2ª rodada — no Chrome, "triagem de pedidos de suporte: classificar por assunto e mandar para um humano
 * quando for urgente; quando não for urgente, responder com um LLM" virou um decision SEM provider nem model (o
 * http-decisions padrão recusa sem model) ramificado por AUTO/ESCALATE (a confiança, não a urgência): os dois pedidos
 * escalaram. O LLM falso faz o que o real fez; as ferramentas recusam com o motivo, e o fluxo que sai é o documento
 * de triagemPelaRespostaDaDecisao.json — o mesmo que o TriagemPelaRespostaDaDecisaoTest (archflow-api) executa no
 * motor de verdade, mandando o URGENTE ao humano e o comum ao LLM.
 */
describe('RCHFLW-116 — o copiloto monta a triagem que ramifica pela RESPOSTA da decisão', () => {
  const PEDIDO = 'triagem de pedidos de suporte: classificar por assunto e mandar para um humano quando for urgente; '
    + 'quando não for urgente, responder com um LLM'
  const ASSUNTO = { type: 'choice', instructions: 'Qual o assunto do pedido?',
    criteria: { financeiro: 'cobrança, pagamento', suporte_tecnico: 'defeito, sistema fora do ar', outro: 'qualquer outro assunto' } }
  const URGENTE = { type: 'noul', instructions: 'O pedido é urgente?',
    criteria: { true: 'algo parado ou fora do ar, prazo vencendo', false: 'dúvida ou pedido que pode esperar' } }

  /** O documento salvo, com os ids gerados trocados pelos do arquivo compartilhado (pelo rótulo) e sem posição. */
  function documentoNormalizado(): unknown[] {
    const snapshot = useFlowStore.getState().getCanvasSnapshot()
    const slugs: Record<string, string> = Object.fromEntries((triagemFlow.steps as { id: string; label: string }[])
      .map(st => [st.label, st.id]))
    let texto = JSON.stringify(canvasToDocumentSteps(snapshot.steps, snapshot.connections)
      .map(({ position: _position, ...st }) => st))
    // Os ids mais longos primeiro: node-…-1 não pode comer o começo de node-…-10.
    for (const st of [...snapshot.steps].sort((a, b) => b.id.length - a.id.length)) {
      texto = texto.split(st.id).join(slugs[st.label] ?? st.id)
    }
    return JSON.parse(texto)
  }

  it('o decision sem provider/model e o ramo AUTO como caminho comum são recusados; o que sai roda', async () => {
    await mountEditor()

    const bodies = await runCopilot(PEDIDO, (body, turn) => {
      const ids = idsByLabel(body.messages)
      const results = toolResults(body.messages)
      if (turn === 1) {
        // O desenho que o modelo real montou: só o assunto, sem provider nem model.
        return [
          { name: 'addNode', args: { nodeType: 'input', label: 'Receber pedido' } },
          { name: 'addNode', args: { nodeType: 'decision', label: 'Triagem',
            config: { questions: { assunto: ASSUNTO }, primary: 'assunto' } } },
          { name: 'addNode', args: { nodeType: 'approval', label: 'Atendimento humano',
            config: { approvalTitle: 'Pedido urgente' } } },
          { name: 'addNode', args: { nodeType: 'llm-chat', label: 'Responder com LLM',
            config: { systemPrompt: 'Responda ao pedido do cliente em uma frase.' } } },
        ]
      }
      if (turn === 2) {
        const refused = results.find(r => r.args.label === 'Triagem')!.result
        expect(refused.ok).toBe(false)
        expect(refused.error).toContain('provider http-decisions (the default) refuses to run without "model"')
        expect(refused.error).toContain('"provider":"llm"')
        // Sem o nó recusado no canvas: corrigir não deixa um decision quebrado para trás.
        expect(useFlowStore.getState().canvasApi!.listNodes().map(n => n.nodeType)).toEqual(['input', 'approval', 'llm-chat'])
        return [{ name: 'addNode', args: { nodeType: 'decision', label: 'Triagem', config: {
          provider: 'llm', questions: { assunto: ASSUNTO, urgente: URGENTE }, primary: 'assunto', gate: ['urgente'],
        } } }]
      }
      if (turn === 3) {
        // O modelo ainda tenta o comum pelo AUTO, que o provider llm (teto 0,89) nunca alcança.
        return [{ name: 'connectNodes', args: { sourceId: ids.get('Triagem'), targetId: ids.get('Responder com LLM'),
          branch: 'AUTO' } }]
      }
      if (turn === 4) {
        const auto = results.at(-1)!.result
        expect(auto.ok).toBe(false)
        expect(auto.error).toContain('branch "AUTO" of decision')
        expect(auto.error).toContain('is never followed: its provider llm caps the confidence at 0.89')
        const d = ids.get('Triagem')
        return [{ name: 'addNode', args: { nodeType: 'condition', label: 'É urgente?', config: {
          conditionExpression: `\${${d}.answers.urgente.result} == true || \${${d}.route} == 'ESCALATE'`,
        } } }]
      }
      if (turn === 5) {
        // RCHFLW-120: o LLM do ramo comum responde à mensagem do cliente (a saída da Entrada), não ao JSON da
        // decisão que a condição repassa — a regra que o agente recebe no contexto.
        expect((rules() as string[]).join('\n')).toContain('{"userMessage":"${<inputNodeId>}"}')
        // A referência sozinha à Entrada dá a mensagem (texto digitado ou {"message": …} de API): nunca ".message",
        // que falhava com o texto digitado no Executar (validação no Chrome).
        expect((rules() as string[]).join('\n')).toContain('Use ${<inputNodeId>} alone (never ${<inputNodeId>.message})')
        return [
          { name: 'connectNodes', args: { sourceId: ids.get('Receber pedido'), targetId: ids.get('Triagem') } },
          { name: 'connectNodes', args: { sourceId: ids.get('Triagem'), targetId: ids.get('É urgente?') } },
          { name: 'connectNodes', args: { sourceId: ids.get('É urgente?'), targetId: ids.get('Atendimento humano'), branch: 'true' } },
          { name: 'connectNodes', args: { sourceId: ids.get('É urgente?'), targetId: ids.get('Responder com LLM'), branch: 'false' } },
          { name: 'configureNode', args: { nodeId: ids.get('Responder com LLM'),
            config: { userMessage: `\${${ids.get('Receber pedido')}}` } } },
        ]
      }
      return 'Triagem montada: urgente vai para o humano, o resto o LLM responde.'
    })

    expect(bodies).toHaveLength(6)
    // As ligações e a Mensagem do usuário da última rodada entraram todas.
    expect(toolResults(bodies[5].messages).slice(-5).map(r => r.result.ok)).toEqual([true, true, true, true, true])
    expect(documentoNormalizado()).toEqual(triagemFlow.steps)
    // O Executar do editor e o aviso do "Gerar com IA" aceitam o fluxo como saiu.
    const steps = useFlowStore.getState().getCanvasSnapshot().steps
    expect(hasRunBlockers(runBlockers(steps))).toBe(false)
  })

  it('configureNode: provider de LLM no lugar do de decisão e regras faltando são recusados; o problema antigo não', async () => {
    await mountEditor({
      // Um decision do usuário que já está sem model: renomear ou mexer noutro campo não trava por isso.
      steps: [step('dec', 'decision', { questions: { assunto: ASSUNTO } })],
      connections: [],
    })

    const llmProvider = await call<{ ok: boolean; error: string }>('configureNode', {
      nodeId: 'dec', config: { provider: 'openai', model: 'gpt-4o-mini' },
    })
    const semRegra = await call<{ ok: boolean; error: string }>('configureNode', {
      nodeId: 'dec', config: { provider: 'rules' },
    })
    const outroCampo = await call<{ ok: boolean }>('configureNode', { nodeId: 'dec', config: { onError: 'FAIL' } })
    const primaryErrado = await call<{ ok: boolean; error: string }>('configureNode', {
      nodeId: 'dec', config: { primary: 'urgente' },
    })

    expect(llmProvider.ok).toBe(false)
    expect(llmProvider.error).toContain('provider "openai" is not a decision provider')
    expect(semRegra.ok).toBe(false)
    expect(semRegra.error).toContain('provider rules has no rule for question "assunto"')
    expect(outroCampo.ok).toBe(true)
    expect(primaryErrado.ok).toBe(false)
    expect(primaryErrado.error).toContain('primary "urgente" is not one of the question ids')
    expect(configOf('dec')).toEqual({ questions: { assunto: ASSUNTO }, onError: 'FAIL' })
  })
})

/**
 * RCHFLW-120 — o LLM Chat depois de uma decisão/condição recebia o JSON da classificação. A "Mensagem do usuário"
 * (userMessage) que o agente grava passa pelas mesmas regras do Executar e do servidor: o que nunca funciona volta
 * ao agente com o motivo; a referência à Entrada entra.
 */
describe('RCHFLW-120 — userMessage do llm-chat: o agente grava, e o que não resolve é recusado', () => {
  const fluxo: WorkflowData = {
    steps: [step('receber', 'input'), step('classificar', 'llm-chat'), step('responder', 'llm-chat'),
      step('agente', 'agent')],
    connections: [],
  }

  it('configureNode e addNode recusam ${ sem fechar, passo inexistente e campo da saída de texto; a Entrada entra', async () => {
    await mountEditor(fluxo)

    const aberta = await call<{ ok: boolean; error: string }>('configureNode', {
      nodeId: 'responder', config: { userMessage: 'Cliente: ${receber' } })
    const rotulo = await call<{ ok: boolean; error: string }>('configureNode', {
      nodeId: 'responder', config: { userMessage: '${Receber.texto}' } })
    const campoDeTexto = await call<{ ok: boolean; error: string }>('addNode', {
      nodeType: 'llm-chat', config: { userMessage: '${classificar.resposta}' } })
    // O campo de texto de uma Entrada em objeto JSON (a forma que a regra indica para chamadas de API) é aceito.
    const campoDaEntrada = await call<{ ok: boolean }>('configureNode', {
      nodeId: 'responder', config: { userMessage: '${receber.message}' } })
    const certo = await call<{ ok: boolean }>('configureNode', {
      nodeId: 'responder', config: { userMessage: 'Cliente: ${receber}' } })
    // A chave só existe nos nós de chat: num agente MCP ela seria gravada sem que nada a lesse.
    const noAgente = await call<{ ok: boolean; allowedKeys: string[] }>('configureNode', {
      nodeId: 'agente', config: { userMessage: '${receber}' } })

    expect(aberta.ok).toBe(false)
    expect(aberta.error).toContain('userMessage not applied: "${receber" is not a reference')
    expect(rotulo.ok).toBe(false)
    expect(rotulo.error).toContain('no node in this flow has the id "Receber"')
    expect(campoDeTexto.ok).toBe(false)
    expect(campoDeTexto.error).toContain('"classificar" is an llm-chat node, whose output is the reply TEXT')
    expect(campoDaEntrada.ok).toBe(true)
    expect(certo.ok).toBe(true)
    expect(configOf('responder')).toEqual({ userMessage: 'Cliente: ${receber}' })
    expect(noAgente.ok).toBe(false)
    expect(noAgente.allowedKeys).not.toContain('userMessage')
    expect(useFlowStore.getState().nodes.filter(n => n.data.nodeType === 'llm-chat')).toHaveLength(2)
    expect(runBlockers(useFlowStore.getState().getCanvasSnapshot().steps).invalidExpressions).toEqual([])
  })

  it('na fila (sem fluxo aberto) só o formato é conferido: o passo citado pode estar no fluxo que vai abrir', async () => {
    await operator()
    const aberta = await call<{ ok: boolean; error: string }>('addNode', {
      nodeType: 'llm-chat', config: { userMessage: '${receber' } })
    const outroFluxo = await call<{ ok: boolean }>('addNode', {
      nodeType: 'llm-chat', config: { userMessage: '${receber.texto}' } })

    expect(aberta.ok).toBe(false)
    expect(aberta.error).toContain('is not a reference')
    expect(outroFluxo.ok).toBe(true)
  })
})

describe('RCHFLW-123 — o valor que vem no texto do cliente é extraído antes da comparação', () => {
  const PEDIDO = 'quando chegar um pedido de reembolso acima de 500 reais, pedir aprovação do gerente; abaixo, '
    + 'responder automaticamente'
  const VALOR = { type: 'choice', instructions: 'Qual o valor do reembolso pedido?', criteria: {
    acima_de_500: 'mais de R$ 500', ate_500: 'R$ 500 ou menos', sem_valor: 'o pedido não diz o valor' } }

  it('a regra diz que a referência sem valor falha o passo; o exemplo do reembolso entra e o Executar o aceita', async () => {
    await mountEditor()

    const bodies = await runCopilot(PEDIDO, (body, turn) => {
      const ids = idsByLabel(body.messages)
      if (turn === 1) {
        const regras = (rules() as string[]).join('\n')
        // O defeito do QA: ${entrada.valor} > 500 sobre texto livre dava false e o LLM aprovava sozinho.
        expect(regras).toContain('A condition/switch reference with no value at run time FAILS the step')
        expect(regras).toContain('extract it first: a decision node with a choice question about it')
        expect(regras).toContain('then a switch on ${<decisionId>.decision} whose default goes to the approval')
        // O llm que devolve o número não é caminho: «1.500» seria lido 1.5 e o reembolso de R$ 1.500 iria ao
        // automático; «R$ 900» e «1.500,00» falham o passo. A regra o proíbe em vez de ensiná-lo.
        expect(regras).toContain('Never ask an llm-chat for the number to compare it with >, >=, < or <=')
        expect(regras).not.toContain('answer only the number')
        expect(regras).not.toContain('${<llmId>} > 500')
        expect(regras).toContain('Never ${<inputNodeId>.valor} > 500 over free text.')
        // A decisão que falhou não tem answers: o switch em answers.<qid>.choice falharia o passo em vez de levar ao
        // humano. O caminho do humano lê decision (nulo → default) ou o portão do ESCALATE.
        expect(regras).toContain('The human path must read ${D.decision} or ${D.route}, never only ${D.answers.<qid>...}')
        expect(regras).toContain('Switch on ${D.decision} (null when the decision failed: it falls in the "default" edge)')
        expect(regras).toContain('never switch on ${D.answers.<qid>.choice}')
        expect(regras).toContain('give a condition on answers the "|| ${D.route} == \'ESCALATE\'" part')
        // A recusa fora do gate não escala: o || ESCALATE só cobre a pergunta que está no gate.
        expect(regras).toContain('a refused question only escalates when it is in "gate" (default: all), so put in '
          + '"gate" every question the condition reads')
        expect(regras).toContain('without a "default" edge a value with no case FAILS the step')
        expect(regras).not.toMatch(/switchExpression "\$\{D\.answers\./)
        return [
          { name: 'addNode', args: { nodeType: 'input', label: 'Receber pedido' } },
          { name: 'addNode', args: { nodeType: 'decision', label: 'Faixa de valor',
            config: { provider: 'llm', questions: { valor: VALOR }, primary: 'valor' } } },
          { name: 'addNode', args: { nodeType: 'approval', label: 'Aprovação do gerente',
            config: { approvalTitle: 'Reembolso acima de R$ 500' } } },
          { name: 'addNode', args: { nodeType: 'llm-chat', label: 'Resposta automática',
            config: { systemPrompt: 'Responda ao pedido de reembolso do cliente.' } } },
        ]
      }
      if (turn === 2) {
        return [{ name: 'addNode', args: { nodeType: 'switch', label: 'Por valor', config: {
          switchExpression: `\${${ids.get('Faixa de valor')}.decision}`, switchCases: 'ate_500' } } }]
      }
      if (turn === 3) {
        return [
          { name: 'connectNodes', args: { sourceId: ids.get('Receber pedido'), targetId: ids.get('Faixa de valor') } },
          { name: 'connectNodes', args: { sourceId: ids.get('Faixa de valor'), targetId: ids.get('Por valor') } },
          { name: 'connectNodes', args: { sourceId: ids.get('Por valor'), targetId: ids.get('Resposta automática'),
            branch: 'ate_500' } },
          { name: 'connectNodes', args: { sourceId: ids.get('Por valor'), targetId: ids.get('Aprovação do gerente'),
            branch: 'default' } },
          { name: 'configureNode', args: { nodeId: ids.get('Resposta automática'),
            config: { userMessage: `\${${ids.get('Receber pedido')}}` } } },
        ]
      }
      return 'Reembolso montado: o valor vem do texto pela decisão, e acima de 500 vai ao gerente.'
    })

    expect(bodies).toHaveLength(4)
    expect(toolResults(bodies[3].messages).map(r => r.result?.ok)).toEqual(Array(10).fill(true))
    const ids = idsByLabel(bodies[3].messages)
    expect(edgesOf()).toEqual(expect.arrayContaining([
      [ids.get('Por valor'), ids.get('Resposta automática'), 'ate_500'],
      [ids.get('Por valor'), ids.get('Aprovação do gerente'), 'default'],
    ]))
    // Nenhuma condição compara um campo da Entrada, e o Executar do editor aceita o fluxo como saiu.
    const steps = useFlowStore.getState().getCanvasSnapshot().steps
    expect(JSON.stringify(steps)).not.toContain('.valor}')
    expect(hasRunBlockers(runBlockers(steps))).toBe(false)
  })
})
