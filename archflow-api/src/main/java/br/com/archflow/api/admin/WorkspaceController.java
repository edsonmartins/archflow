package br.com.archflow.api.admin;

import br.com.archflow.api.admin.dto.UserDto;
import br.com.archflow.api.admin.dto.UserDto.*;
import br.com.archflow.api.admin.dto.WorkspaceSummaryDto;

import java.util.List;

/**
 * REST controller for tenant workspace management (tenant_admin).
 *
 * <p>All endpoints are tenant-scoped — the tenantId is resolved from
 * the JWT token or the X-Impersonate-Tenant header.
 *
 * <p>User endpoints — sobre o repositório real do login (RCHFLW-85):
 * <ul>
 *   <li>GET    /api/admin/workspace/users          — List users</li>
 *   <li>GET    /api/admin/workspace/roles          — The backend roles (for the picker)</li>
 *   <li>POST   /api/admin/workspace/users/invite    — Invite user (returns the acceptance token once)</li>
 *   <li>PUT    /api/admin/workspace/users/{id}      — Update role</li>
 *   <li>DELETE /api/admin/workspace/users/{id}      — Remove user</li>
 *   <li>POST   /api/admin/workspace/users/{id}/revoke — Revoke invite</li>
 * </ul>
 *
 * <p>As chaves de API do workspace são as do {@code ApiKeyService} ({@code /api/apikeys}); a tela
 * de chaves em memória que existia aqui foi removida — as chaves dela nunca autenticavam nada.
 */
public interface WorkspaceController {
    WorkspaceSummaryDto getSummary();

    // Users
    List<UserDto> listUsers();
    List<RoleDto> listRoles();
    InviteUserResponse inviteUser(InviteUserRequest request);
    UserDto updateUserRole(String userId, UpdateRoleRequest request);
    void removeUser(String userId);
    void revokeInvite(String userId);
}
