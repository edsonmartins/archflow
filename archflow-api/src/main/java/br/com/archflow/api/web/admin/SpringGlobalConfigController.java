package br.com.archflow.api.web.admin;

import br.com.archflow.api.admin.GlobalConfigController;
import br.com.archflow.api.admin.dto.GlobalConfigDto.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Spring MVC adapter that delegates to the framework-agnostic {@link GlobalConfigController}.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/admin/global/models}        — List LLM models</li>
 *   <li>{@code PUT  /api/admin/global/models/{id}}    — Toggle model active/inactive</li>
 *   <li>{@code GET  /api/admin/global/plans}          — Get plan defaults</li>
 *   <li>{@code PUT  /api/admin/global/plans/{plan}}   — Update plan defaults</li>
 *   <li>{@code GET  /api/admin/global/toggles}        — Get feature toggles</li>
 *   <li>{@code PUT  /api/admin/global/toggles}        — Update feature toggles</li>
 *   <li>{@code GET  /api/admin/global/audit}          — Recent audit log entries</li>
 *   <li>{@code GET  /api/admin/global/usage}          — Usage by tenant for a month</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/global")
public class SpringGlobalConfigController {

    private final GlobalConfigController delegate;

    public SpringGlobalConfigController(GlobalConfigController delegate) {
        this.delegate = delegate;
    }

    @GetMapping("/models")
    public List<LLMModelDto> getModels() {
        return delegate.getModels();
    }

    @PutMapping("/models/{id}")
    public void toggleModel(@PathVariable String id, @RequestBody ToggleModelRequest request) {
        delegate.toggleModel(id, request);
    }

    @GetMapping("/plans")
    public List<PlanDefaultsDto> getPlanDefaults() {
        return delegate.getPlanDefaults();
    }

    @PutMapping("/plans/{plan}")
    public void updatePlanDefaults(@PathVariable String plan, @RequestBody PlanDefaultsDto defaults) {
        delegate.updatePlanDefaults(plan, defaults);
    }

    @GetMapping("/toggles")
    public FeatureTogglesDto getToggles() {
        return delegate.getToggles();
    }

    @PutMapping("/toggles")
    public void updateToggles(@RequestBody FeatureTogglesDto toggles) {
        delegate.updateToggles(toggles);
    }

    @GetMapping("/audit")
    public List<AuditEntryDto> getAuditLog(@RequestParam(defaultValue = "50") int limit) {
        return delegate.getAuditLog(limit);
    }

    @GetMapping("/usage")
    public List<UsageRowDto> getUsageByTenant(@RequestParam(defaultValue = "") String month) {
        return delegate.getUsageByTenant(month);
    }
}
