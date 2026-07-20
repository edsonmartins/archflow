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
}
