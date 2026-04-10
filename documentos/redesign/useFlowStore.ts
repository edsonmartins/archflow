import { create } from 'zustand'
import type { NodeExecutionState } from '../types'

interface FlowStore {
  // ── Estado de execução ─────────────────────────────────────────
  isExecuting:    boolean
  executionId:    string | null
  executionState: Record<string, NodeExecutionState>

  // ── Nó selecionado ─────────────────────────────────────────────
  selectedNodeId:   string | null
  selectedNodeData: Record<string, unknown> | null

  // ── Ações ──────────────────────────────────────────────────────
  startExecution:   (executionId: string) => void
  updateNodeStatus: (nodeId: string, status: NodeExecutionState) => void
  finishExecution:  () => void
  abortExecution:   () => void

  selectNode:   (nodeId: string | null, data: Record<string, unknown> | null) => void
  clearSelection: () => void
}

export const useFlowStore = create<FlowStore>((set, get) => ({
  isExecuting:      false,
  executionId:      null,
  executionState:   {},
  selectedNodeId:   null,
  selectedNodeData: null,

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
