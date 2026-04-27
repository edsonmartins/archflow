import { useEffect, useState } from 'react'
import { catalogApi, type CatalogItem } from '../../services/catalog-api'

/**
 * Fallback entries used while the backend request is in flight or on
 * offline/dev environments with no plugins registered. Mirrors the
 * identifiers that the reference plugin jars declare (see
 * {@code ConversationalAgent.COMPONENT_ID} etc.), so the editor can
 * render a sensible palette without a live backend.
 */
const FALLBACK_AGENTS: CatalogItem[] = [
    { id: 'conversational-agent', displayName: 'Conversational Agent', kind: 'agent',
      description: 'Customer service / intent classification / escalation' },
    { id: 'research-agent', displayName: 'Research Agent', kind: 'agent',
      description: 'Decomposes tasks and plans multi-step research' },
    { id: 'data-analysis-agent', displayName: 'Data Analysis Agent', kind: 'agent',
      description: 'Analyzes datasets and generates reports' },
    { id: 'monitoring-agent', displayName: 'Monitoring Agent', kind: 'agent',
      description: 'Anomaly detection and threshold-based alerting' },
]

const FALLBACK_ASSISTANTS: CatalogItem[] = [
    { id: 'tech-support-assistant', displayName: 'Tech Support Assistant', kind: 'assistant',
      description: 'Troubleshooting assistant for IT issues' },
]

const FALLBACK_TOOLS: CatalogItem[] = [
    { id: 'text-transform-tool', displayName: 'Text Transform', kind: 'tool',
      description: 'uppercase / lowercase / reverse / wordcount' },
]

let agentsCache:      Promise<CatalogItem[]> | null = null
let assistantsCache:  Promise<CatalogItem[]> | null = null
let toolsCache:       Promise<CatalogItem[]> | null = null
let embeddingsCache:  Promise<CatalogItem[]> | null = null
let memoriesCache:    Promise<CatalogItem[]> | null = null
let vectorstoreCache: Promise<CatalogItem[]> | null = null
let chainsCache:      Promise<CatalogItem[]> | null = null

function fetchOnce<T>(
        cacheGetter: () => Promise<T[]> | null,
        cacheSetter: (p: Promise<T[]>) => void,
        fetcher: () => Promise<T[]>,
        fallback: T[],
): Promise<T[]> {
    const existing = cacheGetter()
    if (existing) return existing
    const promise = fetcher().catch(() => fallback)
    cacheSetter(promise)
    return promise
}

function fetchAgents() {
    return fetchOnce(() => agentsCache, p => { agentsCache = p },
            () => catalogApi.listAgents(),
            FALLBACK_AGENTS)
}
function fetchAssistants() {
    return fetchOnce(() => assistantsCache, p => { assistantsCache = p },
            () => catalogApi.listAssistants(),
            FALLBACK_ASSISTANTS)
}
function fetchTools() {
    return fetchOnce(() => toolsCache, p => { toolsCache = p },
            () => catalogApi.listTools(),
            FALLBACK_TOOLS)
}
function fetchEmbeddings() {
    return fetchOnce(() => embeddingsCache, p => { embeddingsCache = p },
            () => catalogApi.listEmbeddings(),
            [])
}
function fetchMemories() {
    return fetchOnce(() => memoriesCache, p => { memoriesCache = p },
            () => catalogApi.listMemories(),
            [])
}
function fetchVectorStores() {
    return fetchOnce(() => vectorstoreCache, p => { vectorstoreCache = p },
            () => catalogApi.listVectorStores(),
            [])
}
function fetchChains() {
    return fetchOnce(() => chainsCache, p => { chainsCache = p },
            () => catalogApi.listChains(),
            [])
}

/** Resets the module cache. Useful for tests and dev hot-reload. */
export function resetCatalogCache() {
    agentsCache = null
    assistantsCache = null
    toolsCache = null
    embeddingsCache = null
    memoriesCache = null
    vectorstoreCache = null
    chainsCache = null
}

export interface CatalogData {
    agents:       CatalogItem[]
    assistants:   CatalogItem[]
    tools:        CatalogItem[]
    embeddings:   CatalogItem[]
    memories:     CatalogItem[]
    vectorstores: CatalogItem[]
    chains:       CatalogItem[]
    loading:      boolean
}

/**
 * Hook that returns every catalog kind in one go. PropertyPanel (and
 * future palette/catalog pages) consume it to populate dropdowns
 * without hardcoded arrays.
 */
export function useCatalog(): CatalogData {
    const [agents,       setAgents]       = useState<CatalogItem[]>(FALLBACK_AGENTS)
    const [assistants,   setAssistants]   = useState<CatalogItem[]>(FALLBACK_ASSISTANTS)
    const [tools,        setTools]        = useState<CatalogItem[]>(FALLBACK_TOOLS)
    const [embeddings,   setEmbeddings]   = useState<CatalogItem[]>([])
    const [memories,     setMemories]     = useState<CatalogItem[]>([])
    const [vectorstores, setVectorStores] = useState<CatalogItem[]>([])
    const [chains,       setChains]       = useState<CatalogItem[]>([])
    const [loading,      setLoading]      = useState(true)

    useEffect(() => {
        let cancelled = false
        Promise.all([
            fetchAgents(), fetchAssistants(), fetchTools(),
            fetchEmbeddings(), fetchMemories(), fetchVectorStores(), fetchChains(),
        ]).then(([a, as, t, e, m, v, c]) => {
            if (cancelled) return
            // Trust whatever the fetchers resolved with: a successful HTTP
            // call returning [] means "no plugins registered for this
            // category", and the caller should be free to render the
            // empty-catalog branch (e.g. PropertyPanel's TextInput "Tool
            // ID" instead of Select "Tool"). Fallback is already applied
            // inside `fetchOnce` when the request itself fails.
            setAgents(a)
            setAssistants(as)
            setTools(t)
            setEmbeddings(e)
            setMemories(m)
            setVectorStores(v)
            setChains(c)
            setLoading(false)
        })
        return () => { cancelled = true }
    }, [])

    return { agents, assistants, tools, embeddings, memories, vectorstores, chains, loading }
}
