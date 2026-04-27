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
    lastError?: string | null
}

export type NewScheduledTrigger = Omit<
    ScheduledTrigger,
    'id' | 'createdAt' | 'lastFiredAt' | 'lastError'
>

export const triggersApi = {
    list:    () => api.get<ScheduledTrigger[]>('/admin/triggers'),
    get:     (id: string) => api.get<ScheduledTrigger>(`/admin/triggers/${encodeURIComponent(id)}`),
    create:  (t: NewScheduledTrigger) => api.post<ScheduledTrigger>('/admin/triggers', t),
    update:  (id: string, t: NewScheduledTrigger) =>
        api.put<ScheduledTrigger>(`/admin/triggers/${encodeURIComponent(id)}`, t),
    remove:  (id: string) => api.delete<void>(`/admin/triggers/${encodeURIComponent(id)}`),
    fireNow: (id: string) =>
        api.post<ScheduledTrigger>(`/admin/triggers/${encodeURIComponent(id)}/fire`),
}
