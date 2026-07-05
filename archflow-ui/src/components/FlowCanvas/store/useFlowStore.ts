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
  addNode: (spec: { nodeType: string; label?: string; position?: { x: number; y: number } }) => string
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
  replaceCanvas: (nodes: Node<FlowNodeData>[], edges: Edge[]) => void

  // ── Nó selecionado ─────────────────────────────────────────────
  selectedNodeId:   string | null
  selectedNodeData: FlowNodeData | null

  // ── Ações ──────────────────────────────────────────────────────
  startExecution:   (executionId: string) => void
  /** Swaps the provisional execution id for the backend one WITHOUT resetting per-node state. */
  adoptExecutionId: (executionId: string) => void
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
  setNodes: (nodes) => set((state) => {
    // Index once — this sync runs on every React Flow change (each drag
    // frame), so the merge must stay O(N).
    const existingById = new Map(state.nodes.map((node) => [node.id, node]))
    const merged = nodes.map((node) => {
      const existing = existingById.get(node.id)
      return existing ? { ...node, data: existing.data } : node
    })
    const selected = state.selectedNodeId
      ? merged.find((node) => node.id === state.selectedNodeId)
      : null
    return {
      nodes: merged,
      selectedNodeData: selected ? selected.data : state.selectedNodeData,
    }
  }),
  setEdges: (edges) => set({ edges }),
  replaceCanvas: (nodes, edges) =>
    set({
      nodes,
      edges,
      selectedNodeId: null,
      selectedNodeData: null,
    }),

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

  // Only adopt the backend id while a run is still active. A RUN_STARTED that
  // arrives after the user hit Stop must not resurrect the "executing" state.
  adoptExecutionId: (executionId) =>
    set((state) => (state.isExecuting ? { executionId } : {})),

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

