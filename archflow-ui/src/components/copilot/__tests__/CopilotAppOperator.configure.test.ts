/**
 * RCHFLW-78 — o copiloto não conseguia configurar nó nem remover nó/aresta: só existiam addNode({nodeType,
 * label}) e connectNodes, o buildPaletteNode gravava sempre {@code config: {}} e um nodeType fora da paleta
 * virava, calado, um nó 'tool'. Todo fluxo do "Gerar com IA" saía com config vazia (llm-chat sem modelo nem
 * prompt) e um "remova o nó X" só podia duplicar nós.
 *
 * <p>As ferramentas são as que o CopilotAppOperator registra; o FlowCanvas e o useFlowStore são os reais.
 * O primeiro bloco roda o laço REAL do CopilotKit com um backend falso (o mesmo SSE do AgUiAgentController);
 * os demais chamam as ferramentas como o agente as chamaria.</p>
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

async function runCopilot(model: (body: RunBody, turn: number) => ModelCall[] | string) {
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
  agent.addMessage({ id: 'u1', role: 'user', content: 'resumir um texto com o gpt-4o-mini' })
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

describe('RCHFLW-78 — o "Gerar com IA" sai com um fluxo configurado (laço real do CopilotKit)', () => {
  it('cria, liga e configura: o llm-chat fica com provedor, modelo e prompt, sem passar pelo painel', async () => {
    await mountEditor()

    const bodies = await runCopilot((body, turn) => {
      if (turn === 1) {
        return [
          { name: 'addNode', args: { nodeType: 'input', label: 'Texto', config: { ioFields: ['texto'] } } },
          { name: 'addNode', args: { nodeType: 'llm-chat', label: 'Resumir' } },
          { name: 'addNode', args: { nodeType: 'output', label: 'Resumo' } },
        ]
      }
      if (turn === 2) {
        const ids = idsByLabel(body.messages)
        return [
          { name: 'connectNodes', args: { sourceId: ids.get('Texto'), targetId: ids.get('Resumir') } },
          { name: 'connectNodes', args: { sourceId: ids.get('Resumir'), targetId: ids.get('Resumo') } },
          // Como o backend anuncia todo parâmetro como string (AgUiAgentController.toSchema), a config chega
          // serializada — e a temperatura, como texto.
          {
            name: 'configureNode',
            args: {
              nodeId: ids.get('Resumir'),
              config: JSON.stringify({
                provider: 'openai', model: 'gpt-4o-mini', systemPrompt: 'Resuma em 3 tópicos', temperature: '0.2',
              }),
            },
          },
        ]
      }
      return 'Fluxo montado e configurado.'
    })

    expect(bodies).toHaveLength(3)
    const ids = idsByLabel(bodies[1].messages)
    const { steps, connections } = useFlowStore.getState().getCanvasSnapshot()
    const byId = new Map(steps.map(s => [s.id, s]))
    expect(byId.get(ids.get('Resumir')!)!.config).toEqual({
      provider: 'openai', model: 'gpt-4o-mini', systemPrompt: 'Resuma em 3 tópicos', temperature: 0.2,
    })
    expect(byId.get(ids.get('Texto')!)!.config).toEqual({ ioFields: ['texto'] })
    expect(connections.map(e => [e.sourceId, e.targetId])).toEqual([
      [ids.get('Texto'), ids.get('Resumir')], [ids.get('Resumir'), ids.get('Resumo')],
    ])
    // O resultado do configureNode volta ao agente com a config resultante (é o que ele resume).
    const configured = bodies[2].messages
      .filter(m => m.role === 'tool')
      .map(m => JSON.parse(m.content!) as { ok?: boolean; config?: Record<string, unknown> })
      .find(r => r.config)
    expect(configured).toEqual(expect.objectContaining({
      ok: true, config: expect.objectContaining({ model: 'gpt-4o-mini' }),
    }))
    expect(redToasts()).toEqual([])
  })

  it('chave inventada: nada é gravado, o agente recebe as chaves válidas e corrige na rodada seguinte', async () => {
    await mountEditor({ steps: [step('cond', 'condition')], connections: [] })

    const bodies = await runCopilot((body, turn) => {
      if (turn === 1) return [{ name: 'configureNode', args: { nodeId: 'cond', config: { expression: '${x} > 1' } } }]
      if (turn === 2) {
        const refusal = JSON.parse(body.messages.find(m => m.role === 'tool')!.content!) as {
          ok: boolean; error: string; allowedKeys: Record<string, string>
        }
        expect(refusal.ok).toBe(false)
        expect(refusal.error).toContain('unknown config key "expression"')
        expect(configOf('cond')).toEqual({})
        const [key] = Object.keys(refusal.allowedKeys)
        return [{ name: 'configureNode', args: { nodeId: 'cond', config: { [key]: '${x} > 1' } } }]
      }
      return 'ok'
    })

    expect(bodies).toHaveLength(3)
    expect(configOf('cond')).toEqual({ conditionExpression: '${x} > 1' })
  })
})

describe('RCHFLW-78 — configureNode', () => {
  it('mescla na config existente, converte tipos e null volta a chave ao padrão', async () => {
    await mountEditor({
      steps: [step('llm', 'llm-chat', { provider: 'anthropic', model: 'claude', maxTokens: 512 })], connections: [],
    })

    const result = await call('configureNode', {
      nodeId: 'llm', config: { model: 'gpt-4o-mini', temperature: '0.3', maxTokens: null },
    })

    expect(result).toEqual(expect.objectContaining({ ok: true, queued: false }))
    expect(configOf('llm')).toEqual({ provider: 'anthropic', model: 'gpt-4o-mini', temperature: 0.3 })
  })

  it('tudo ou nada: um valor inválido não deixa gravar as outras chaves do mesmo patch', async () => {
    await mountEditor({ steps: [step('llm', 'llm-chat')], connections: [] })

    const result = await call('configureNode', {
      nodeId: 'llm', config: { systemPrompt: 'Olá', temperature: 'quente' },
    })

    expect(result).toEqual(expect.objectContaining({
      ok: false,
      error: '"temperature" must be number',
      allowedKeys: expect.objectContaining({ systemPrompt: 'string' }),
    }))
    expect(configOf('llm')).toEqual({})
    expect(redToasts()).toHaveLength(1)
  })

  it('id desconhecido devolve os nós válidos; config que não é objeto é recusada', async () => {
    await mountEditor({ steps: [step('llm', 'llm-chat')], connections: [] })

    expect(await call('configureNode', { nodeId: 'node-1', config: { model: 'x' } })).toEqual(expect.objectContaining({
      ok: false, nodes: [{ id: 'llm', label: 'llm', nodeType: 'llm-chat' }],
    }))
    expect(await call('configureNode', { nodeId: 'llm', config: '[1,2]' }))
      .toEqual(expect.objectContaining({ ok: false, error: expect.stringContaining('JSON object') }))
    expect(await call('configureNode', { nodeId: 'llm' }))
      .toEqual(expect.objectContaining({ ok: false, error: expect.stringContaining('nothing to change') }))
  })

  it('renomeia o nó e o painel de propriedades do nó selecionado mostra a config nova na hora', async () => {
    await mountEditor({ steps: [step('llm', 'llm-chat')], connections: [] })
    const node = useFlowStore.getState().nodes[0]
    act(() => { useFlowStore.getState().selectNode(node.id, node.data) })

    await call('configureNode', { nodeId: 'llm', label: 'Resumir', config: { systemPrompt: 'Resuma' } })

    const { selectedNodeData, nodes } = useFlowStore.getState()
    expect(selectedNodeData).toEqual(expect.objectContaining({ label: 'Resumir', config: { systemPrompt: 'Resuma' } }))
    expect(nodes[0].data.label).toBe('Resumir')
  })

  it('um passo de undo desfaz o configureNode inteiro', async () => {
    const el = await mountEditor({ steps: [step('llm', 'llm-chat', { model: 'a' })], connections: [] })

    await call('configureNode', { nodeId: 'llm', config: { model: 'b', systemPrompt: 'p' } })
    expect(configOf('llm')).toEqual({ model: 'b', systemPrompt: 'p' })

    const undo = el.querySelector('[aria-label="editor.canvasActions.undo"]') as HTMLButtonElement
    await act(async () => { undo.click() })
    expect(configOf('llm')).toEqual({ model: 'a' })
    expect(undo.disabled).toBe(true)
  })

  it('fora de um evento do React (como o CopilotKit chama), o configureNode segue sendo UM passo de undo', async () => {
    // O configureNode grava canvas e store juntos; os dois efeitos de sincronia não podem tomar a config nova
    // por uma edição do painel e empilhar um segundo passo (o usuário teria de apertar Ctrl+Z duas vezes).
    const el = await mountEditor({ steps: [step('llm', 'llm-chat', { model: 'a' })], connections: [] })
    ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = false
    try {
      await tools.get('configureNode')!.handler({ nodeId: 'llm', config: { model: 'b' } })
      await new Promise(r => setTimeout(r, 50))
    } finally {
      ;(globalThis as any).IS_REACT_ACT_ENVIRONMENT = true
    }
    expect(configOf('llm')).toEqual({ model: 'b' })

    const undo = el.querySelector('[aria-label="editor.canvasActions.undo"]') as HTMLButtonElement
    await act(async () => { undo.click() })
    expect(configOf('llm')).toEqual({ model: 'a' })
    expect(undo.disabled).toBe(true)
  })

  it('a senha numa URL digitada no painel não vai para o contexto do agente', async () => {
    await mountEditor({
      steps: [step('ag', 'agent', { memoryBackend: 'redis', redisUrl: 'redis://:s3cret@cache:6379' })],
      connections: [],
    })
    const [, nodes] = [...contexts.entries()].find(([d]) => d.startsWith('Nodes currently on the editor canvas'))!
    expect(JSON.stringify(nodes)).not.toContain('s3cret')
    expect(nodes).toEqual([expect.objectContaining({
      config: { memoryBackend: 'redis', redisUrl: 'redis://<redacted>@cache:6379' },
    })])
    // Trocar outra chave não mexe na URL; e o resultado devolvido ao agente também sai mascarado.
    const result = await call<{ config: Record<string, unknown> }>('configureNode', {
      nodeId: 'ag', config: { maxIterations: 5 },
    })
    expect(JSON.stringify(result)).not.toContain('s3cret')
    expect(configOf('ag'))
      .toEqual({ memoryBackend: 'redis', redisUrl: 'redis://:s3cret@cache:6379', maxIterations: 5 })
    // Copiar a URL mascarada de volta é recusado: gravaria a marca no lugar da senha.
    expect(await call('configureNode', { nodeId: 'ag', config: { redisUrl: 'redis://<redacted>@cache:6379' } }))
      .toEqual(expect.objectContaining({ ok: false }))
    expect(configOf('ag')).toEqual(expect.objectContaining({ redisUrl: 'redis://:s3cret@cache:6379' }))
  })

  it('decisão: o copiloto não redireciona o endpoint nem grava perguntas fora da forma do motor', async () => {
    await mountEditor({ steps: [step('dec', 'decision', { provider: 'http-decisions' })], connections: [] })

    expect(await call('configureNode', {
      nodeId: 'dec', config: { providerOptions: { 'http-decisions': { endpoint: 'https://atacante.example' } } },
    })).toEqual(expect.objectContaining({ ok: false, error: expect.stringContaining('can only be set by the user') }))
    expect(await call('configureNode', { nodeId: 'dec', config: { questions: { q: 'é urgente?' } } }))
      .toEqual(expect.objectContaining({ ok: false }))
    expect(configOf('dec')).toEqual({ provider: 'http-decisions' })
    expect(notificationsMock.show).not.toHaveBeenCalledWith(expect.objectContaining({ color: 'teal' }))

    expect(await call('configureNode', {
      nodeId: 'dec',
      config: { questions: { urgente: { type: 'noul', instructions: 'É urgente?' } }, thresholds: { auto: 0.8 } },
    })).toEqual(expect.objectContaining({ ok: true }))
  })

  it('a config sem chaves de API vai para o contexto do agente', async () => {
    await mountEditor({
      steps: [step('vs', 'vector-search', { vectorBackend: 'pinecone', pineconeApiKey: 'sk-secreta', topK: 3 })],
      connections: [],
    })
    const [, nodes] = [...contexts.entries()].find(([d]) => d.startsWith('Nodes currently on the editor canvas'))!
    expect(nodes).toEqual([expect.objectContaining({ id: 'vs', config: { vectorBackend: 'pinecone', topK: 3 } })])
    // E o modelo não pode gravá-la.
    expect(await call('configureNode', { nodeId: 'vs', config: { pineconeApiKey: 'sk-outra' } }))
      .toEqual(expect.objectContaining({ ok: false }))
  })
})

describe('RCHFLW-78 — addNode', () => {
  it('nodeType fora da paleta é recusado com a lista de tipos, sem criar um nó "tool" calado', async () => {
    await mountEditor()

    const result = await call('addNode', { nodeType: 'http-request', label: 'HTTP' })

    expect(result).toEqual(expect.objectContaining({
      ok: false, error: expect.stringMatching(/^unknown node type "http-request"/),
      nodeTypes: expect.arrayContaining(['llm-chat', 'input']),
    }))
    expect(useFlowStore.getState().nodes).toEqual([])
    expect(notificationsMock.show).not.toHaveBeenCalledWith(expect.objectContaining({ color: 'teal' }))
  })

  // RCHFLW-11: o "Gerar com IA" criava nós que o motor recusa; o agente precisa saber por quê para trocar o tipo.
  it.each(['agent', 'tool', 'transform', 'llm-streaming'])('não cria nó %s, que não está entre os tipos que executam', async (nodeType) => {
    await mountEditor()

    const result = await call<{ ok: boolean; error: string; nodeTypes: string[] }>('addNode', { nodeType, label: 'X' })

    expect(result.ok).toBe(false)
    expect(result.nodeTypes).not.toContain(nodeType)
    // RCHFLW-153: o motivo vem primeiro; depois, o que fazer com a lacuna (dizer ao usuário, não imitar).
    expect(result.error.startsWith(nodeType === 'llm-streaming'
      ? 'unknown node type "llm-streaming"'
      : `node type "${nodeType}" is not yet supported at run time`)).toBe(true)
    expect(useFlowStore.getState().nodes).toEqual([])
  })

  it('aceita a config já na criação, validada contra as chaves do tipo', async () => {
    await mountEditor()

    const ok = await call<{ id: string }>('addNode', {
      // ${input.…} e não ${t.…}: com o canvas vazio, "t" não é passo nenhum, e a expressão seria recusada (RCHFLW-116).
      nodeType: 'condition', label: 'Urgente?', config: { conditionExpression: "${input.prioridade} == 'alta'" },
    })
    const bad = await call('addNode', { nodeType: 'condition', config: { prompt: 'x' } })

    expect(configOf(ok.id)).toEqual({ conditionExpression: "${input.prioridade} == 'alta'" })
    expect(bad).toEqual(expect.objectContaining({ ok: false, allowedKeys: { conditionExpression: 'string' } }))
    expect(useFlowStore.getState().nodes).toHaveLength(1)
  })
})

describe('RCHFLW-78 — removeNode / removeEdge', () => {
  const chain: WorkflowData = {
    steps: [step('a', 'input'), step('b', 'llm-chat'), step('c', 'output')],
    connections: [
      { id: 'e1', sourceId: 'a', targetId: 'b', isErrorPath: false },
      { id: 'e2', sourceId: 'b', targetId: 'c', isErrorPath: false },
    ],
  }

  it('removeNode tira o nó e as arestas dele, e fecha o painel se ele estava selecionado', async () => {
    await mountEditor(chain)
    const b = useFlowStore.getState().nodes.find(n => n.id === 'b')!
    act(() => { useFlowStore.getState().selectNode(b.id, b.data) })

    const result = await call('removeNode', { nodeId: 'b' })

    expect(result).toEqual({ ok: true, nodeId: 'b', removedEdges: 2, queued: false })
    // O nó já estava no fluxo: só sai com o OK do usuário.
    expect(confirmRemoval).toHaveBeenCalledWith(expect.any(Function), { id: 'b', label: 'b' })
    const { steps, connections } = useFlowStore.getState().getCanvasSnapshot()
    expect(steps.map(s => s.id)).toEqual(['a', 'c'])
    expect(connections).toEqual([])
    expect(useFlowStore.getState().selectedNodeId).toBeNull()
  })

  it('nó que já estava no fluxo: o usuário recusa e nada sai — o agente lê que foi o usuário', async () => {
    await mountEditor(chain)
    confirmRemoval.mockResolvedValueOnce(false)

    const result = await call('removeNode', { nodeId: 'b' })

    expect(result).toEqual(expect.objectContaining({ ok: false, declined: true }))
    const { steps, connections } = useFlowStore.getState().getCanvasSnapshot()
    expect(steps.map(s => s.id)).toEqual(['a', 'b', 'c'])
    expect(connections).toHaveLength(2)
    expect(notificationsMock.show).not.toHaveBeenCalledWith(expect.objectContaining({ color: 'teal' }))
  })

  it('nó que o próprio copiloto criou sai sem perguntar (corrigir o que montou)', async () => {
    await mountEditor(chain)
    const extra = await call<{ id: string }>('addNode', { nodeType: 'output', label: 'Duplicado' })

    expect(await call('removeNode', { nodeId: extra.id }))
      .toEqual({ ok: true, nodeId: extra.id, removedEdges: 0, queued: false })
    expect(confirmRemoval).not.toHaveBeenCalled()
    expect(useFlowStore.getState().nodes.map(n => n.id)).toEqual(['a', 'b', 'c'])
  })

  it('o usuário apaga o nó enquanto o modal está aberto: o removeNode não finge que removeu', async () => {
    await mountEditor(chain)
    confirmRemoval.mockImplementationOnce(async () => {
      act(() => { useFlowStore.getState().canvasApi!.removeNode('b') })
      return true
    })

    expect(await call('removeNode', { nodeId: 'b' }))
      .toEqual(expect.objectContaining({ ok: false, error: 'unknown node id: b' }))
  })

  it('removeNode com id desconhecido devolve os nós e não mexe em nada', async () => {
    await mountEditor(chain)
    expect(await call('removeNode', { nodeId: 'zz' })).toEqual(expect.objectContaining({
      ok: false, nodes: expect.arrayContaining([expect.objectContaining({ id: 'a' })]),
    }))
    expect(useFlowStore.getState().nodes).toHaveLength(3)
  })

  it('removeEdge tira só a aresta pedida; uma que não existe devolve as arestas do canvas', async () => {
    await mountEditor(chain)

    expect(await call('removeEdge', { sourceId: 'a', targetId: 'b' }))
      .toEqual({ ok: true, sourceId: 'a', targetId: 'b', removed: 1, queued: false })
    expect(await call('removeEdge', { sourceId: 'a', targetId: 'c' })).toEqual(expect.objectContaining({
      ok: false, edges: [{ sourceId: 'b', targetId: 'c' }],
    }))
    expect(useFlowStore.getState().getCanvasSnapshot().connections.map(e => [e.sourceId, e.targetId]))
      .toEqual([['b', 'c']])
  })

  it('religar: removeEdge + connectNodes trocam o destino de uma aresta', async () => {
    await mountEditor(chain)
    await call('removeEdge', { sourceId: 'b', targetId: 'c' })
    await call('removeNode', { nodeId: 'c' })
    const d = await call<{ id: string }>('addNode', { nodeType: 'output', label: 'Novo fim' })
    await call('connectNodes', { sourceId: 'b', targetId: d.id })

    expect(useFlowStore.getState().getCanvasSnapshot().connections.map(e => [e.sourceId, e.targetId]))
      .toEqual([['a', 'b'], ['b', d.id]])
  })
})

describe('RCHFLW-78 — com os nós ainda na fila (sem canvas aberto)', () => {
  it('configureNode/removeNode/removeEdge editam a fila, e a drenagem cria os nós já configurados', async () => {
    await operator('/workflows')
    const input = await call<{ id: string }>('addNode', { nodeType: 'input', label: 'Entrada' })
    const llm = await call<{ id: string }>('addNode', { nodeType: 'llm-chat', label: 'LLM' })
    const extra = await call<{ id: string }>('addNode', { nodeType: 'output', label: 'Sobra' })
    await call('connectNodes', { sourceId: input.id, targetId: llm.id })
    await call('connectNodes', { sourceId: llm.id, targetId: extra.id })

    expect(await call('configureNode', { nodeId: llm.id, config: { systemPrompt: 'Resuma' } }))
      .toEqual(expect.objectContaining({ ok: true, queued: true, config: { systemPrompt: 'Resuma' } }))
    expect(await call('removeNode', { nodeId: extra.id }))
      .toEqual({ ok: true, nodeId: extra.id, removedEdges: 1, queued: true })
    expect(await call('removeEdge', { sourceId: input.id, targetId: llm.id }))
      .toEqual(expect.objectContaining({ ok: true, removed: 1, queued: true }))
    // Um nó que não está na fila nem num canvas não pode ser configurado "às cegas".
    expect(await call('configureNode', { nodeId: 'node-x', config: { model: 'm' } }))
      .toEqual(expect.objectContaining({ ok: false, error: expect.stringContaining('no flow is open') }))

    await canvas()
    await waitFor(() => expect(useFlowStore.getState().nodes).toHaveLength(2))
    const { steps, connections } = useFlowStore.getState().getCanvasSnapshot()
    expect(steps.map(s => [s.id, s.config])).toEqual([[input.id, {}], [llm.id, { systemPrompt: 'Resuma' }]])
    expect(connections).toEqual([])
    expect(useFlowStore.getState().pendingCanvasOps).toEqual([])
  })
})

describe('RCHFLW-78 — contrato das ferramentas novas', () => {
  it('pedem a rodada seguinte (o agente lê o resultado e corrige) e entram no catálogo enviado ao modelo', async () => {
    await mountEditor()
    for (const name of ['configureNode', 'removeNode', 'removeEdge']) {
      expect(tools.get(name), name).toBeDefined()
      expect(tools.get(name)!.followUp, name).not.toBe(false)
    }
    const keys = [...contexts.entries()].find(([d]) => d.startsWith('Config keys'))![1] as Record<string, string>
    expect(keys['llm-chat']).toContain('systemPrompt:string')
    expect(keys.condition).toBe('conditionExpression:string')
  })
})
