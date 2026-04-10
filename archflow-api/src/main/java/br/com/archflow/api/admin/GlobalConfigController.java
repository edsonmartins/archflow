package br.com.archflow.api.admin;

import br.com.archflow.api.admin.dto.GlobalConfigDto.*;

import java.util.List;

/**
 * REST controller for global platform configuration (superadmin only).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET  /api/admin/global/models    — List LLM models</li>
 *   <li>PUT  /api/admin/global/models/{id} — Toggle model active/inactive</li>
 *   <li>GET  /api/admin/global/plans     — Get plan defaults</li>
 *   <li>PUT  /api/admin/global/plans/{plan} — Update plan defaults</li>
 *   <li>GET  /api/admin/global/toggles   — Get feature toggles</li>
 *   <li>PUT  /api/admin/global/toggles   — Update feature toggles</li>
 *   <li>GET  /api/admin/global/audit     — Recent audit log entries</li>
 *   <li>GET  /api/admin/global/usage     — Usage by tenant for a month</li>
 * </ul>
 */
public interface GlobalConfigController {
    List<LLMModelDto> getModels();
    void toggleModel(String modelId, ToggleModelRequest request);
    List<PlanDefaultsDto> getPlanDefaults();
    void updatePlanDefaults(String plan, PlanDefaultsDto defaults);
    FeatureTogglesDto getToggles();
    void updateToggles(FeatureTogglesDto toggles);
    List<AuditEntryDto> getAuditLog(int limit);
    List<UsageRowDto> getUsageByTenant(String month);
}
