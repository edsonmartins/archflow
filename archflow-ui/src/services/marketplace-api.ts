import { api } from './api'

export interface Extension {
    id: string
    name: string
    version: string
    displayName: string
    author: string
    description: string
    type: string
    permissions: string[]
    installed: boolean
}

export interface InstallExtensionRequest {
    manifestUrl: string
    verifySignature?: boolean
}

/**
 * Wraps {@code /api/extensions/*} which is backed by
 * {@code MarketplaceControllerImpl}. The backend accepts a manifest
 * path (local) under the installer's configured extensions directory.
 */
export const marketplaceApi = {
    list:        () => api.get<Extension[]>('/extensions'),
    get:         (id: string) => api.get<Extension>(`/extensions/${encodeURIComponent(id)}`),
    search:      (query: string, type?: string) => {
        const q = encodeURIComponent(query ?? '')
        const t = type ? `&type=${encodeURIComponent(type)}` : ''
        return api.get<Extension[]>(`/extensions/search?q=${q}${t}`)
    },
    install:     (req: InstallExtensionRequest) =>
        api.post<Extension>('/extensions/install', req),
    uninstall:   (id: string) =>
        api.delete<void>(`/extensions/${encodeURIComponent(id)}`),
}
