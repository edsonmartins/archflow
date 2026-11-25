import { api } from './api'

export interface ScheduledTrigger {
    id: string
    name: string
    cronExpression: string
    tenantId: string
    agentId: string
    payload: Record<string, unknown>
    enabled: boolean
    createdAt: string
    lastFiredAt?: string | null
    /** A falha do último disparo: a de enfileirar ou a do agente na invocação disparada (RCHFLW-104). */
    lastError?: string | null
    /** requestId da última invocação disparada: é por ele que se consulta como o agente terminou. */
    lastRequestId?: string | null
    /** Como terminou o último disparo, inclusive os automáticos; null se nunca disparou. */
    lastStatus?: TriggerRunStatus | null
}

export type TriggerRunStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED'

export type NewScheduledTrigger = Omit<
    ScheduledTrigger,
    'id' | 'createdAt' | 'lastFiredAt' | 'lastError' | 'lastRequestId' | 'lastStatus'
>

export const triggersApi = {
    // tenantId ausente = o servidor usa o tenant da requisição; 'all' ou outro tenant, só o admin
    // (RCHFLW-103, a mesma regra da fila de aprovações — ver approvalTenantParam).
    list:    (tenantId?: string) => api.get<ScheduledTrigger[]>(
        `/admin/triggers${tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ''}`),
    get:     (id: string) => api.get<ScheduledTrigger>(`/admin/triggers/${encodeURIComponent(id)}`),
    create:  (t: NewScheduledTrigger) => api.post<ScheduledTrigger>('/admin/triggers', t),
    update:  (id: string, t: NewScheduledTrigger) =>
        api.put<ScheduledTrigger>(`/admin/triggers/${encodeURIComponent(id)}`, t),
    remove:  (id: string) => api.delete<void>(`/admin/triggers/${encodeURIComponent(id)}`),
    fireNow: (id: string) =>
        api.post<ScheduledTrigger>(`/admin/triggers/${encodeURIComponent(id)}/fire`),
}
