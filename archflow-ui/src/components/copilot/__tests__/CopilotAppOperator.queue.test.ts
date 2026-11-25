/**
 * RCHFLW-77 — o copiloto fora do editor: createFlow e depois vários addNode, todos antes de o canvas
 * montar. As operações vão para a fila e o FlowCanvas as drena num laço síncrono ao montar.
 *
 * <p>Antes: o id era {@code node-<Date.now()>} gerado na drenagem (mesmo milissegundo → mesmo id para
 * todos), a posição vinha de um ref que só muda no próximo render (todos no mesmo ponto) e um connect
 * enfileirado não tinha como citar um nó da fila — e, se falhasse, falhava calado.</p>
 *
 * <p>As ferramentas do CopilotKit são capturadas no mock de {@code useFrontendTool} e chamadas como o
 * agente as chamaria; o FlowCanvas e o useFlowStore são os reais. Arquivo .ts (createElement em vez de
 * JSX) porque o vitest.config só inclui *.test.ts.</p>
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createElement as h, act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { MemoryRouter } from 'react-router-dom'
import { MantineProvider } from '@mantine/core'
import { waitFor } from '@testing-library/dom'

type Tool = { name: string; handler: (args: Record<string, unknown>) => Promise<unknown> }

const tools = vi.hoisted(() => new Map<string, Tool>())
const notificationsMock = vi.hoisted(() => ({ show: vi.fn(), hide: vi.fn(), update: vi.fn(), clean: vi.fn() }))
const workflowApiMock = vi.hoisted(() => ({
  list: vi.fn(async () => []),
  create: vi.fn(async (wf: { metadata: { name: string } }) => ({ id: 'wf-new', metadata: wf.metadata })),
}))

vi.mock('@copilotkit/react-core/v2', () => ({
  useAgentContext: () => {},
  useFrontendTool: (tool: Tool) => { tools.set(tool.name, tool) },
}))
vi.mock('@mantine/notifications', () => ({ notifications: notificationsMock }))
vi.mock('../../../services/api', () => ({ api: { post: vi.fn() }, workflowApi: workflowApiMock, executionApi: {} }))
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (k: string, o?: Record<string, unknown>) => (o ? `${k} ${JSON.stringify(o)}` : k),
    i18n: { language: 'pt-BR', resolvedLanguage: 'pt-BR' },
  }),
}))

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

async function mount(element: ReturnType<typeof h>): Promise<Root> {
  const container = document.createElement('div')
  document.body.appendChild(container)
  containers.push(container)
  const root = createRoot(container)
  roots.push(root)
  await act(async () => { root.render(element) })
  return root
}

/** O copiloto montado numa tela qualquer (fora do editor), como no AppLayout. */
async function mountOperator() {
  await mount(h(MemoryRouter, { initialEntries: ['/workflows'] }, h(CopilotAppOperator)))
  expect([...tools.keys()]).toEqual(expect.arrayContaining(['createFlow', 'addNode', 'connectNodes']))
}

function call(name: string, args: Record<string, unknown>) {
  return act(async () => tools.get(name)!.handler(args))
}

async function toolResult(name: string, args: Record<string, unknown>): Promise<unknown> {
  let result: unknown
  await act(async () => { result = await tools.get(name)!.handler(args) })
  return result
}

/** O id do nó que o addNode devolveu ao agente (o resultado é {id, nodeType, label, queued} — RCHFLW-76). */
async function addedId(args: Record<string, unknown>): Promise<string> {
  return ((await toolResult('addNode', args)) as { id: string }).id
}

/** O editor só monta o FlowCanvas depois de carregar o fluxo — é o que esvazia a fila. */
async function mountCanvas(workflow: WorkflowData, workflowId?: string) {
  await mount(h(MantineProvider, null, h('div', { style: { width: '800px', height: '600px' } },
    h(FlowCanvas, { initialWorkflow: workflow, workflowId }))))
}

const EMPTY: WorkflowData = { steps: [], connections: [] }

function overlaps(a: { x: number; y: number }, b: { x: number; y: number }) {
  // O nó tem 240 px de largura (ShapedNode) e ~90 de altura.
  return Math.abs(a.x - b.x) < 240 && Math.abs(a.y - b.y) < 90
}

describe('CopilotAppOperator — fila createFlow → addNode antes de o canvas montar', () => {
  it('os nós da fila saem com ids únicos (os que o addNode devolveu), em posições distintas e ligados', async () => {
    // Congela o relógio: na drenagem real todas as chamadas caem no mesmo milissegundo.
    const now = vi.spyOn(Date, 'now').mockReturnValue(1_700_000_000_000)
    await mountOperator()

    await call('createFlow', { name: 'Teste' })
    const a = await addedId({ nodeType: 'input', label: 'Entrada' })
    const b = await addedId({ nodeType: 'llm-chat', label: 'Resumo' })
    const c = await addedId({ nodeType: 'output', label: 'Saída' })
    await call('connectNodes', { sourceId: a, targetId: b })
    await call('connectNodes', { sourceId: b, targetId: c })

    expect(useFlowStore.getState().pendingCanvasOps).toHaveLength(5)
    const queuedIds = [a, b, c]
    expect(new Set(queuedIds).size).toBe(3)
    queuedIds.forEach(id => expect(id).toEqual(expect.stringMatching(/^node-/)))

    // O editor do fluxo criado (createFlow devolveu wf-new).
    await mountCanvas(EMPTY, 'wf-new')
    await waitFor(() => expect(useFlowStore.getState().nodes).toHaveLength(3))
    now.mockRestore()

    const { steps, connections } = useFlowStore.getState().getCanvasSnapshot()
    expect(steps.map(s => s.id)).toEqual(queuedIds)
    expect(steps.map(s => s.type)).toEqual(['input', 'llm-chat', 'output'])
    for (let i = 0; i < steps.length; i++) {
      for (let j = i + 1; j < steps.length; j++) {
        expect(overlaps(steps[i].position, steps[j].position), `${steps[i].id} x ${steps[j].id}`).toBe(false)
      }
    }
    expect(connections.map(e => [e.sourceId, e.targetId])).toEqual([[a, b], [b, c]])
    expect(new Set(connections.map(e => e.id)).size).toBe(2)
    expect(useFlowStore.getState().pendingCanvasOps).toEqual([])
    expect(notificationsMock.show).not.toHaveBeenCalledWith(expect.objectContaining({ color: 'red' }))
  })

  it('createFlow com outro fluxo aberto: os addNode seguintes esperam o canvas novo, não caem no antigo', async () => {
    await mountOperator()
    await mountCanvas({
      steps: [{ id: 'antigo', type: 'input', componentId: 'input', label: 'A', category: 'io',
        position: { x: 0, y: 0 } }],
      connections: [],
    })
    await waitFor(() => expect(useFlowStore.getState().canvasApi).not.toBeNull())

    await call('createFlow', { name: 'Outro' })
    const id = await addedId({ nodeType: 'output' })

    expect(useFlowStore.getState().nodes.map(n => n.id)).toEqual(['antigo'])
    expect(useFlowStore.getState().pendingCanvasOps).toEqual([
      { kind: 'add', id, nodeType: 'output', label: undefined, workflowId: 'wf-new' },
    ])
  })

  it('os ops marcados para o fluxo criado não são drenados pelo canvas de OUTRO fluxo', async () => {
    // O fluxo novo não carregou (GET falhou) e o usuário abriu outro: os nós pedidos para o novo não
    // podem aparecer nele.
    await mountOperator()
    await call('createFlow', { name: 'Novo' })
    const a = await addedId({ nodeType: 'input' })
    const b = await addedId({ nodeType: 'output' })
    await call('connectNodes', { sourceId: a, targetId: b })
    // Um pedido sem fluxo criado antes (só "adicione um nó") vale para qualquer canvas que abrir.
    useFlowStore.getState().setCanvasApi({ addNode: () => '', connectNodes: () => true, listNodes: () => [] })
    useFlowStore.getState().setCanvasApi(null)
    const livre = await addedId({ nodeType: 'llm-chat' })

    await mountCanvas(EMPTY, 'wf-outro')
    await waitFor(() => expect(useFlowStore.getState().nodes).toHaveLength(1))

    expect(useFlowStore.getState().nodes.map(n => n.id)).toEqual([livre])
    expect(useFlowStore.getState().pendingCanvasOps.map(op => op.kind)).toEqual(['add', 'add', 'connect'])
    expect(useFlowStore.getState().pendingCanvasOps.every(op => op.workflowId === 'wf-new')).toBe(true)
  })

  it('e são drenados pelo canvas do fluxo criado', async () => {
    await mountOperator()
    await call('createFlow', { name: 'Novo' })
    const a = await addedId({ nodeType: 'input' })
    const b = await addedId({ nodeType: 'output' })
    await call('connectNodes', { sourceId: a, targetId: b })

    await mountCanvas(EMPTY, 'wf-new')
    await waitFor(() => expect(useFlowStore.getState().nodes).toHaveLength(2))

    const { connections } = useFlowStore.getState().getCanvasSnapshot()
    expect(connections.map(e => [e.sourceId, e.targetId])).toEqual([[a, b]])
    expect(useFlowStore.getState().pendingCanvasOps).toEqual([])
    // O destino vale só até um canvas se registrar: dali em diante as edições vão direto para ele.
    expect(useFlowStore.getState().pendingCanvasTarget).toBeNull()
  })
})

describe('CopilotAppOperator — connectNodes enfileirado confere os ids (revisão do RCHFLW-76)', () => {
  it('fluxo recém-criado: id inventado volta ok:false com os nós da fila, e nada é enfileirado', async () => {
    // O fluxo novo está vazio: os adds enfileirados para ele são todos os ids válidos. Antes, o connect
    // devolvia {ok:true, queued:true} e o agente resumia "fluxo ligado"; o erro só aparecia na drenagem.
    await mountOperator()
    await call('createFlow', { name: 'Novo' })
    const a = await addedId({ nodeType: 'input', label: 'Entrada' })
    await addedId({ nodeType: 'output', label: 'Saída' })

    const result = await toolResult('connectNodes', { sourceId: a, targetId: 'node-2' })

    expect(result).toEqual(expect.objectContaining({
      ok: false,
      error: expect.stringContaining('unknown node id: node-2'),
      nodes: [
        expect.objectContaining({ id: a, label: 'Entrada' }),
        expect.objectContaining({ label: 'Saída', nodeType: 'output' }),
      ],
    }))
    expect(useFlowStore.getState().pendingCanvasOps.map(op => op.kind)).toEqual(['add', 'add'])
  })

  it('sem fluxo criado, o próximo canvas pode ser um fluxo existente: id fora da fila segue', async () => {
    await mountOperator()
    const a = await addedId({ nodeType: 'input' })

    const result = await toolResult('connectNodes', { sourceId: 'existente', targetId: a })

    expect(result).toEqual(expect.objectContaining({ ok: true, queued: true }))
  })
})

describe('FlowCanvas — drenagem da fila do copiloto', () => {
  it('um id da fila já ocupado no canvas vira outro, e os connects da fila seguem o nó novo', async () => {
    useFlowStore.getState().enqueueCanvasOp({ kind: 'add', id: 'dup', nodeType: 'llm-chat' })
    useFlowStore.getState().enqueueCanvasOp({ kind: 'add', id: 'q2', nodeType: 'output' })
    useFlowStore.getState().enqueueCanvasOp({ kind: 'connect', sourceId: 'dup', targetId: 'q2' })

    await mountCanvas({
      steps: [{ id: 'dup', type: 'input', componentId: 'input', label: 'Já existia', category: 'io',
        position: { x: 120, y: 120 } }],
      connections: [],
    })
    await waitFor(() => expect(useFlowStore.getState().nodes).toHaveLength(3))

    const { steps, connections } = useFlowStore.getState().getCanvasSnapshot()
    const created = steps.find(s => s.type === 'llm-chat')!
    expect(created.id).not.toBe('dup')
    expect(new Set(steps.map(s => s.id)).size).toBe(3)
    expect(connections.map(e => [e.sourceId, e.targetId])).toEqual([[created.id, 'q2']])
  })

  it('um connect da fila que cita um nó inexistente avisa em vez de falhar calado', async () => {
    useFlowStore.getState().enqueueCanvasOp({ kind: 'add', id: 'q1', nodeType: 'input' })
    useFlowStore.getState().enqueueCanvasOp({ kind: 'connect', sourceId: 'q1', targetId: 'fantasma' })

    await mountCanvas(EMPTY)
    await waitFor(() => expect(notificationsMock.show).toHaveBeenCalledWith(expect.objectContaining({
      color: 'red',
      message: expect.stringContaining('copilot.errors.queuedConnectFailed'),
    })))
    expect(useFlowStore.getState().edges).toEqual([])
  })
})
