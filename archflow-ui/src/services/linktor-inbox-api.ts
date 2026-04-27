import { api } from './api'

/**
 * Linktor payload shapes are declared as {@code Record<string, unknown>}
 * on purpose — the backend proxies Linktor's REST response verbatim
 * and its schema evolves faster than ours. Callers pick the fields
 * they need (id, status, contactId, lastMessagePreview…) without
 * blowing up when new fields appear.
 */
export type LinktorConversation = Record<string, unknown>
export type LinktorMessage      = Record<string, unknown>
export type LinktorContact      = Record<string, unknown>
export type LinktorChannel      = Record<string, unknown>

export const linktorInboxApi = {
    listConversations: (limit = 25, offset = 0) =>
        api.get<LinktorConversation[]>(`/admin/linktor/inbox/conversations?limit=${limit}&offset=${offset}`),
    getConversation: (id: string) =>
        api.get<LinktorConversation>(`/admin/linktor/inbox/conversations/${encodeURIComponent(id)}`),
    listMessages: (id: string, limit = 50) =>
        api.get<LinktorMessage[]>(`/admin/linktor/inbox/conversations/${encodeURIComponent(id)}/messages?limit=${limit}`),
    sendMessage: (id: string, content: string) =>
        api.post<LinktorMessage>(
            `/admin/linktor/inbox/conversations/${encodeURIComponent(id)}/messages`,
            { content }),
    assign: (id: string, userId: string) =>
        api.post<LinktorConversation>(
            `/admin/linktor/inbox/conversations/${encodeURIComponent(id)}/assign`,
            { userId }),
    resolve: (id: string) =>
        api.post<LinktorConversation>(
            `/admin/linktor/inbox/conversations/${encodeURIComponent(id)}/resolve`),
    listContacts: (limit = 25, offset = 0) =>
        api.get<LinktorContact[]>(`/admin/linktor/inbox/contacts?limit=${limit}&offset=${offset}`),
    listChannels: () =>
        api.get<LinktorChannel[]>(`/admin/linktor/inbox/channels`),
}
