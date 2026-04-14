/**
 * Client for /api/workflow/* read-only metadata endpoints consumed by
 * the visual workflow editor (PropertyPanel dropdowns). Mirrors the
 * Java `WorkflowConfigController` contract.
 */

const API_BASE = import.meta.env.VITE_API_BASE || '/api'

function getHeaders(): Record<string, string> {
  const token = localStorage.getItem('archflow_token')
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`
  const impersonating = sessionStorage.getItem('archflow_impersonate_tenant')
  if (impersonating) headers['X-Impersonate-Tenant'] = impersonating
  return headers
}

async function getJson<T>(path: string): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, { method: 'GET', headers: getHeaders() })
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
  return res.json() as Promise<T>
}

// ── Types (mirror the backend DTOs) ─────────────────────────────

export interface ProviderModel {
  id:             string
  name:           string
  contextWindow:  number
  maxTemperature: number
}

export interface ProviderInfo {
  id:                string
  displayName:       string
  requiresApiKey:    boolean
  supportsStreaming: boolean
  group:             'Cloud' | 'Local'
  models:            ProviderModel[]
}

export interface AgentPatternInfo {
  id:          string
  label:       string
  description: string
}

export interface PersonaInfo {
  id:          string
  label:       string
  description: string
  promptId:    string
}

export interface GovernanceProfileInfo {
  id:                   string
  name:                 string
  systemPrompt:         string
  enabledTools:         string[]
  disabledTools:        string[]
  escalationThreshold:  number
  maxToolExecutions:    number
  customInstructions:   string
}

export interface McpServerInfo {
  name:      string
  transport: 'stdio' | 'sse'
  command:   string | null
  url:       string | null
  toolCount: number
}

// ── Service ─────────────────────────────────────────────────────

export const workflowConfigApi = {
  getProviders:          (): Promise<ProviderInfo[]>          => getJson('/workflow/providers'),
  getAgentPatterns:      (): Promise<AgentPatternInfo[]>      => getJson('/workflow/agent-patterns'),
  getPersonas:           (): Promise<PersonaInfo[]>           => getJson('/workflow/personas'),
  getGovernanceProfiles: (): Promise<GovernanceProfileInfo[]> => getJson('/workflow/governance-profiles'),
  getMcpServers:         (): Promise<McpServerInfo[]>         => getJson('/workflow/mcp-servers'),
}
