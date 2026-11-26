import { api, ifMatch } from './api'

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
    /** Versão da configuração (RCHFLW-191): vai no If-Match do Salvar, e o servidor responde 409 se mudou. */
    updatedAt?: string | null
    /**
     * Próximo disparo segundo o Quartz; null quando não há agendamento. É ele, e não `enabled`, que diz se o
     * gatilho está mesmo agendado (RCHFLW-140).
     */
    nextFireTime?: string | null
    /** Fuso em que o servidor lê o cron (ex.: America/Sao_Paulo). */
    timeZone?: string | null
}

export type TriggerRunStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED'

export type NewScheduledTrigger = Omit<
    ScheduledTrigger,
    'id' | 'createdAt' | 'lastFiredAt' | 'lastError' | 'lastRequestId' | 'lastStatus' | 'updatedAt'
    | 'nextFireTime' | 'timeZone'
>

export const triggersApi = {
    // tenantId ausente = o servidor usa o tenant da requisição; 'all' ou outro tenant, só o admin
    // (RCHFLW-103, a mesma regra da fila de aprovações — ver approvalTenantParam).
    list:    (tenantId?: string) => api.get<ScheduledTrigger[]>(
        `/admin/triggers${tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ''}`),
    get:     (id: string) => api.get<ScheduledTrigger>(`/admin/triggers/${encodeURIComponent(id)}`),
    create:  (t: NewScheduledTrigger) => api.post<ScheduledTrigger>('/admin/triggers', t),
    // expectedUpdatedAt: o updatedAt que o formulário leu — se outra pessoa salvou no meio, 409 TRIGGER_CONFLICT.
    update:  (id: string, t: NewScheduledTrigger, expectedUpdatedAt?: string | null) =>
        api.put<ScheduledTrigger>(`/admin/triggers/${encodeURIComponent(id)}`, t, ifMatch(expectedUpdatedAt)),
    remove:  (id: string) => api.delete<void>(`/admin/triggers/${encodeURIComponent(id)}`),
    fireNow: (id: string) =>
        api.post<ScheduledTrigger>(`/admin/triggers/${encodeURIComponent(id)}/fire`),
}
