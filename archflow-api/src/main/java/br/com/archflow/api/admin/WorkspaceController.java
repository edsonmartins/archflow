package br.com.archflow.api.admin;

import br.com.archflow.api.admin.dto.UserDto;
import br.com.archflow.api.admin.dto.UserDto.*;
import br.com.archflow.api.admin.dto.ApiKeyDto;
import br.com.archflow.api.admin.dto.ApiKeyDto.*;

import java.util.List;

/**
 * REST controller for tenant workspace management (tenant_admin).
 *
 * <p>All endpoints are tenant-scoped — the tenantId is resolved from
 * the JWT token or the X-Impersonate-Tenant header.
 *
 * <p>User endpoints:
 * <ul>
 *   <li>GET    /api/admin/workspace/users          — List users</li>
 *   <li>POST   /api/admin/workspace/users/invite    — Invite user</li>
 *   <li>PUT    /api/admin/workspace/users/{id}      — Update role</li>
 *   <li>DELETE /api/admin/workspace/users/{id}      — Remove user</li>
 *   <li>POST   /api/admin/workspace/users/{id}/revoke — Revoke invite</li>
 * </ul>
 *
 * <p>API Key endpoints:
 * <ul>
 *   <li>GET    /api/admin/workspace/keys     — List keys</li>
 *   <li>POST   /api/admin/workspace/keys     — Create key</li>
 *   <li>DELETE /api/admin/workspace/keys/{id} — Revoke key</li>
 * </ul>
 */
public interface WorkspaceController {
    // Users
    List<UserDto> listUsers();
    UserDto inviteUser(InviteUserRequest request);
    UserDto updateUserRole(String userId, UpdateRoleRequest request);
    void removeUser(String userId);
    void revokeInvite(String userId);

    // API Keys
    List<ApiKeyDto> listApiKeys();
    CreateApiKeyResponse createApiKey(CreateApiKeyRequest request);
    void revokeApiKey(String keyId);
}
