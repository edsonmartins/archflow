import { create } from 'zustand'
import type { FlowNodeData, NodeExecutionState, WorkflowData } from '../types'
import type { Node, Edge } from '@xyflow/react'
import { NODE_CATEGORIES } from '../constants'

/**
 * Imperative canvas API registered by the editor's FlowCanvas while mounted, so
 * an out-of-tree caller (the global copilot) can create/connect nodes without
 * owning React Flow's local state. Null when no editable canvas is mounted.
 */
export interface CanvasApi {
  addNode: (spec: { nodeType: string; label?: string }) => string
  connectNodes: (sourceId: string, targetId: string) => boolean
  listNodes: () => { id: string; label: string; nodeType: string }[]
}

/** Canvas edit deferred until an editable canvas mounts (copilot creates a flow, then adds). */
export type CanvasOp =
  | { kind: 'add'; nodeType: string; label?: string }
  | { kind: 'connect'; sourceId: string; targetId: string }

interface FlowStore {
  // ── Estado de execução ─────────────────────────────────────────
  isExecuting:    boolean
  executionId:    string | null
  executionState: Record<string, NodeExecutionState>

  // ── Nós e arestas (espelho do React Flow) ──────────────────────
  nodes: Node<FlowNodeData>[]
  edges: Edge[]
  setNodes: (nodes: Node<FlowNodeData>[]) => void
  setEdges: (edges: Edge[]) => void

  // ── Nó selecionado ─────────────────────────────────────────────
  selectedNodeId:   string | null
  selectedNodeData: FlowNodeData | null

  // ── Ações ──────────────────────────────────────────────────────
  startExecution:   (executionId: string) => void
  updateNodeStatus: (nodeId: string, status: NodeExecutionState) => void
  finishExecution:  () => void
  abortExecution:   () => void

  selectNode:       (nodeId: string | null, data: FlowNodeData | null) => void
  clearSelection:   () => void
  updateNodeConfig: (nodeId: string, key: string, value: unknown) => void
  updateNodeLabel:  (nodeId: string, label: string) => void
  getCanvasSnapshot: () => WorkflowData

  // ── API imperativa do canvas (registrada pelo FlowCanvas editável) ──
  canvasApi:    CanvasApi | null
  setCanvasApi: (api: CanvasApi | null) => void

  // ── Fila de edições aplicada quando um canvas editável monta ──
  pendingCanvasOps:    CanvasOp[]
  enqueueCanvasOp:     (op: CanvasOp) => void
  takePendingCanvasOps: () => CanvasOp[]
}

export const useFlowStore = create<FlowStore>((set, get) => ({
  isExecuting:      false,
  executionId:      null,
  executionState:   {},
  nodes:            [],
  edges:            [],
  selectedNodeId:   null,
  selectedNodeData: null,

  // ── Nós e arestas ──────────────────────────────────────────────
  setNodes: (nodes) => set({ nodes }),
  setEdges: (edges) => set({ edges }),

  canvasApi:    null,
  setCanvasApi: (api) => set({ canvasApi: api }),

  pendingCanvasOps: [],
  enqueueCanvasOp: (op) => set((s) => ({ pendingCanvasOps: [...s.pendingCanvasOps, op] })),
  takePendingCanvasOps: () => {
    const ops = get().pendingCanvasOps
    if (ops.length) set({ pendingCanvasOps: [] })
    return ops
  },

  // ── Execução ───────────────────────────────────────────────────
  startExecution: (executionId) =>
    set({
      isExecuting:    true,
      executionId,
      executionState: {},
    }),

  updateNodeStatus: (nodeId, status) =>
    set(state => ({
      executionState: {
        ...state.executionState,
        [nodeId]: status,
      },
    })),

  finishExecution: () =>
    set({ isExecuting: false }),

  abortExecution: () =>
    set({
      isExecuting: false,
      executionState: Object.fromEntries(
        Object.entries(get().executionState).map(([id, s]) => [
          id,
          s.status === 'running' ? { ...s, status: 'error' as const, error: 'Aborted' } : s,
        ])
      ),
    }),

  // ── Seleção ────────────────────────────────────────────────────
  selectNode: (nodeId, data) =>
    set({ selectedNodeId: nodeId, selectedNodeData: data }),

  clearSelection: () =>
    set({ selectedNodeId: null, selectedNodeData: null }),

  // ── Atualização de config de nó ────────────────────────────────
  updateNodeConfig: (nodeId, key, value) =>
    set(state => {
      const nodes = state.nodes.map(n => {
        if (n.id !== nodeId) return n
        return {
          ...n,
          data: {
            ...n.data,
            config: { ...n.data.config, [key]: value },
          },
        }
      })
      const updated = nodes.find(n => n.id === nodeId)
      return {
        nodes,
        selectedNodeData: state.selectedNodeId === nodeId && updated
          ? updated.data
          : state.selectedNodeData,
      }
    }),

  updateNodeLabel: (nodeId, label) =>
    set(state => {
      const nodes = state.nodes.map(n => {
        if (n.id !== nodeId) return n
        return { ...n, data: { ...n.data, label } }
      })
      const updated = nodes.find(n => n.id === nodeId)
      return {
        nodes,
        selectedNodeData: state.selectedNodeId === nodeId && updated
          ? updated.data
          : state.selectedNodeData,
      }
    }),

  getCanvasSnapshot: () => {
    const { nodes, edges } = get()
    return {
      steps: nodes.map(n => ({
        id:          n.id,
        type:        n.data.nodeType,
        componentId: n.data.componentId,
        label:       n.data.label,
        category:    (n.type ?? 'tool') as keyof typeof NODE_CATEGORIES,
        position:    n.position,
        config:      n.data.config,
      })),
      connections: edges.map(e => ({
        id:          e.id,
        sourceId:    e.source,
        targetId:    e.target,
        isErrorPath: (e.data as any)?.isErrorPath ?? false,
      })),
    }
  },
}))

// ── Hook para simular execução (dev/demo) ────────────────────────
// Em produção: chamar startExecution(), ouvir SSE do backend,
// chamar updateNodeStatus() por evento, chamar finishExecution() no fim.
export function simulateExecution(
  nodeIds: string[],
  store: FlowStore
) {
  const execId = `exec-${Date.now()}`
  store.startExecution(execId)

  nodeIds.forEach((id, i) => {
    const delay = i * 1200

    setTimeout(() => {
      store.updateNodeStatus(id, {
        status:    'running',
        startedAt: Date.now(),
      })
    }, delay)

    setTimeout(() => {
      store.updateNodeStatus(id, {
        status:     'success',
        startedAt:  Date.now() - 1000,
        durationMs: 800 + Math.random() * 400,
      })
    }, delay + 1000)
  })

  setTimeout(() => {
    store.finishExecution()
  }, nodeIds.length * 1200 + 500)
}
