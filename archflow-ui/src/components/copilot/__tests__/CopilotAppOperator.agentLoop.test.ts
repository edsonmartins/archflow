/**
 * RCHFLW-76 — "Gerar com IA" criava os nós e não os ligava. Três defeitos somados: o addNode não devolvia
 * o id ao agente (o resultado da ferramenta virava ""), todas as ferramentas tinham {@code followUp:false}
 * (o CopilotKit não chamava o agente de novo depois do lote, então ele nunca via resultado nenhum) e o
 * backend descartava a mensagem do assistente com toolCalls na rodada seguinte.
 *
 * <p>Aqui roda o laço REAL: CopilotKitCoreReact + HttpAgent (os mesmos do AppLayout), com as ferramentas
 * que o CopilotAppOperator registra e o FlowCanvas real. Só o backend é falso: um fetch que responde em
 * SSE como o AgUiAgentController (TEXT_MESSAGE_START/END, TOOL_CALL_*, RUN_FINISHED) e faz o papel do
 * modelo — na 2ª rodada ele só sabe ligar os nós se o histórico trouxer, para cada chamada do addNode, o
 * id que ela devolveu.</p>
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
const notificationsMock = vi.hoisted(() => ({ show: vi.fn(), hide: vi.fn(), update: vi.fn(), clean: vi.fn() }))

vi.mock('@copilotkit/react-core/v2', () => ({
  useAgentContext: () => {},
  useFrontendTool: (tool: Tool) => { tools.set(tool.name, tool) },
}))
vi.mock('@mantine/notifications', () => ({ notifications: notificationsMock }))
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

// Pela entrada /context: a /v2 importa o CSS do chat, que o vitest não carrega. É a mesma classe.
import { CopilotKitCoreReact } from '@copilotkit/react-core/v2/context'
import CopilotAppOperator from '../CopilotAppOperator'
import { workflowApi } from '../../../services/api'
import { FlowCanvas } from '../../FlowCanvas/FlowCanvas'
import { useFlowStore } from '../../FlowCanvas/store/useFlowStore'

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
  })
})

afterEach(async () => {
  for (const r of roots.splice(0)) await act(async () => { r.unmount() })
  for (const c of containers.splice(0)) c.remove()
})

async function mount(element: ReturnType<typeof h>) {
  const container = document.createElement('div')
  document.body.appendChild(container)
  containers.push(container)
  const root = createRoot(container)
  roots.push(root)
  await act(async () => { root.render(element) })
}

/** O editor aberto num fluxo vazio, com o copiloto montado ao lado (como no AppLayout). */
async function mountEditor() {
  await mount(h(MemoryRouter, { initialEntries: ['/editor/wf-1'] }, h(CopilotAppOperator)))
  await mount(h(MantineProvider, null, h('div', { style: { width: '800px', height: '600px' } },
    h(FlowCanvas, { initialWorkflow: { steps: [], connections: [] }, workflowId: 'wf-1' }))))
  await waitFor(() => expect(useFlowStore.getState().canvasApi).not.toBeNull())
}

// ── Backend falso: o mesmo SSE que o AgUiAgentController emite ──

type AgUiMessage = {
  id: string
  role: string
  content?: string
  toolCallId?: string
  toolCalls?: { id: string; function: { name: string; arguments: string } }[]
}
type RunBody = { messages: AgUiMessage[]; tools: { name: string }[] }
type ModelCall = { name: string; args: Record<string, unknown> }
/** O "modelo": recebe o corpo do POST e devolve as chamadas de ferramenta (ou um texto, para encerrar). */
type FakeModel = (body: RunBody, turn: number) => ModelCall[] | string

function sse(events: Record<string, unknown>[]): Response {
  const text = events.map(e => `data: ${JSON.stringify(e)}\n\n`).join('')
  return new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(text))
      controller.close()
    },
  }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
}

function backend(model: FakeModel) {
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
      for (const call of reply) {
        const toolCallId = `call-${++callSeq}`
        events.push(
          { type: 'TOOL_CALL_START', toolCallId, toolCallName: call.name, parentMessageId: messageId },
          { type: 'TOOL_CALL_ARGS', toolCallId, delta: JSON.stringify(call.args) },
          { type: 'TOOL_CALL_END', toolCallId })
      }
    }
    events.push({ type: 'RUN_FINISHED', threadId: 't', runId: `r${bodies.length}` })
    return sse(events)
  })
  return { fetch, bodies }
}

/**
 * Pelo histórico, o id que cada addNode devolveu, por rótulo — como o modelo o leria: a chamada (com o
 * label nos argumentos) pareada pelo toolCallId com o resultado dela.
 */
function idsByLabel(messages: AgUiMessage[]): Map<string, string> {
  const byLabel = new Map<string, string>()
  for (const m of messages) {
    for (const call of m.toolCalls ?? []) {
      if (call.function.name !== 'addNode') continue
      const result = messages.find(r => r.role === 'tool' && r.toolCallId === call.id)
      if (!result?.content) continue
      const label = (JSON.parse(call.function.arguments) as { label: string }).label
      byLabel.set(label, (JSON.parse(result.content) as { id: string }).id)
    }
  }
  return byLabel
}

async function runCopilot(model: FakeModel) {
  const server = backend(model)
  const agent = new HttpAgent({ url: '/ag-ui/agent', fetch: server.fetch as unknown as typeof window.fetch })
  const core = new CopilotKitCoreReact({
    agents__unsafe_dev_only: { archflow: agent as any },
    tools: [...tools.values()] as any,
  })
  agent.addMessage({ id: 'u1', role: 'user', content: 'classificar, buscar e responder' })
  await act(async () => { await core.runAgent({ agent: agent as any }) })
  return server
}

const THREE_NODES: ModelCall[] = [
  { name: 'addNode', args: { nodeType: 'input', label: 'Entrada' } },
  { name: 'addNode', args: { nodeType: 'llm-chat', label: 'Classificar' } },
  { name: 'addNode', args: { nodeType: 'output', label: 'Resposta' } },
]

describe('CopilotAppOperator — o agente liga os nós que criou (laço real do CopilotKit)', () => {
  it('addNode devolve o id, o agente roda de novo e liga os nós com esses ids', async () => {
    await mountEditor()

    const server = await runCopilot((body) => {
      if (!body.messages.some(m => m.role === 'tool')) return THREE_NODES
      const ids = idsByLabel(body.messages)
      if (!body.messages.some(m => m.toolCalls?.some(c => c.function.name === 'connectNodes'))) {
        return [
          { name: 'connectNodes', args: { sourceId: ids.get('Entrada'), targetId: ids.get('Classificar') } },
          { name: 'connectNodes', args: { sourceId: ids.get('Classificar'), targetId: ids.get('Resposta') } },
        ]
      }
      return 'Fluxo montado: Entrada → Classificar → Resposta.'
    })

    // 1ª rodada: os nós; 2ª: as ligações (precisa dos ids); 3ª: o resumo, que encerra o laço.
    expect(server.bodies).toHaveLength(3)
    const ids = idsByLabel(server.bodies[1].messages)
    expect([...ids.keys()]).toEqual(['Entrada', 'Classificar', 'Resposta'])

    const { steps, connections } = useFlowStore.getState().getCanvasSnapshot()
    expect(steps.map(s => [s.id, s.label])).toEqual([
      [ids.get('Entrada'), 'Entrada'], [ids.get('Classificar'), 'Classificar'], [ids.get('Resposta'), 'Resposta'],
    ])
    expect(connections.map(e => [e.sourceId, e.targetId])).toEqual([
      [ids.get('Entrada'), ids.get('Classificar')], [ids.get('Classificar'), ids.get('Resposta')],
    ])
    // E o resultado de cada ligação também chega ao agente (é o que ele resume na 3ª rodada).
    const connectResults = server.bodies[2].messages
      .filter(m => m.role === 'tool')
      .map(m => JSON.parse(m.content!) as { ok: boolean })
      // O addNode também devolve {ok} (RCHFLW-78); os resultados de ligação são os que têm sourceId.
      .filter(r => 'sourceId' in r)
    expect(connectResults).toEqual([expect.objectContaining({ ok: true }), expect.objectContaining({ ok: true })])
    expect(notificationsMock.show).not.toHaveBeenCalledWith(expect.objectContaining({ color: 'red' }))
  })

  it('ids inventados no mesmo lote: o connect devolve o erro com os nós válidos e o agente corrige', async () => {
    await mountEditor()

    const server = await runCopilot((body, turn) => {
      if (turn === 1) {
        // Modelo apressado: cria e liga no mesmo turno, chutando os ids.
        return [...THREE_NODES.slice(0, 2),
          { name: 'connectNodes', args: { sourceId: 'node-1', targetId: 'node-2' } }]
      }
      if (turn === 2) {
        const failure = body.messages
          .filter(m => m.role === 'tool')
          .map(m => JSON.parse(m.content!) as { ok?: boolean; error?: string; nodes?: { id: string; label: string }[] })
          .find(r => r.ok === false)!
        expect(failure.error).toContain('unknown node id')
        const byLabel = new Map(failure.nodes!.map(n => [n.label, n.id]))
        return [{
          name: 'connectNodes',
          args: { sourceId: byLabel.get('Entrada'), targetId: byLabel.get('Classificar') },
        }]
      }
      return 'Pronto.'
    })

    expect(server.bodies).toHaveLength(3)
    const ids = idsByLabel(server.bodies[1].messages)
    const { connections } = useFlowStore.getState().getCanvasSnapshot()
    expect(connections.map(e => [e.sourceId, e.targetId])).toEqual([[ids.get('Entrada'), ids.get('Classificar')]])
  })

  it('o histórico reenviado tem o formato que o AgUiAgentController remonta (toolCalls + toolCallId)', async () => {
    await mountEditor()

    const server = await runCopilot((body) => (body.messages.some(m => m.role === 'tool') ? 'ok' : THREE_NODES))

    const [first, second] = server.bodies
    expect(first.tools.map(t => t.name)).toEqual(expect.arrayContaining(['addNode', 'connectNodes', 'createFlow']))
    // RCHFLW-153: o reportGap é do agente do "Gerar com IA"; o chat lateral ('archflow') não tem aviso que o mostre.
    expect(tools.has('reportGap')).toBe(true)
    expect(first.tools.map(t => t.name)).not.toContain('reportGap')
    const assistant = second.messages.find(m => m.role === 'assistant' && m.toolCalls?.length)!
    expect(assistant.toolCalls!.map(c => c.function.name)).toEqual(['addNode', 'addNode', 'addNode'])
    for (const call of assistant.toolCalls!) {
      const result = second.messages.find(m => m.role === 'tool' && m.toolCallId === call.id)
      expect(JSON.parse(result!.content!)).toEqual(expect.objectContaining({
        id: expect.stringMatching(/^node-/), queued: false,
      }))
    }
  })
})

describe('CopilotAppOperator — contrato das ferramentas de edição', () => {
  it('as ferramentas de que o agente depende pedem a rodada seguinte; as terminais não', async () => {
    await mountEditor()
    // reportGap também: com followUp:false, um lote só com ele encerraria o laço sem o resumo (RCHFLW-153).
    for (const name of ['createFlow', 'addNode', 'connectNodes', 'reportGap']) {
      expect(tools.get(name)!.followUp, name).not.toBe(false)
    }
    for (const name of ['navigateTo', 'runWorkflow', 'showStatus']) {
      expect(tools.get(name)!.followUp, name).toBe(false)
    }
  })

  it('connectNodes recusa anotações do canvas (não têm handles) e diz por quê', async () => {
    await mountEditor()
    let note!: { id: string }
    let step!: { id: string }
    await act(async () => {
      note = (await tools.get('addNode')!.handler({ nodeType: 'sticky-note', label: 'Lembrete' })) as { id: string }
      step = (await tools.get('addNode')!.handler({ nodeType: 'llm-chat', label: 'LLM' })) as { id: string }
    })

    let result: unknown
    await act(async () => {
      result = await tools.get('connectNodes')!.handler({ sourceId: note.id, targetId: step.id })
    })

    expect(result).toEqual(expect.objectContaining({ ok: false, error: expect.stringContaining('annotation') }))
    expect(useFlowStore.getState().edges).toEqual([])
  })

  it('a API do canvas também recusa a anotação (caminho da fila e da paleta)', async () => {
    await mountEditor()
    const api = useFlowStore.getState().canvasApi!
    let ok = true
    await act(async () => {
      const note = api.addNode({ nodeType: 'group-frame' })
      const step = api.addNode({ nodeType: 'input' })
      ok = api.connectNodes(step, note)
    })
    expect(ok).toBe(false)
    expect(useFlowStore.getState().edges).toEqual([])
  })

  it('sem canvas, connectNodes recusa ligar uma anotação enfileirada', async () => {
    await mount(h(MemoryRouter, { initialEntries: ['/workflows'] }, h(CopilotAppOperator)))
    let result: unknown
    await act(async () => {
      const note = (await tools.get('addNode')!.handler({ nodeType: 'section-divider' })) as { id: string }
      const step = (await tools.get('addNode')!.handler({ nodeType: 'input' })) as { id: string }
      result = await tools.get('connectNodes')!.handler({ sourceId: step.id, targetId: note.id })
    })
    expect(result).toEqual(expect.objectContaining({ ok: false }))
    expect(useFlowStore.getState().pendingCanvasOps.map(op => op.kind)).toEqual(['add', 'add'])
  })
})

describe('CopilotAppOperator — o laço do agente termina (revisão do RCHFLW-76)', () => {
  it('createFlow falha no mesmo lote dos addNode: nada cai no fluxo aberto e o agente para', async () => {
    // Fluxo A aberto no editor; o modelo pede um fluxo novo e os nós dele num lote só. O backend recusa o
    // createFlow (403/500): antes, os addNode do lote iam para o canvas de A, davam certo e o followUp
    // deles fazia o agente seguir ligando-os em A.
    await mountEditor()
    vi.mocked(workflowApi.create).mockRejectedValueOnce(new Error('403 Forbidden'))

    const server = await runCopilot((_body, turn) => (turn === 1
      ? [{ name: 'createFlow', args: { name: 'Novo' } }, ...THREE_NODES]
      : 'não deveria haver 2ª rodada'))

    expect(server.bodies).toHaveLength(1)
    expect(useFlowStore.getState().nodes).toEqual([])
    expect(useFlowStore.getState().pendingCanvasOps).toEqual([])
  })
})
