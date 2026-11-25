import { useAuthStore } from '../stores/auth-store';

/**
 * Permissões de fluxos e execuções (RCHFLW-74), no vocabulário do backend (`recurso:acao`).
 *
 * É o espelho do que o servidor aplica (`WorkflowPermissionInterceptor`): os papéis built-in de
 * `Role.java` (createViewerRole, createExecutorRole, createDesignerRole) e ADMIN/SUPERADMIN com tudo.
 * Quem decide é o servidor (403 com a permissão que faltou); a UI só deixa de oferecer o que ele
 * recusaria. Mudou a matriz de um lado, muda do outro.
 */
export type Permission =
    | 'workflow:read'
    | 'workflow:create'
    | 'workflow:update'
    | 'workflow:delete'
    | 'workflow:execute'
    | 'execution:read'
    | 'execution:manage';

const ROLE_PERMISSIONS: Record<string, readonly Permission[]> = {
    DESIGNER: ['workflow:read', 'workflow:create', 'workflow:update', 'workflow:delete', 'workflow:execute'],
    EXECUTOR: ['workflow:read', 'workflow:execute'],
    VIEWER:   ['workflow:read', 'execution:read'],
};

const ADMIN_ROLES = ['ADMIN', 'SUPERADMIN'];

function normalize(role: string): string {
    return role.trim().replace(/^ROLE_/i, '').toUpperCase();
}

/** True quando algum dos papéis concede a permissão. Papel desconhecido não concede nada. */
export function rolesGrant(roles: readonly string[], permission: Permission): boolean {
    return roles.some((raw) => {
        const role = normalize(raw);
        return ADMIN_ROLES.includes(role) || (ROLE_PERMISSIONS[role]?.includes(permission) ?? false);
    });
}

/**
 * Os papéis do access token — o mesmo claim `roles` que o backend confere. Lidos do token (e não do
 * `/auth/me`) porque o token sobrevive ao recarregar a página e é ele que o servidor valida.
 * Token ausente ou ilegível: nenhum papel, logo nenhuma ação de escrita.
 */
export function rolesFromToken(token: string | null | undefined): string[] {
    if (!token) return [];
    try {
        const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
        return Array.isArray(payload.roles) ? payload.roles.map(String) : [];
    } catch {
        return [];
    }
}

/** Hook: `can('workflow:update')` diz se o usuário logado pode fazer a ação. */
export function usePermissions(): (permission: Permission) => boolean {
    const token = useAuthStore((s) => s.token);
    const roles = rolesFromToken(token);
    return (permission) => rolesGrant(roles, permission);
}
