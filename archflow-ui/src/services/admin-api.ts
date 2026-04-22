import type { TenantInfo, TenantLimits, TenantUsage, PlanType } from '../stores/useTenantStore'

const API_BASE = import.meta.env.VITE_API_BASE || '/api'

function getHeaders(): Record<string, string> {
  const token = localStorage.getItem('archflow_token')
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  }
  if (token) headers.Authorization = `Bearer ${token}`

  // Impersonation header
  const impersonating = sessionStorage.getItem('archflow_impersonate_tenant')
  if (impersonating) headers['X-Impersonate-Tenant'] = impersonating

  return headers
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    method,
    headers: getHeaders(),
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) {
    const error = await res.text()
    throw new Error(error || `Request failed: ${res.status}`)
  }
  if (res.status === 204) return undefined as T
  return res.json()
}

// ── Tenants (superadmin) ────────────────────────────────────────

export interface TenantDetail extends TenantInfo {
  adminEmail:     string
  sector:         string
  limits:         TenantLimits
  usage:          TenantUsage
  createdAt:      string
  allowedModels:  string[]
}

export interface CreateTenantRequest {
  name:           string
  tenantId:       string
  adminEmail:     string
  sector:         string
  plan:           PlanType
  expiresAt?:     string
  limits?:        Partial<TenantLimits>
  allowedModels?: string[]
}

export interface TenantStats {
  totalActive:       number
  totalTrial:        number
  executionsToday:   number
  tokensThisMonth:   number
}

export const tenantApi = {
  list:    ()                          => request<TenantInfo[]>('GET', '/admin/tenants'),
  get:     (id: string)                => request<TenantDetail>('GET', `/admin/tenants/${id}`),
  create:  (data: CreateTenantRequest) => request<TenantDetail>('POST', '/admin/tenants', data),
  update:  (id: string, data: Partial<TenantDetail>) => request<TenantDetail>('PUT', `/admin/tenants/${id}`, data),
  suspend: (id: string)                => request<void>('POST', `/admin/tenants/${id}/suspend`),
  activate:(id: string)                => request<void>('POST', `/admin/tenants/${id}/activate`),
  delete:  (id: string)                => request<void>('DELETE', `/admin/tenants/${id}`),
  stats:   ()                          => request<TenantStats>('GET', '/admin/tenants/stats'),
}

// ── Users (tenant admin) ────────────────────────────────────────

export interface TenantUser {
  id:           string
  name:         string
  email:        string
  role:         string
  status:       'active' | 'invited'
  lastAccessAt: string | null
  workflowCount: number
}

export interface InviteUserRequest {
  email: string
  role:  string
}

export const userApi = {
  list:    ()                           => request<TenantUser[]>('GET', '/admin/workspace/users'),
  invite:  (data: InviteUserRequest)    => request<TenantUser>('POST', '/admin/workspace/users/invite', data),
  update:  (id: string, role: string)   => request<TenantUser>('PUT', `/admin/workspace/users/${id}`, { role }),
  remove:  (id: string)                 => request<void>('DELETE', `/admin/workspace/users/${id}`),
  revoke:  (id: string)                 => request<void>('POST', `/admin/workspace/users/${id}/revoke`),
}

export interface WorkspaceSummary {
  tenantId: string
  tenantName: string
  plan: PlanType
  status: 'active' | 'trial' | 'suspended'
  executionsToday: number
  tokensThisMonth: number
  workflowCount: number
  userCount: number
  apiKeyCount: number
  limits: {
    executionsPerDay: number
    tokensPerMonth: number
    maxWorkflows: number
    maxUsers: number
    allowedModels: string[]
  }
}

export const workspaceApi = {
  summary: () => request<WorkspaceSummary>('GET', '/admin/workspace/summary'),
}

// ── API Keys (tenant admin) ─────────────────────────────────────

export interface ApiKey {
  id:         string
  name:       string
  type:       'production' | 'staging' | 'web_component'
  prefix:     string
  maskedKey:  string
  createdAt:  string
  lastUsedAt: string | null
}

export interface CreateApiKeyRequest {
  name: string
  type: 'production' | 'staging' | 'web_component'
}

export interface CreateApiKeyResponse extends ApiKey {
  fullKey: string  // only shown once
}

export const apiKeyApi = {
  list:    ()                            => request<ApiKey[]>('GET', '/admin/workspace/keys'),
  create:  (data: CreateApiKeyRequest)   => request<CreateApiKeyResponse>('POST', '/admin/workspace/keys', data),
  revoke:  (id: string)                  => request<void>('DELETE', `/admin/workspace/keys/${id}`),
}

// ── Global Config (superadmin) ──────────────────────────────────

export interface LLMModel {
  id:            string
  name:          string
  provider:      string
  status:        'active' | 'beta' | 'deprecated'
  costInputPer1M:  number
  costOutputPer1M: number
}

export interface PlanDefaults {
  plan:              PlanType
  executionsPerDay:  number
  tokensPerMonth:    number
  maxWorkflows:      number
  maxUsers:          number
}

export interface GlobalFeatureToggles {
  allowLocalModels:    boolean
  humanInTheLoop:      boolean
  brainSentry:         boolean
  debugMode:           boolean
  linktorNotifications: boolean
  auditLog:            boolean
}

export const globalConfigApi = {
  getModels:    () => request<LLMModel[]>('GET', '/admin/global/models'),
  toggleModel:  (id: string, active: boolean) => request<void>('PUT', `/admin/global/models/${id}`, { active }),
  getPlanDefaults: () => request<PlanDefaults[]>('GET', '/admin/global/plans'),
  updatePlanDefaults: (plan: PlanType, data: Partial<PlanDefaults>) =>
    request<void>('PUT', `/admin/global/plans/${plan}`, data),
  getToggles:   () => request<GlobalFeatureToggles>('GET', '/admin/global/toggles'),
  updateToggles:(data: Partial<GlobalFeatureToggles>) => request<void>('PUT', '/admin/global/toggles', data),
}

// ── Usage & Billing (superadmin) ────────────────────────────────

export interface TenantUsageRow {
  tenantId:      string
  tenantName:    string
  executions:    number
  tokensInput:   number
  tokensOutput:  number
  estimatedCost: number
  percentOfTotal: number
  planLimit:     number
}

export const usageApi = {
  byTenant: (month: string) => request<TenantUsageRow[]>('GET', `/admin/global/usage?month=${month}`),
  exportCsv:(month: string) => `${API_BASE}/admin/global/usage/export?month=${month}`,
}

// ── Audit Log (superadmin) ──────────────────────────────────────

export interface AuditEntry {
  id:        string
  timestamp: string
  actor:     string
  action:    string
  details:   string
}

export const auditApi = {
  recent: (limit = 10) => request<AuditEntry[]>('GET', `/admin/global/audit?limit=${limit}`),
}
