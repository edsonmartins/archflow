import { api } from './api'

export interface ScopedApiKey {
    id: string
    keyId: string
    name: string
    scopes: string[]
    createdAt: string
    expiresAt?: string
    lastUsedAt?: string
    enabled: boolean
}

export interface CreateScopedApiKeyRequest {
    name: string
    scopes: string[]
    expiresAt?: string
}

export interface CreateScopedApiKeyResponse extends ScopedApiKey {
    keySecret: string
}

/**
 * Wraps {@code /api/apikeys/*} — the scoped/expiring API key system
 * (distinct from the admin workspace keys). All requests require the
 * {@code X-User-Id} header; we forward the authenticated user id.
 */
function userHeader(): Record<string, string> {
    const raw = localStorage.getItem('archflow_user')
    if (raw) {
        try {
            const u = JSON.parse(raw)
            if (u?.userId) return { 'X-User-Id': String(u.userId) }
        } catch { /* fall through */ }
    }
    return { 'X-User-Id': 'anonymous' }
}

async function req<T>(path: string, init: RequestInit = {}): Promise<T> {
    const headers = {
        'Content-Type': 'application/json',
        ...(init.headers as Record<string, string> ?? {}),
        ...userHeader(),
    }
    const token = localStorage.getItem('archflow_token')
    if (token) (headers as Record<string, string>).Authorization = `Bearer ${token}`
    const res = await fetch(`${import.meta.env.VITE_API_BASE || '/api'}${path}`, { ...init, headers })
    if (!res.ok) throw new Error(`HTTP ${res.status}: ${await res.text()}`)
    if (res.status === 204) return undefined as T
    return res.json() as Promise<T>
}

export const scopedApiKeyApi = {
    list:   () => req<ScopedApiKey[]>('/apikeys'),
    get:    (id: string) => req<ScopedApiKey>(`/apikeys/${encodeURIComponent(id)}`),
    create: (body: CreateScopedApiKeyRequest) =>
        req<CreateScopedApiKeyResponse>('/apikeys', { method: 'POST', body: JSON.stringify(body) }),
    remove: (id: string) =>
        req<void>(`/apikeys/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    _unused: api, // keep import linter happy
}
