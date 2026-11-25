import { create } from 'zustand'

export type UserRole = 'superadmin' | 'tenant_admin' | 'editor' | 'viewer'
export type PlanType = 'enterprise' | 'professional' | 'trial' | 'internal'
export type TenantStatus = 'active' | 'trial' | 'suspended'

export interface TenantInfo {
  id:     string
  name:   string
  plan:   PlanType
  status: TenantStatus
}

export interface TenantLimits {
  executionsPerDay:   number
  tokensPerMonth:     number
  maxWorkflows:       number
  maxUsers:           number
  allowedModels:      string[]
  featuresEnabled:    string[]
}

export interface TenantUsage {
  executionsToday:    number
  tokensThisMonth:    number
  workflowCount:      number
  userCount:          number
}

interface TenantStore {
  currentRole:   UserRole
  currentTenant: TenantInfo | null
  impersonating: TenantInfo | null
  tenantLimits:  TenantLimits | null
  tenantUsage:   TenantUsage  | null
  tenants:       TenantInfo[]
  loading:       boolean

  setRole:              (role: UserRole) => void
  setCurrentTenant:     (tenant: TenantInfo | null) => void
  startImpersonation:   (tenant: TenantInfo) => void
  exitImpersonation:    () => void
  setTenants:           (tenants: TenantInfo[]) => void
  setLimits:            (limits: TenantLimits) => void
  setUsage:             (usage: TenantUsage) => void
  setLoading:           (loading: boolean) => void
  isSuperadmin:         () => boolean
  effectiveRole:        () => UserRole
}

/** O tenant personificado vai no cabeçalho X-Impersonate-Tenant de toda requisição (services/*-api). */
const IMPERSONATE_KEY = 'archflow_impersonate_tenant'
/** Id e nome do tenant personificado, para a faixa voltar depois de recarregar a página (RCHFLW-96). */
const IMPERSONATE_INFO_KEY = 'archflow_impersonate_tenant_info'

/**
 * A personificação que sobreviveu ao recarregar a página.
 *
 * <p>O cabeçalho sai do sessionStorage, que sobrevive ao F5; a faixa saía só do estado em memória, que
 * não. Depois do F5 o superadmin seguia agindo no tenant sem faixa nem "Voltar ao superadmin" (RCHFLW-96).
 * Agora a faixa volta com os dados guardados ao entrar. Se não houver como mostrá-la — dados do tenant
 * ausentes ou de outro id, ou quem está logado não é superadmin —, a personificação termina aqui: nunca
 * fica ativa sem aparecer.</p>
 */
function getInitialImpersonation(role: UserRole): TenantInfo | null {
  try {
    const id = sessionStorage.getItem(IMPERSONATE_KEY)
    if (!id) return null
    const raw = sessionStorage.getItem(IMPERSONATE_INFO_KEY)
    const info = raw ? JSON.parse(raw) as Partial<TenantInfo> : null
    if (role === 'superadmin' && info && info.id === id && typeof info.name === 'string') {
      return info as TenantInfo
    }
    sessionStorage.removeItem(IMPERSONATE_KEY)
    sessionStorage.removeItem(IMPERSONATE_INFO_KEY)
  } catch { /* SSR / storage indisponível */ }
  return null
}

function getInitialRole(): UserRole {
  try {
    const stored = sessionStorage.getItem('archflow_role')
    if (stored && ['superadmin', 'tenant_admin', 'editor', 'viewer'].includes(stored)) {
      return stored as UserRole
    }
  } catch { /* SSR safe */ }
  return 'viewer'
}

export const useTenantStore = create<TenantStore>((set, get) => ({
  currentRole:   getInitialRole(),
  currentTenant: null,
  impersonating: getInitialImpersonation(getInitialRole()),
  tenantLimits:  null,
  tenantUsage:   null,
  tenants:       [],
  loading:       false,

  setRole: (role) => {
    try {
      sessionStorage.setItem('archflow_role', role)
    } catch { /* SSR safe */ }
    set({ currentRole: role })
  },
  setCurrentTenant: (tenant) => set({ currentTenant: tenant }),

  startImpersonation: (tenant) => {
    try {
      sessionStorage.setItem(IMPERSONATE_KEY, tenant.id)
      sessionStorage.setItem(IMPERSONATE_INFO_KEY, JSON.stringify(tenant))
    } catch { /* SSR safe */ }
    set({ impersonating: tenant })
  },

  exitImpersonation: () => {
    try {
      sessionStorage.removeItem(IMPERSONATE_KEY)
      sessionStorage.removeItem(IMPERSONATE_INFO_KEY)
    } catch { /* SSR safe */ }
    set({ impersonating: null })
  },

  setTenants: (tenants) => set({ tenants }),
  setLimits:  (limits)  => set({ tenantLimits: limits }),
  setUsage:   (usage)   => set({ tenantUsage: usage }),
  setLoading: (loading) => set({ loading }),

  isSuperadmin: () => get().currentRole === 'superadmin',

  effectiveRole: () => {
    const { currentRole, impersonating } = get()
    if (currentRole === 'superadmin' && impersonating) return 'tenant_admin'
    return currentRole
  },
}))
