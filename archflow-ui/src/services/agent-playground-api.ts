import { api } from './api'

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
    invoke: (agentId: string, tenantId: string, sessionId: string | undefined, payload: Record<string, unknown>) =>
        fetch(`/archflow/agents/${encodeURIComponent(agentId)}/invoke`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                ...(sessionStorage.getItem('archflow_token') ? {
                    Authorization: `Bearer ${sessionStorage.getItem('archflow_token')}`,
                } : {}),
            },
            body: JSON.stringify({ tenantId, sessionId, payload }),
        }).then(async r => {
            if (!r.ok) throw new Error(`HTTP ${r.status}: ${await r.text()}`)
            return r.json() as Promise<InvokeResponse>
        }),

    message: (tenantId: string, agentId: string, message: string, sessionId?: string) =>
        // Path absoluto (fora do prefixo /api): o truque anterior com '/../'
        // dependia da normalização de dot-segments do browser e quebrava
        // atrás de gateways que não normalizam.
        fetch('/archflow/events/message', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                ...(sessionStorage.getItem('archflow_token') ? {
                    Authorization: `Bearer ${sessionStorage.getItem('archflow_token')}`,
                } : {}),
            },
            body: JSON.stringify({ tenantId, sessionId, agentId, message, metadata: {} }),
        }).then(async r => {
            if (!r.ok) throw new Error(`HTTP ${r.status}: ${await r.text()}`)
            return r.json() as Promise<MessageResponse>
        }),
}
