import type { TenantInfo, TenantLimits, TenantUsage, PlanType } from '../stores/useTenantStore'
import { mensagemDoCorpoDeErro } from '../lib/errorMessage'

const API_BASE = import.meta.env.VITE_API_BASE || '/api'

function getHeaders(): Record<string, string> {
  const token = sessionStorage.getItem('archflow_token')
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  }
  if (token) headers.Authorization = `Bearer ${token}`

  // Impersonation header
  const impersonating = sessionStorage.getItem('archflow_impersonate_tenant')
  if (impersonating) headers['X-Impersonate-Tenant'] = impersonating

  return headers
}

/**
 * Erro de uma chamada de admin: a frase legível em `message` e, quando o backend manda, o `code` do caso
 * (as recusas de usuários do workspace) — a tela traduz por ele (`lib/workspaceUserError`).
 */
export class AdminApiError extends Error {
  constructor(message: string, public readonly status: number, public readonly code: string | null) {
    super(message)
    this.name = 'AdminApiError'
  }
}

async function errorFrom(res: Response): Promise<AdminApiError> {
  // A frase do corpo ({message|error}), nunca o JSON cru — a tela mostra o que o servidor disse.
  const body = await res.text().catch(() => '')
  let code: string | null = null
  try {
    const parsed: unknown = JSON.parse(body)
    const value = parsed && typeof parsed === 'object' ? (parsed as { code?: unknown }).code : null
    code = typeof value === 'string' && value ? value : null
  } catch {
    // Corpo que não é JSON: sem código, só a frase.
  }
  return new AdminApiError(mensagemDoCorpoDeErro(body, res.statusText || `HTTP ${res.status}`), res.status, code)
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    method,
    headers: getHeaders(),
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) throw await errorFrom(res)
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
  /**
   * `archflow.admin.impersonation.enabled` do servidor. Desligada, toda requisição personificada leva 403
   * IMPERSONATION_DENIED, e a lista não oferece o "Entrar" (RCHFLW-198).
   */
  impersonationEnabled: boolean
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

/**
 * Um usuário do workspace — o do login de verdade (RCHFLW-85). `role` é o nome do papel no backend
 * (`ADMIN`, `DESIGNER`, `EXECUTOR`, `VIEWER`), a mesma lista de `userApi.roles()`.
 */
export interface TenantUser {
  id:           string
  name:         string
  email:        string
  role:         string | null
  status:       'active' | 'invited' | 'disabled'
  lastAccessAt: string | null
  invitationExpiresAt: string | null
}

/** Um papel do backend e as permissões (`recurso:acao`) que ele concede. */
export interface WorkspaceRole {
  name:        string
  description: string
  permissions: string[]
}

export interface InviteUserRequest {
  email: string
  role:  string
}

/** O convite criado. `invitationToken` só existe nesta resposta — o produto não envia e-mail. */
export interface InviteUserResponse {
  user:            TenantUser
  invitationToken: string
  expiresAt:       string
}

export const userApi = {
  list:    ()                           => request<TenantUser[]>('GET', '/admin/workspace/users'),
  roles:   ()                           => request<WorkspaceRole[]>('GET', '/admin/workspace/roles'),
  invite:  (data: InviteUserRequest)    => request<InviteUserResponse>('POST', '/admin/workspace/users/invite', data),
  update:  (id: string, role: string)   => request<TenantUser>('PUT', `/admin/workspace/users/${id}`, { role }),
  remove:  (id: string)                 => request<void>('DELETE', `/admin/workspace/users/${id}`),
  revoke:  (id: string)                 => request<void>('POST', `/admin/workspace/users/${id}/revoke`),
}

/** O link de aceite que o administrador repassa ao convidado. */
export function invitationLink(token: string, origin: string = window.location.origin): string {
  return `${origin}/accept-invite?token=${encodeURIComponent(token)}`
}

/**
 * Aceite do convite (rota pública): sem token de sessão e sem o redirecionamento do 401 — quem aceita
 * ainda não tem login.
 */
export async function acceptInvitation(token: string, password: string): Promise<{ username: string }> {
  const res = await fetch(`${API_BASE}/auth/invitations/accept`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token, password }),
  })
  if (!res.ok) throw await errorFrom(res)
  return res.json()
}

/** Consumo de modelo medido. Custo `null` = sem preço declarado (nunca zero). */
export interface MeasuredUsage {
  executions:             number
  tokensInput:            number
  tokensOutput:           number
  costCents:              number | null
  costReais:              number | null
  declaredCostUsd:        number | null
  executionsWithoutPrice: number
}

/**
 * O resumo do workspace, com números medidos (RCHFLW-75). `plan`/`status`/`limits` vêm do cadastro do
 * tenant e são `null` quando ele não tem cadastro — o limite não aparece, em vez de inventado.
 */
export interface WorkspaceSummary {
  tenantId: string
  tenantName: string
  plan: PlanType | null
  status: 'active' | 'trial' | 'suspended' | null
  executionsToday: number
  tokensThisMonth: number
  workflowCount: number
  userCount: number
  apiKeyCount: number
  usageThisMonth: MeasuredUsage
  limits: {
    executionsPerDay: number
    tokensPerMonth: number
    maxWorkflows: number
    maxUsers: number
    allowedModels: string[]
  } | null
}

export const workspaceApi = {
  summary: () => request<WorkspaceSummary>('GET', '/admin/workspace/summary'),
}

// ── Global Config (superadmin) ──────────────────────────────────

export interface LLMModel {
  id:            string
  name:          string
  /** Id do provider (`openrouter`, `openai`...). */
  provider:      string
  status:        'active' | 'beta' | 'deprecated'
  /** `null` = custo não informado (nunca um zero inventado; zero é "grátis"). */
  costInputPer1M:  number | null
  costOutputPer1M: number | null
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
  // Id do OpenRouter tem barra (openai/gpt-6-luna): cada segmento é codificado, a barra fica (o servidor
  // captura o resto do caminho; %2F seria recusado pelo firewall do Spring Security).
  toggleModel:  (id: string, active: boolean) =>
    request<void>('PUT', `/admin/global/models/${id.split('/').map(encodeURIComponent).join('/')}`, { active }),
  getPlanDefaults: () => request<PlanDefaults[]>('GET', '/admin/global/plans'),
  updatePlanDefaults: (plan: PlanType, data: Partial<PlanDefaults>) =>
    request<void>('PUT', `/admin/global/plans/${plan}`, data),
  getToggles:   () => request<GlobalFeatureToggles>('GET', '/admin/global/toggles'),
  updateToggles:(data: Partial<GlobalFeatureToggles>) => request<void>('PUT', '/admin/global/toggles', data),
}

// ── Usage & Billing (superadmin) ────────────────────────────────

/** O consumo medido de um tenant no mês. Custo `null` = nenhuma execução tinha preço declarado. */
export interface TenantUsageRow {
  tenantId:               string
  tenantName:             string
  executions:             number
  tokensInput:            number
  tokensOutput:           number
  costCents:              number | null
  costReais:              number | null
  declaredCostUsd:        number | null
  executionsWithoutPrice: number
  percentOfTotal:         number
  /** Tokens/mês do plano; `null` quando o tenant não tem cadastro. */
  planLimit:              number | null
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
