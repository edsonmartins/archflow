import { useEffect, useState } from 'react'
import {
  workflowConfigApi,
  type AgentPatternInfo,
  type GovernanceProfileInfo,
  type McpServerInfo,
  type PersonaInfo,
  type ProviderInfo,
} from '../../services/workflow-config-api'

/**
 * Fallback provider catalog used while the API call is in flight or
 * when the endpoint is unreachable (e.g. offline dev, tests). Mirrors
 * a subset of the backend LLMProvider enum so the editor never shows
 * an empty dropdown — users can still work without a live backend.
 */
const FALLBACK_PROVIDERS: ProviderInfo[] = [
  {
    id: 'anthropic', displayName: 'Anthropic', requiresApiKey: true, supportsStreaming: true, group: 'Cloud',
    models: [
      { id: 'claude-sonnet-4-6', name: 'Claude Sonnet 4.6', contextWindow: 200000, maxTemperature: 1.0 },
      { id: 'claude-opus-4-6',   name: 'Claude Opus 4.6',   contextWindow: 200000, maxTemperature: 1.0 },
    ],
  },
  {
    id: 'openai', displayName: 'OpenAI', requiresApiKey: true, supportsStreaming: true, group: 'Cloud',
    models: [
      { id: 'gpt-4o',      name: 'GPT-4o',      contextWindow: 128000, maxTemperature: 2.0 },
      { id: 'gpt-4o-mini', name: 'GPT-4o Mini', contextWindow: 128000, maxTemperature: 2.0 },
    ],
  },
  {
    id: 'ollama', displayName: 'Ollama (local)', requiresApiKey: false, supportsStreaming: true, group: 'Local',
    models: [
      { id: 'llama3.3', name: 'Llama 3.3', contextWindow: 128000, maxTemperature: 2.0 },
    ],
  },
]

const FALLBACK_PATTERNS: AgentPatternInfo[] = [
  { id: 'react',            label: 'ReAct (Reason + Act)',                    description: 'Iterative thought-action-observation loop. Best for multi-step tool use.' },
  { id: 'plan-execute',     label: 'Plan and Execute',                        description: 'Separates planning from execution. Cost-efficient for complex tasks.' },
  { id: 'rewoo',            label: 'ReWOO (Reasoning Without Observation)',   description: 'Plans all tool calls upfront. ~82% fewer tokens than ReAct.' },
  { id: 'chain-of-thought', label: 'Chain of Thought',                        description: 'Multiple reasoning paths with majority vote. Best for analytical tasks.' },
]

// Module-level cache so every PropertyPanel instance reuses the result.
let providersCache: Promise<ProviderInfo[]> | null = null
let patternsCache: Promise<AgentPatternInfo[]> | null = null
let personasCache: Promise<PersonaInfo[]> | null = null
let governanceCache: Promise<GovernanceProfileInfo[]> | null = null
let mcpServersCache: Promise<McpServerInfo[]> | null = null

function fetchProviders(): Promise<ProviderInfo[]> {
  if (!providersCache) {
    providersCache = workflowConfigApi.getProviders().catch(() => FALLBACK_PROVIDERS)
  }
  return providersCache
}
function fetchPatterns(): Promise<AgentPatternInfo[]> {
  if (!patternsCache) {
    patternsCache = workflowConfigApi.getAgentPatterns().catch(() => FALLBACK_PATTERNS)
  }
  return patternsCache
}
function fetchPersonas(): Promise<PersonaInfo[]> {
  if (!personasCache) {
    personasCache = workflowConfigApi.getPersonas().catch(() => [])
  }
  return personasCache
}
function fetchGovernance(): Promise<GovernanceProfileInfo[]> {
  if (!governanceCache) {
    governanceCache = workflowConfigApi.getGovernanceProfiles().catch(() => [])
  }
  return governanceCache
}
function fetchMcpServers(): Promise<McpServerInfo[]> {
  if (!mcpServersCache) {
    mcpServersCache = workflowConfigApi.getMcpServers().catch(() => [])
  }
  return mcpServersCache
}

/** Resets the module cache. Useful for tests and dev hot-reload. */
export function resetWorkflowConfigCache() {
  providersCache = null
  patternsCache = null
  personasCache = null
  governanceCache = null
  mcpServersCache = null
}

export interface WorkflowConfigData {
  providers:   ProviderInfo[]
  patterns:    AgentPatternInfo[]
  personas:    PersonaInfo[]
  governance:  GovernanceProfileInfo[]
  mcpServers:  McpServerInfo[]
  loading:     boolean
}

/**
 * Hook used by PropertyPanel to fetch all editor metadata in one go.
 * Returns fallback data until the requests resolve so the dropdowns
 * never render empty.
 */
export function useWorkflowConfig(): WorkflowConfigData {
  const [providers,  setProviders]  = useState<ProviderInfo[]>(FALLBACK_PROVIDERS)
  const [patterns,   setPatterns]   = useState<AgentPatternInfo[]>(FALLBACK_PATTERNS)
  const [personas,   setPersonas]   = useState<PersonaInfo[]>([])
  const [governance, setGovernance] = useState<GovernanceProfileInfo[]>([])
  const [mcpServers, setMcpServers] = useState<McpServerInfo[]>([])
  const [loading,    setLoading]    = useState(true)

  useEffect(() => {
    let cancelled = false
    Promise.all([
      fetchProviders(),
      fetchPatterns(),
      fetchPersonas(),
      fetchGovernance(),
      fetchMcpServers(),
    ]).then(([ps, ap, pe, go, mc]) => {
      if (cancelled) return
      setProviders(ps.length > 0 ? ps : FALLBACK_PROVIDERS)
      setPatterns(ap.length > 0 ? ap : FALLBACK_PATTERNS)
      setPersonas(pe)
      setGovernance(go)
      setMcpServers(mc)
      setLoading(false)
    })
    return () => { cancelled = true }
  }, [])

  return { providers, patterns, personas, governance, mcpServers, loading }
}
