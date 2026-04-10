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
  impersonating: null,
  tenantLimits:  null,
  tenantUsage:   null,
  tenants:       [],
  loading:       false,

  setRole:          (role)   => set({ currentRole: role }),
  setCurrentTenant: (tenant) => set({ currentTenant: tenant }),

  startImpersonation: (tenant) => set({ impersonating: tenant }),

  exitImpersonation: () => set({ impersonating: null }),

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
