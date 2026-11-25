import { authFetch } from './api'

export interface InvokeResponse {
    requestId: string
    tenantId: string
    agentId: string
    status: string
    timestamp: string
}

export interface MessageResponse {
    requestId: string
    tenantId: string
    sessionId: string
    agentId: string
    status: string
    response?: string
    timestamp?: string
}

/** Andamento de uma invocação da fila: QUEUED/RUNNING são transitórios, COMPLETED/FAILED finais. */
export type InvocationStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED'

export interface InvocationResult {
    requestId: string
    tenantId: string
    agentId: string
    status: InvocationStatus
    output?: unknown
    error?: string | null
    attempts: number
    createdAt?: string | null
    startedAt?: string | null
    finishedAt?: string | null
}

export const isTerminal = (s: InvocationStatus) => s === 'COMPLETED' || s === 'FAILED'

/**
 * 404 da consulta: o requestId não existe para este tenant. Separado dos outros erros porque, logo
 * depois do ACCEPTED, há um instante em que o consumidor retirou a invocação da fila em memória e ainda
 * não a registrou — quem consulta tolera alguns 404 seguidos antes de desistir.
 */
export class InvocationNotFoundError extends Error {}

/**
 * Wrapper for the two "trigger an agent" endpoints that the audit
 * flagged as lacking a frontend surface: agent invoke and event
 * message. Used by the playground page to test integrations without
 * curl.
 */
export const agentPlaygroundApi = {
    // authFetch (path absoluto, fora do prefixo /api) aplica refresh de token,
    // Authorization e X-Impersonate-Tenant — sem ele estas chamadas davam 401
    // silencioso após a expiração do access token em sessões longas.
    invoke: (agentId: string, tenantId: string, sessionId: string | undefined, payload: Record<string, unknown>) =>
        authFetch(`/archflow/agents/${encodeURIComponent(agentId)}/invoke`, {
            method: 'POST',
            body: JSON.stringify({ tenantId, sessionId, payload }),
        }).then(async r => {
            if (!r.ok) throw new Error(`HTTP ${r.status}: ${await r.text()}`)
            return r.json() as Promise<InvokeResponse>
        }),

    message: (tenantId: string, agentId: string, message: string, sessionId?: string) =>
        authFetch('/archflow/events/message', {
            method: 'POST',
            body: JSON.stringify({ tenantId, sessionId, agentId, message, metadata: {} }),
        }).then(async r => {
            if (!r.ok) throw new Error(`HTTP ${r.status}: ${await r.text()}`)
            return r.json() as Promise<MessageResponse>
        }),

    // RCHFLW-80: o invoke e o chat só respondem ACCEPTED; o resultado do agente sai daqui.
    invocation: (requestId: string, tenantId: string) =>
        authFetch(`/archflow/agents/invocations/${encodeURIComponent(requestId)}`
                + `?tenantId=${encodeURIComponent(tenantId)}`)
            .then(async r => {
                if (r.status === 404) throw new InvocationNotFoundError(await r.text())
                if (!r.ok) throw new Error(`HTTP ${r.status}: ${await r.text()}`)
                return r.json() as Promise<InvocationResult>
            }),
}
