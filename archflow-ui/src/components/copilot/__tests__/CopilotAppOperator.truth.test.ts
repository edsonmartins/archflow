/**
 * O copiloto não afirma o que não fez (2º bug hunt de QA).
 *
 * <ul>
 *   <li>RCHFLW-149 — connectNodes respondia ok:true para a aresta que o canvas descartava (2º ramo do mesmo par).</li>
 *   <li>RCHFLW-150 — no "Gerar com IA" o createFlow LANÇAVA a recusa: o CopilotKit não pede a rodada seguinte depois
 *       de um handler com erro, o modelo nunca via o motivo e o canvas ficava vazio.</li>
 *   <li>RCHFLW-151 — ${...} no systemPrompt e na proposta da aprovação, campos que o motor usa como estão.</li>
 *   <li>RCHFLW-152 — knowledgeBaseId inventado ("default", o nome da base).</li>
 *   <li>RCHFLW-153 — a recusa do tipo sem execução soava como "troque de tipo", e o modelo imitava a integração.</li>
 *   <li>RCHFLW-155 — as regras não diziam o que fazer com vários casos para o mesmo nó; o default ia para o
 *       automático.</li>
 * </ul>
 *
 * <p>As ferramentas são as que o CopilotAppOperator registra; o FlowCanvas e o useFlowStore são os reais. O caso do
 * RCHFLW-150 roda o laço REAL do CopilotKit com um backend falso (o mesmo SSE do AgUiAgentController).</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { waitFor } from '@testing-library/dom'
import { HttpAgent } from '@ag-ui/client'

type Tool = {
  name: string; agentId?: string; followUp?: boolean
  handler: (args: Record<string, unknown>, ctx?: unknown) => Promise<unknown>
}

const tools = vi.hoisted(() => new Map<string, Tool>())
const contexts = vi.hoisted(() => new Map<string, unknown>())
const notificationsMock = vi.hoisted(() => ({ show: vi.fn(), hide: vi.fn(), update: vi.fn(), clean: vi.fn() }))
const kb = vi.hoisted(() => ({ listBases: vi.fn() }))

vi.mock('@copilotkit/react-core/v2', () => ({
  useAgentContext: ({ description, value }: { description: string; value: unknown }) => {
    contexts.set(description, value)
  },
  useFrontendTool: (tool: Tool) => { tools.set(tool.name, tool) },
}))
vi.mock('@mantine/notifications', () => ({ notifications: notificationsMock }))
vi.mock('../../../services/api', () => ({
  api: { post: vi.fn() },
  workflowApi: { list: vi.fn(async () => []), create: vi.fn() },
  executionApi: {},
}))
vi.mock('../../../services/knowledge-api', () => ({ knowledgeApi: kb }))
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string) => k,
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))

import { CopilotKitCoreReact } from '@copilotkit/react-core/v2/context'
import CopilotAppOperator from '../CopilotAppOperator'
import { COPILOT_GENERATE_AGENT_ID, lacunasDaGeracao } from '../CopilotGenerateBridge'
import { workflowApi } from '../../../services/api'
import { FlowCanvas } from '../../FlowCanvas/FlowCanvas'
import { useFlowStore } from '../../FlowCanvas/store/useFlowStore'
import type { WorkflowData } from '../../FlowCanvas/types'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

const roots: Root[] = []
const containers: HTMLElement[] = []

const BASES = [
  { id: 'kb-721b9ef3', tenantId: 't', workspaceId: null, name: 'Políticas da loja', createdAt: '2026-11-01' },
]

beforeEach(() => {
  vi.clearAllMocks()
  tools.clear()
  contexts.clear()
  kb.listBases.mockResolvedValue(BASES)
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

async function mountEditor(workflow: WorkflowData = { steps: [], connections: [] }) {
  await mount(h(MemoryRouter, { initialEntries: ['/editor/wf-1'] }, h(CopilotAppOperator)))
  await mount(h(MantineProvider, null, h('div', { style: { width: '800px', height: '600px' } },
    h(FlowCanvas, { initialWorkflow: workflow, workflowId: 'wf-1' }))))
  await waitFor(() => expect(useFlowStore.getState().canvasApi).not.toBeNull())
}

async function call(name: string, args: Record<string, unknown>, ctx?: unknown) {
  let result: unknown
  await act(async () => { result = await tools.get(name)!.handler(args, ctx) })
  return result as Record<string, unknown>
}

const step = (id: string, componentId: string, label: string, config: Record<string, unknown> = {}) => ({
  id, type: componentId, componentId, label, category: 'control', position: { x: 0, y: 0 }, config,
})

// wf-07f28a28: switch de urgência, aprovação do coordenador e resposta automática.
const URGENCIA: WorkflowData = {
  steps: [
    step('sw', 'switch', 'Urgência', { switchExpression: '${d.decision}', switchCases: 'alta\nmedia\nbaixa' }),
    step('appr', 'approval', 'Aprovação Coordenador'),
    step('auto', 'llm-chat', 'Resposta Automática'),
  ] as WorkflowData['steps'],
  connections: [],
}

describe('connectNodes diz o que o canvas guardou (RCHFLW-149)', () => {
  it('dois casos para o mesmo destino: os dois ok, e as quatro arestas no canvas', async () => {
    await mountEditor(URGENCIA)
    const results = [
      await call('connectNodes', { sourceId: 'sw', targetId: 'appr', branch: 'alta' }),
      await call('connectNodes', { sourceId: 'sw', targetId: 'appr', branch: 'default' }),
      await call('connectNodes', { sourceId: 'sw', targetId: 'auto', branch: 'media' }),
      await call('connectNodes', { sourceId: 'sw', targetId: 'auto', branch: 'baixa' }),
    ]
    expect(results.map(r => r.ok)).toEqual([true, true, true, true])
    await waitFor(() => expect(useFlowStore.getState().getCanvasSnapshot().connections
      .map(c => [c.targetId, c.branch])).toEqual([
      ['appr', 'alta'], ['appr', 'default'], ['auto', 'media'], ['auto', 'baixa'],
    ]))
  })

  it('a mesma ligação de novo: ok:false com a aresta que já está lá, e nenhum toast de "Connected"', async () => {
    await mountEditor(URGENCIA)
    await call('connectNodes', { sourceId: 'sw', targetId: 'appr', branch: 'alta' })
    notificationsMock.show.mockClear()

    const again = await call('connectNodes', { sourceId: 'sw', targetId: 'appr', branch: 'alta' })

    expect(again).toEqual(expect.objectContaining({ ok: false, error: expect.stringContaining('already an edge') }))
    expect(notificationsMock.show).not.toHaveBeenCalledWith(expect.objectContaining({ color: 'teal' }))
    await waitFor(() => expect(useFlowStore.getState().edges).toHaveLength(1))
  })

  it('o contexto das arestas mostra o ramo de cada uma', async () => {
    await mountEditor(URGENCIA)
    await call('connectNodes', { sourceId: 'sw', targetId: 'appr', branch: 'default' })
    await waitFor(() => expect(contexts.get(
      'Edges currently on the editor canvas (sourceId -> targetId, and the branch) — use them with removeEdge',
    )).toEqual([{ sourceId: 'sw', targetId: 'appr', branch: 'default' }]))
  })
})

// wf-07f28a28 ANTES do pedido do RCHFLW-149: alta -> aprovação, default -> resposta automática.
const URGENCIA_ORIGINAL: WorkflowData = {
  steps: URGENCIA.steps,
  connections: [
    { id: 'c1', sourceId: 'sw', targetId: 'appr', isErrorPath: false, branch: 'alta' },
    { id: 'c2', sourceId: 'sw', targetId: 'auto', isErrorPath: false, branch: 'default' },
  ],
}
const branchesOnCanvas = () => useFlowStore.getState().getCanvasSnapshot().connections
  .map(c => [c.targetId, c.branch])

describe('um ramo leva a um nó, e o removeEdge tira só o ramo pedido (revisão do RCHFLW-149)', () => {
  it('default para a aprovação com o default antigo ainda na resposta: ok:false, mandando tirar o antigo', async () => {
    await mountEditor(URGENCIA_ORIGINAL)

    const moved = await call('connectNodes', { sourceId: 'sw', targetId: 'appr', branch: 'default' })

    expect(moved).toEqual(expect.objectContaining({ ok: false }))
    expect(String(moved.error)).toContain('branch "default" of sw already goes to auto')
    expect(String(moved.error)).toContain('removeEdge with sourceId sw, targetId auto and branch "default"')
    await waitFor(() => expect(branchesOnCanvas()).toEqual([['appr', 'alta'], ['auto', 'default']]))
  })

  it('removeEdge sem ramo num par com vários ramos é recusado com os ramos; com o ramo, sai só aquele', async () => {
    await mountEditor(URGENCIA_ORIGINAL)
    await call('connectNodes', { sourceId: 'sw', targetId: 'auto', branch: 'media' })
    await call('connectNodes', { sourceId: 'sw', targetId: 'auto', branch: 'baixa' })

    const all = await call('removeEdge', { sourceId: 'sw', targetId: 'auto' })
    expect(all).toEqual(expect.objectContaining({ ok: false }))
    expect(String(all.error)).toContain('"default", "media", "baixa"')
    await waitFor(() => expect(useFlowStore.getState().edges).toHaveLength(4))

    expect(await call('removeEdge', { sourceId: 'sw', targetId: 'auto', branch: 'default' })).toEqual({
      ok: true, sourceId: 'sw', targetId: 'auto', removed: 1, queued: false, removedBranches: ['default'],
    })
    expect((await call('connectNodes', { sourceId: 'sw', targetId: 'appr', branch: 'default' })).ok).toBe(true)
    await waitFor(() => expect(branchesOnCanvas()).toEqual([
      ['appr', 'alta'], ['auto', 'media'], ['auto', 'baixa'], ['appr', 'default'],
    ]))
  })

  it('na fila (sem canvas) valem as mesmas regras', async () => {
    await mount(h(MemoryRouter, { initialEntries: ['/editor/wf-1'] }, h(CopilotAppOperator)))
    const sw = await call('addNode', {
      nodeType: 'switch', label: 'Urgência', config: { switchExpression: '${d.decision}', switchCases: 'alta\nbaixa' },
    })
    const ap = await call('addNode', { nodeType: 'approval', label: 'Aprovação' })
    const auto = await call('addNode', { nodeType: 'llm-chat', label: 'Resposta' })
    await call('connectNodes', { sourceId: sw.id, targetId: auto.id, branch: 'default' })
    await call('connectNodes', { sourceId: sw.id, targetId: auto.id, branch: 'baixa' })

    const moved = await call('connectNodes', { sourceId: sw.id, targetId: ap.id, branch: 'default' })
    expect(moved).toEqual(expect.objectContaining({ ok: false }))
    expect(String(moved.error)).toContain(`already goes to ${auto.id}`)
    expect((await call('removeEdge', { sourceId: sw.id, targetId: auto.id })).ok).toBe(false)
    expect(await call('removeEdge', { sourceId: sw.id, targetId: auto.id, branch: 'default' }))
      .toEqual(expect.objectContaining({ ok: true, removed: 1, removedBranches: ['default'], queued: true }))
  })
})

// ── Laço real do CopilotKit, para o RCHFLW-150 ──

type AgUiMessage = {
  id: string; role: string; content?: string; toolCallId?: string
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

async function runGenerate(model: (body: RunBody, turn: number) => ModelCall[] | string) {
  const bodies: RunBody[] = []
  let seq = 0
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
        const toolCallId = `call-${++seq}`
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
    agents__unsafe_dev_only: { [COPILOT_GENERATE_AGENT_ID]: agent as any },
    tools: [...tools.values()] as any,
  })
  agent.addMessage({ id: 'u1', role: 'user', content: 'Triagem de pedidos de reembolso' })
  await act(async () => { await core.runAgent({ agent: agent as any }) })
  return { bodies, messages: agent.messages }
}

describe('"Gerar com IA" começando por createFlow (RCHFLW-150)', () => {
  it('a recusa volta ao modelo como resultado, ele roda de novo e monta no canvas aberto', async () => {
    await mountEditor()

    const { bodies } = await runGenerate((body, turn) => {
      if (turn === 1) return [{ name: 'createFlow', args: { name: 'Triagem de Reembolso' } }]
      if (turn === 2) {
        const refusal = body.messages.filter(m => m.role === 'tool').map(m => JSON.parse(m.content!))[0]
        expect(refusal).toEqual(expect.objectContaining({ ok: false, error: expect.stringContaining('addNode') }))
        return [{ name: 'addNode', args: { nodeType: 'input', label: 'Pedido' } }]
      }
      return 'Montei a entrada.'
    })

    expect(bodies).toHaveLength(3)
    expect(workflowApi.create).not.toHaveBeenCalled()
    expect(useFlowStore.getState().nodes.map(n => n.data.label)).toEqual(['Pedido'])
  })
})

describe('${...} em campo que o motor usa como está (RCHFLW-151)', () => {
  it('systemPrompt do llm-chat com referência é recusado, dizendo para usar o userMessage', async () => {
    await mountEditor()
    const rag = await call('addNode', { nodeType: 'rag', label: 'Busca FAQ' })
    const input = await call('addNode', { nodeType: 'input', label: 'Pergunta' })
    const llm = await call('addNode', { nodeType: 'llm-chat', label: 'Responder' })

    const refused = await call('configureNode', {
      nodeId: llm.id, config: { systemPrompt: `Contexto:\n\${${rag.id}}\n\nPergunta do cliente:\n\${${input.id}}` },
    })
    expect(refused).toEqual(expect.objectContaining({ ok: false, error: expect.stringContaining('userMessage') }))
    expect(useFlowStore.getState().canvasApi!.configOf(String(llm.id))).toEqual({})

    // O caminho que a recusa aponta é aceito.
    const ok = await call('configureNode', {
      nodeId: llm.id,
      config: { systemPrompt: 'Responda pela base.', userMessage: `Contexto: \${${rag.id}} Pergunta: \${${input.id}}` },
    })
    expect(ok).toEqual(expect.objectContaining({ ok: true }))
  })

  it('approvalProposal com referência é recusada no addNode, apontando a proposta vazia', async () => {
    await mountEditor()
    const llm = await call('addNode', { nodeType: 'llm-chat', label: 'Resumo' })
    const refused = await call('addNode', {
      nodeType: 'approval', label: 'Aprovação',
      config: { approvalProposal: `Resumo do pedido: \${${llm.id}}` },
    })
    expect(refused).toEqual(expect.objectContaining({
      ok: false, error: expect.stringContaining('leave approvalProposal empty'),
    }))
    expect(useFlowStore.getState().nodes).toHaveLength(1)
  })

  it('as regras do contexto dizem quais campos leem referências', async () => {
    await mountEditor()
    const rules = contexts.get('How to classify and branch a flow (decision answers, condition/switch expressions, '
      + 'decision routes, the llm-chat userMessage after a branch)') as string[]
    expect(rules.some(r => r.includes('systemPrompt') && r.includes('approvalProposal') && r.includes('literal')))
      .toBe(true)
    // O state do decision também resolve ${...} (StateTemplate): a regra não pode dizer "só userMessage".
    expect(rules.some(r => r.startsWith('Only the llm-chat userMessage'))).toBe(false)
    expect(rules.some(r => r.includes('decision state'))).toBe(true)
  })
})

describe('knowledgeBaseId só do catálogo (RCHFLW-152)', () => {
  it('id inventado é recusado com as bases que existem', async () => {
    await mountEditor()
    const refused = await call('addNode', {
      nodeType: 'rag', label: 'Busca FAQ', config: { knowledgeBaseId: 'default' },
    })
    expect(refused).toEqual(expect.objectContaining({ ok: false }))
    expect(String(refused.error)).toContain('kb-721b9ef3 ("Políticas da loja")')
    expect(String(refused.error)).toContain('ask')
    expect(useFlowStore.getState().nodes).toEqual([])
  })

  it('o nome da base no lugar do id é recusado com o id certo; o id entra', async () => {
    await mountEditor()
    const node = await call('addNode', { nodeType: 'vector-search', label: 'Busca' })
    const byName = await call('configureNode', { nodeId: node.id, config: { knowledgeBaseId: 'Políticas da loja' } })
    expect(byName).toEqual(expect.objectContaining({ ok: false, error: expect.stringContaining('"kb-721b9ef3"') }))

    const byId = await call('configureNode', { nodeId: node.id, config: { knowledgeBaseId: 'kb-721b9ef3' } })
    expect(byId).toEqual(expect.objectContaining({ ok: true }))
    expect(useFlowStore.getState().canvasApi!.configOf(String(node.id))).toEqual({ knowledgeBaseId: 'kb-721b9ef3' })
  })

  it('sem a lista de bases, nada é gravado (não há como saber se o id existe)', async () => {
    await mountEditor()
    kb.listBases.mockRejectedValue(new Error('500'))
    const refused = await call('addNode', { nodeType: 'rag', label: 'Busca', config: { knowledgeBaseId: 'kb-x' } })
    expect(refused).toEqual(expect.objectContaining({
      ok: false, error: expect.stringContaining('could not be loaded'),
    }))
  })

  it('as bases (id e nome) chegam ao agente pelo contexto', async () => {
    await mountEditor()
    await waitFor(() => expect(contexts.get('Knowledge bases of this workspace (id and name) — knowledgeBaseId takes '
      + 'the id; with none that fits, ask the user')).toEqual([{ id: 'kb-721b9ef3', name: 'Políticas da loja' }]))
  })
})

describe('integração que não existe é lacuna, não imitação (RCHFLW-153)', () => {
  it('a recusa do tipo sem execução manda dizer ao usuário, e não trocar por llm-chat', async () => {
    await mountEditor()
    const refused = await call('addNode', { nodeType: 'tool', label: 'Enviar e-mail SMTP' })
    expect(refused).toEqual(expect.objectContaining({ ok: false }))
    expect(String(refused.error)).toContain('not available')
    expect(String(refused.error)).toContain('do not imitate it with llm-chat or output')
  })

  it('o contexto traz a regra de não fingir e-mail/CRM', async () => {
    await mountEditor()
    const rules = contexts.get('What the nodes cannot do — never fake an integration or invent an id') as string[]
    expect(rules.join(' ')).toMatch(/Salesforce/)
    expect(rules.join(' ')).toMatch(/tell the user, in your reply, that this integration is not available/)
  })

  it('reportGap só no agente do "Gerar com IA", pedindo a rodada seguinte', async () => {
    await mountEditor()
    const gap = tools.get('reportGap')
    expect(gap).toBeDefined()
    expect(gap!.agentId).toBe(COPILOT_GENERATE_AGENT_ID)
    expect(gap!.followUp).not.toBe(false)
  })

  it('reportGap devolve a capacidade aparada; vazia é recusada, sem lançar', async () => {
    await mountEditor()
    expect(await call('reportGap', { capability: '  Enviar e-mail SMTP ' }))
      .toEqual({ ok: true, capability: 'Enviar e-mail SMTP' })
    expect(await call('reportGap', { capability: '   ' })).toEqual(expect.objectContaining({ ok: false }))
  })

  it('no laço real, a lacuna registrada volta ao modelo e chega à ponte no formato do CopilotKit', async () => {
    await mountEditor()

    const { bodies, messages } = await runGenerate((body, turn) => {
      if (turn === 1) {
        return [
          { name: 'addNode', args: { nodeType: 'input', label: 'Entrada' } },
          { name: 'addNode', args: { nodeType: 'llm-chat', label: 'Gerar Proposta' } },
          { name: 'reportGap', args: { capability: 'Enviar e-mail SMTP' } },
        ]
      }
      expect(body.tools.map(t => t.name)).toContain('reportGap')
      const call = body.messages.flatMap(m => m.toolCalls ?? []).find(c => c.function.name === 'reportGap')!
      const result = body.messages.find(m => m.role === 'tool' && m.toolCallId === call.id)!
      expect(JSON.parse(result.content!)).toEqual({ ok: true, capability: 'Enviar e-mail SMTP' })
      return 'Montei Entrada e Gerar Proposta; o envio por e-mail SMTP ficou de fora.'
    })

    expect(bodies).toHaveLength(2)
    expect(lacunasDaGeracao(messages)).toEqual(['Enviar e-mail SMTP'])
  })
})

describe('vários casos para o mesmo nó, default ao humano (RCHFLW-155)', () => {
  it('as regras dizem para ligar um ramo por caso, mesmo com destino repetido, e o default ao humano', async () => {
    await mountEditor()
    const rules = contexts.get('How to classify and branch a flow (decision answers, condition/switch expressions, '
      + 'decision routes, the llm-chat userMessage after a branch)') as string[]
    const rule = rules.find(r => r.startsWith('Several cases going to the SAME node'))
    expect(rule).toBeDefined()
    expect(rule).toContain('"default" is where a failed decision falls')
  })
})
