import { useAuthStore } from '../stores/auth-store';

/**
 * Permissões de fluxos e execuções (RCHFLW-74), no vocabulário do backend (`recurso:acao`).
 *
 * É o espelho do que o servidor aplica (`WorkflowPermissionInterceptor`): os papéis built-in de
 * `Role.java` (createViewerRole, createExecutorRole, createDesignerRole) e ADMIN/SUPERADMIN com tudo.
 * Quem decide é o servidor (403 com a permissão que faltou); a UI só deixa de oferecer o que ele
 * recusaria. Mudou a matriz de um lado, muda do outro.
 *
 * QA2 (RCHFLW-136/158): a mesma tabela cobre agora as outras escritas que o servidor passou a recusar
 * por papel — decidir aprovação (`execution:manage`), acionar agente/LLM (`workflow:execute`), gatilhos,
 * bases de conhecimento e arquivos (`workflow:update`), jobs (`execution:manage`) e skills
 * (`agent:manage`). Linktor, Brain Sentry e extensões não têm permissão: são do papel ADMIN
 * (`AdminRoleInterceptor`), que aqui é `isAdmin`. Nenhuma permissão nova: são os nomes de `ApiKeyScope`.
 */
export type Permission =
    | 'workflow:read'
    | 'workflow:create'
    | 'workflow:update'
    | 'workflow:delete'
    | 'workflow:execute'
    | 'execution:read'
    | 'execution:manage'
    | 'agent:manage';

const ROLE_PERMISSIONS: Record<string, readonly Permission[]> = {
    DESIGNER: ['workflow:read', 'workflow:create', 'workflow:update', 'workflow:delete', 'workflow:execute'],
    EXECUTOR: ['workflow:read', 'workflow:execute'],
    VIEWER:   ['workflow:read', 'execution:read'],
};

const ADMIN_ROLES = ['ADMIN', 'SUPERADMIN'];

function normalize(role: string): string {
    return role.trim().replace(/^ROLE_/i, '').toUpperCase();
}

/**
 * True quando algum dos papéis é ADMIN/SUPERADMIN — o que o `AdminRoleInterceptor` do servidor exige em
 * `/api/admin/linktor`, `/api/admin/brainsentry` e nas escritas de `/api/extensions`.
 */
export function rolesAreAdmin(roles: readonly string[]): boolean {
    return roles.some((raw) => ADMIN_ROLES.includes(normalize(raw)));
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

/** Hook: se o usuário logado tem o papel ADMIN (ver {@link rolesAreAdmin}). */
export function useIsAdmin(): boolean {
    const token = useAuthStore((s) => s.token);
    return rolesAreAdmin(rolesFromToken(token));
}
