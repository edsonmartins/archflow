import { useAuthStore } from '../stores/auth-store'
import { useTenantStore, type TenantInfo } from '../stores/useTenantStore'
import { rolesFromToken } from './permissions'

/**
 * Tenant padrão do servidor (`DefaultTenant.ID`): é nele que cai um access token sem a claim
 * `tenantId` (ImpersonationFilter). Repetido aqui só para a UI mostrar o tenant em que a ação vai rodar.
 */
export const SERVER_DEFAULT_TENANT = '__default__'

const ADMIN_ROLES = ['ADMIN', 'SUPERADMIN']

export interface ActingTenant {
    /** O tenant em que a ação roda; `null` sem login (auth desligada): o formulário decide, como antes. */
    tenantId: string | null
    /** `true` quando o usuário não pode escolher outro tenant — o servidor recusaria (403). */
    locked: boolean
}

function claimTenant(token: string): string | null {
    try {
        const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
        return typeof payload.tenantId === 'string' && payload.tenantId.trim() ? payload.tenantId.trim() : null
    } catch {
        return null
    }
}

/**
 * Em que tenant um gatilho agendado ou uma invocação do Agent lab roda (RCHFLW-103).
 *
 * <p>O servidor usa o tenant da identidade (`TenantScope.forAction`): o do token, ou o personificado;
 * outro tenant só com o papel ADMIN/SUPERADMIN. A UI segue a mesma regra: quem não é admin vê o próprio
 * tenant, sem poder trocá-lo; o admin começa no tenant em que está e pode escolher outro. Sem token
 * (auth desligada, dev) nada muda: o campo é livre.</p>
 */
export function actingTenant(token: string | null | undefined,
                             impersonating: Pick<TenantInfo, 'id'> | null): ActingTenant {
    if (!token) return { tenantId: null, locked: false }
    const roles = rolesFromToken(token).map(r => r.trim().replace(/^ROLE_/i, '').toUpperCase())
    const proprio = claimTenant(token) ?? SERVER_DEFAULT_TENANT
    if (roles.some(r => ADMIN_ROLES.includes(r))) {
        return { tenantId: impersonating?.id ?? proprio, locked: false }
    }
    return { tenantId: proprio, locked: true }
}

/** Hook de {@link actingTenant} com o token e a personificação da sessão. */
export function useActingTenant(): ActingTenant {
    const token = useAuthStore((s) => s.token)
    const impersonating = useTenantStore((s) => s.impersonating)
    return actingTenant(token, impersonating)
}
