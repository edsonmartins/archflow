import type { NODE_CATEGORIES } from './constants'

// ── Dados internos de cada nó no canvas ─────────────────────────
// xyflow's `Node<TData>` constrains data to `Record<string, unknown>`.
// The index signature satisfies that constraint without weakening the
// known keys.
/**
 * Status de execução de um nó no canvas. `awaiting` é o portão de aprovação humana parado à espera
 * da decisão: não concluiu (não é `success`, não ganha ✓ nem duração) e não está rodando (sem pulso).
 * Antes ele aparecia como concluído enquanto ninguém tinha decidido nada (RCHFLW-32).
 */
export type NodeRunStatus = 'idle' | 'running' | 'success' | 'error' | 'skipped' | 'awaiting'

export interface FlowNodeData {
  label:           string
  componentId:     string
  nodeType:        string
  status:          NodeRunStatus
  config:          Record<string, unknown>
  executionStatus?: NodeRunStatus
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
  /** Condição escrita à mão (`${a.b} == 'x'`); vence o ramo. */
  condition?:  string
  /** O ramo que a aresta quer dizer ("true", um caso do switch, uma rota da decisão). */
  branch?:     string
}

export interface WorkflowData {
  steps:       WorkflowStep[]
  connections: WorkflowConnection[]
}

// ── Estado de execução por nó ────────────────────────────────────
export interface NodeExecutionState {
  status:     NodeRunStatus
  startedAt?: number
  durationMs?: number
  output?:    unknown
  error?:     string
}

