package br.com.archflow.api.admin;

import br.com.archflow.api.admin.dto.TenantDto;
import br.com.archflow.api.admin.dto.TenantDto.*;

import java.util.List;

/**
 * REST controller for tenant management (superadmin only).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET    /api/admin/tenants       — List all tenants</li>
 *   <li>GET    /api/admin/tenants/stats  — Aggregated stats</li>
 *   <li>POST   /api/admin/tenants       — Create tenant</li>
 *   <li>GET    /api/admin/tenants/{id}  — Get tenant detail</li>
 *   <li>PUT    /api/admin/tenants/{id}  — Update tenant</li>
 *   <li>POST   /api/admin/tenants/{id}/suspend  — Suspend tenant</li>
 *   <li>POST   /api/admin/tenants/{id}/activate — Activate tenant</li>
 *   <li>DELETE /api/admin/tenants/{id}  — Delete tenant</li>
 * </ul>
 */
public interface TenantController {
    List<TenantDto> listTenants();
    TenantStatsDto getStats();
    TenantDto createTenant(CreateTenantRequest request);
    TenantDto getTenant(String tenantId);
    TenantDto updateTenant(String tenantId, TenantDto update);
    void suspendTenant(String tenantId);
    void activateTenant(String tenantId);
    void deleteTenant(String tenantId);
}
