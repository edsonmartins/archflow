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
}

export interface DynamicWorkflowResponse {
    confirmed: unknown[]
    confirmedCount: number
    rounds: number
    trace: TraceEntry[]
}

/**
 * Fires a dynamic multi-agent workflow (ADR-0002): decompose the goal, fan out
 * to catalog-routed agents, adversarially verify and loop until convergence.
 */
export const orchestrationApi = {
    run: (req: DynamicWorkflowRequest) =>
        api.post<DynamicWorkflowResponse>('/orchestration/run', req),
}
