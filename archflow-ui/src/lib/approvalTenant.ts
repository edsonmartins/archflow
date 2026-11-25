import type { TenantInfo, UserRole } from '../stores/useTenantStore'

/**
 * Qual `tenantId` a UI manda para a fila de aprovações.
 *
 * - personificando um tenant: o id dele (o servidor confere o papel no header de personificação);
 * - superadmin: `'all'`, a visão de todos os tenants;
 * - qualquer outro papel: `undefined` — o parâmetro não vai, e o servidor usa o tenant da própria
 *   requisição (o do token).
 *
 * Antes a UI mandava `'default'`, um tenant que não era o de ninguém: as aprovações das execuções
 * eram gravadas sob outro id e a fila de quem não era superadmin ficava sempre vazia (RCHFLW-13).
 */
export function approvalTenantParam(
    impersonating: Pick<TenantInfo, 'id'> | null,
    role: UserRole,
): string | undefined {
    if (impersonating?.id) return impersonating.id
    if (role === 'superadmin') return 'all'
    return undefined
}
