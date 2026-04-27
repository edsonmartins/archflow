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
        fetch(`${import.meta.env.VITE_API_BASE || '/api'}/../archflow/agents/${encodeURIComponent(agentId)}/invoke`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                ...(localStorage.getItem('archflow_token') ? {
                    Authorization: `Bearer ${localStorage.getItem('archflow_token')}`,
                } : {}),
            },
            body: JSON.stringify({ tenantId, sessionId, payload }),
        }).then(async r => {
            if (!r.ok) throw new Error(`HTTP ${r.status}: ${await r.text()}`)
            return r.json() as Promise<InvokeResponse>
        }),

    message: (tenantId: string, agentId: string, message: string, sessionId?: string) =>
        api.post<MessageResponse>('/../archflow/events/message', {
            tenantId, sessionId, agentId, message, metadata: {},
        }),
}
