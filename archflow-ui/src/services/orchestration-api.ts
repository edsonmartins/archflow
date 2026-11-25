import { api } from './api'

/** Mirrors the backend DynamicWorkflowRequest (only `goal` is required). */
export interface DynamicWorkflowRequest {
    goal: string
    decomposePrompt?: string
    maxSubtasks?: number
    voters?: number
    minAgree?: number
    maxRounds?: number
    concurrency?: number
    budgetTokens?: number
    tenantId?: string
}

export interface TraceEntry {
    type: string
    round: number
    detail: string
    confirmed: boolean | null
    /**
     * O componente que atendeu a subtarefa da linha (dispatched/failed/usage-unknown) — RCHFLW-64 —, ou o que
     * ficou fora do roteamento (excluded-from-routing, round 0, uma linha por componente) — RCHFLW-108.
     */
    componentId?: string | null
}

/** Uma subtarefa que falhou antes da verificação (RCHFLW-61). */
export interface SubtaskFailure {
    round: number
    subtask: string
    error: string
}

export interface DynamicWorkflowResponse {
    confirmed: unknown[]
    confirmedCount: number
    rounds: number
    trace: TraceEntry[]
    /** Subtarefas despachadas, somadas em todas as rodadas. */
    subtasks: number
    /** Quantas falharam antes da verificação. */
    failedCount: number
    failures: SubtaskFailure[]
    /** Tokens debitados no orçamento: o que o planejador e os agentes informaram (RCHFLW-62). */
    tokensSpent: number
    /** Chamadas que não informaram uso; com alguma, `tokensSpent` é um piso. */
    usageUnknown: number
    /** O orçamento acabou e a execução parou de despachar por isso. */
    budgetExhausted: boolean
    /** Subtarefas planejadas que não saíram porque o orçamento acabou (não são falhas). */
    notDispatched: number
}

/**
 * Fires a dynamic multi-agent workflow (ADR-0002): decompose the goal, fan out
 * to catalog-routed agents, adversarially verify and loop until convergence.
 */
export const orchestrationApi = {
    run: (req: DynamicWorkflowRequest) =>
        api.post<DynamicWorkflowResponse>('/orchestration/run', req),
}
