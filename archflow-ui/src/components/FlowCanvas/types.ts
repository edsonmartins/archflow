import type { NODE_CATEGORIES } from './constants'

// ── Dados internos de cada nó no canvas ─────────────────────────
// xyflow's `Node<TData>` constrains data to `Record<string, unknown>`.
// The index signature satisfies that constraint without weakening the
// known keys.
export interface FlowNodeData {
  label:           string
  componentId:     string
  nodeType:        string
  status:          'idle' | 'running' | 'success' | 'error' | 'skipped'
  config:          Record<string, unknown>
  executionStatus?: 'idle' | 'running' | 'success' | 'error' | 'skipped'
  executionMs?:     number
  [key: string]:    unknown
}

// ── Modelo de workflow serializado (entrada/saída pública) ────────
export interface WorkflowStep {
  id:          string
  type:        string
  componentId: string
  label:       string
  category:    keyof typeof NODE_CATEGORIES
  position:    { x: number; y: number }
  config?:     Record<string, unknown>
}

export interface WorkflowConnection {
  id:          string
  sourceId:    string
  targetId:    string
  isErrorPath: boolean
}

export interface WorkflowData {
  steps:       WorkflowStep[]
  connections: WorkflowConnection[]
}

// ── Estado de execução por nó ────────────────────────────────────
export interface NodeExecutionState {
  status:     'idle' | 'running' | 'success' | 'error' | 'skipped'
  startedAt?: number
  durationMs?: number
  output?:    unknown
  error?:     string
}

