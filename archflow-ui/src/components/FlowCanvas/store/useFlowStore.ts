import { create } from 'zustand'
import type { FlowNodeData, NodeExecutionState, WorkflowData } from '../types'
import type { Node, Edge } from '@xyflow/react'
import { NODE_CATEGORIES } from '../constants'

/**
 * Imperative canvas API registered by the editor's FlowCanvas while mounted, so
 * an out-of-tree caller (the global copilot) can create/connect/configure/remove nodes without
 * owning React Flow's local state. Null when no editable canvas is mounted.
 */
export interface CanvasApi {
  /**
   * Devolve o id do nó criado. {@code id} só é respeitado se estiver livre no canvas: é o id que o copiloto
   * já entregou para um addNode enfileirado, e connects enfileirados logo depois o citam.
   */
  addNode: (spec: {
    nodeType: string; label?: string; position?: { x: number; y: number }; id?: string
    /** Config inicial do nó (o copiloto já a valida contra as chaves do tipo). */
    config?: Record<string, unknown>
    /**
     * 'free': sem posição, o nó vai para a vaga livre mais próxima do centro (usado pelo copiloto, que
     * adiciona vários nós em sequência — RCHFLW-77). Sem isso vale o posicionamento de sempre da paleta.
     */
    placement?: 'free'
  }) => string
  connectNodes: (sourceId: string, targetId: string) => boolean
  listNodes: () => { id: string; label: string; nodeType: string }[]
  /** Arestas do canvas (origem → destino e o ramo, se houver) — para o copiloto saber o que remover. */
  listEdges: () => { id: string; sourceId: string; targetId: string; branch?: string }[]
  /**
   * Mescla {@code config} na config do nó (as chaves de {@code unset} voltam ao padrão) e/ou troca o
   * rótulo, num passo de undo. Devolve a config resultante, ou null para id desconhecido. O canvas não
   * decide quais chaves valem: o copiloto valida antes (nodeConfigKeys).
   */
  configureNode: (
    nodeId: string,
    patch: { config?: Record<string, unknown>; unset?: string[]; label?: string },
  ) => Record<string, unknown> | null
  /** Remove o nó e as arestas dele, num passo de undo. Devolve false para id desconhecido. */
  removeNode: (nodeId: string) => boolean
  /** Remove as arestas source → target, num passo de undo. Devolve quantas removeu. */
  removeEdge: (sourceId: string, targetId: string) => number
}

/**
 * Canvas edit deferred until an editable canvas mounts (copilot creates a flow, then adds).
 *
 * <p>{@code workflowId}: o fluxo a que a edição se destina — o que o createFlow acabou de criar. Sem ele, a
 * edição vai para o próximo canvas editável que montar (o aviso da fila diz "crie ou abra um fluxo"). Com
 * ele, só o canvas daquele fluxo a drena: se o fluxo novo não carregar, os nós pedidos para ele não podem
 * aparecer no próximo fluxo que o usuário abrir.</p>
 */
export type CanvasOp =
  | {
    kind: 'add'; nodeType: string; label?: string; id?: string; workflowId?: string
    /**
     * Config inicial. Um configureNode de um nó ainda na fila não vira outro op: ele reescreve este, porque
     * o nó só passa a existir na drenagem — e assim a fila nunca aplica uma config antes do nó.
     */
    config?: Record<string, unknown>
  }
  | { kind: 'connect'; sourceId: string; targetId: string; workflowId?: string }

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
  /**
   * Mescla campos no estado do nó (ex.: a saída lida do registro da execução) sem perder status,
   * duração e erro que os eventos STEP_* já gravaram — o updateNodeStatus substitui o estado inteiro.
   */
  mergeNodeState:   (nodeId: string, patch: Partial<NodeExecutionState>) => void
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
  /** Carimba o op com o {@link pendingCanvasTarget} (se houver e o op não trouxer o seu). */
  enqueueCanvasOp:     (op: CanvasOp) => void
  /** Tira da fila os ops sem destino e os destinados a {@code workflowId}; os de outros fluxos ficam. */
  takePendingCanvasOps: (workflowId?: string) => CanvasOp[]
  /**
   * Fluxo que o copiloto acabou de criar e para onde vão as próximas edições enfileiradas. Vale até um
   * canvas editável se registrar: dali em diante as edições vão direto para ele.
   */
  pendingCanvasTarget: string | null
  /**
   * O createFlow do copiloto: descarta a API do canvas aberto (é de OUTRO fluxo, que segue montado até o
   * editor trocar de rota) e passa a enfileirar as edições seguintes para {@code workflowId}.
   */
  awaitCanvasFor:      (workflowId: string) => void
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
  setCanvasApi: (api) => set((s) => ({
    canvasApi: api,
    pendingCanvasTarget: api ? null : s.pendingCanvasTarget,
  })),

  pendingCanvasOps: [],
  enqueueCanvasOp: (op) => set((s) => ({
    pendingCanvasOps: [
      ...s.pendingCanvasOps,
      op.workflowId || !s.pendingCanvasTarget ? op : { ...op, workflowId: s.pendingCanvasTarget },
    ],
  })),
  takePendingCanvasOps: (workflowId) => {
    const ops = get().pendingCanvasOps
    const taken = ops.filter(op => !op.workflowId || op.workflowId === workflowId)
    if (taken.length) set({ pendingCanvasOps: ops.filter(op => !taken.includes(op)) })
    return taken
  },
  pendingCanvasTarget: null,
  awaitCanvasFor: (workflowId) => set({ canvasApi: null, pendingCanvasTarget: workflowId }),

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

  mergeNodeState: (nodeId, patch) =>
    set(state => ({
      executionState: {
        ...state.executionState,
        // Sem evento STEP_* para o nó, não inventa um status: fica idle com o que chegou.
        [nodeId]: { ...(state.executionState[nodeId] ?? { status: 'idle' as const }), ...patch },
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
        const config: Record<string, unknown> = { ...n.data.config, [key]: value }
        // undefined = "tire a chave": é como um campo volta a herdar (ex.: provider → padrão da
        // plataforma). Guardar a chave com undefined/null deixaria o documento dizendo outra coisa.
        if (value === undefined) delete config[key]
        return {
          ...n,
          data: {
            ...n.data,
            config,
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
      connections: edges.map(e => {
        const d = e.data as { isErrorPath?: boolean; condition?: string; branch?: string } | undefined
        return {
          id:          e.id,
          sourceId:    e.source,
          targetId:    e.target,
          isErrorPath: d?.isErrorPath ?? false,
          ...(d?.condition ? { condition: d.condition } : {}),
          ...(d?.branch ? { branch: d.branch } : {}),
        }
      }),
    }
  },
}))

