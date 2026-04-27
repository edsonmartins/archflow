import { api } from './api'

/**
 * Shape mirroring {@code CatalogItemDto} on the backend. One type for
 * every catalog kind (agent / assistant / tool / provider / embedding
 * / memory / vectorstore / chain). {@code kind} discriminates.
 */
export interface CatalogItem {
    id: string
    displayName: string
    description?: string
    kind: CatalogKind
    capabilities?: string[]
    operations?: CatalogOperation[]
    configSchema?: CatalogConfigKey[]
    tags?: string[]
}

export type CatalogKind =
    | 'agent'
    | 'assistant'
    | 'tool'
    | 'provider'
    | 'embedding'
    | 'memory'
    | 'vectorstore'
    | 'chain'

export interface CatalogOperation {
    id: string
    name: string
    description?: string
    inputs: CatalogParameter[]
    outputs: CatalogParameter[]
}

export interface CatalogParameter {
    name: string
    type: string
    description?: string
    required: boolean
}

export interface CatalogConfigKey {
    name: string
    type: string
    required: boolean
    description?: string
    defaultValue?: unknown
}

/**
 * Calls `/api/catalog/*`. Each method is a thin wrapper around the
 * generic `api.get`, returning typed arrays. The backend routes
 * guarantee deterministic order; callers may still sort for UX.
 */
export const catalogApi = {
    listAgents:       () => api.get<CatalogItem[]>('/catalog/agents'),
    listAssistants:   () => api.get<CatalogItem[]>('/catalog/assistants'),
    listTools:        () => api.get<CatalogItem[]>('/catalog/tools'),
    listProviders:    () => api.get<CatalogItem[]>('/catalog/chat-providers'),
    listEmbeddings:   () => api.get<CatalogItem[]>('/catalog/embeddings'),
    listMemories:     () => api.get<CatalogItem[]>('/catalog/memories'),
    listVectorStores: () => api.get<CatalogItem[]>('/catalog/vectorstores'),
    listChains:       () => api.get<CatalogItem[]>('/catalog/chains'),
    listAll:          () => api.get<CatalogItem[]>('/catalog'),
}
