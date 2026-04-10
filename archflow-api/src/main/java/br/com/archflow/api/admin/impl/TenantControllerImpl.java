package br.com.archflow.api.admin.impl;

import br.com.archflow.api.admin.TenantController;
import br.com.archflow.api.admin.dto.TenantDto;
import br.com.archflow.api.admin.dto.TenantDto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TenantControllerImpl implements TenantController {
    private static final Logger log = LoggerFactory.getLogger(TenantControllerImpl.class);
    private final Map<String, TenantDto> tenants = new ConcurrentHashMap<>();

    public TenantControllerImpl() {
        // Seed with demo data
        var limits = new TenantLimitsDto(500, 5_000_000, 20, 10, List.of("gpt-4o", "claude-sonnet-4-6"), List.of("hitl", "brainSentry"));
        var usage = new TenantUsageDto(340, 4_200_000, 12, 5);
        tenants.put("tenant_rio_quality", new TenantDto("tenant_rio_quality", "Rio Quality", "enterprise", "active",
                "admin@rioquality.com.br", "Food Distribution", limits, usage, limits.allowedModels(), "2025-06"));
    }

    @Override
    public List<TenantDto> listTenants() {
        return new ArrayList<>(tenants.values());
    }

    @Override
    public TenantStatsDto getStats() {
        long active = tenants.values().stream().filter(t -> "active".equals(t.status())).count();
        long trial = tenants.values().stream().filter(t -> "trial".equals(t.status())).count();
        int execToday = tenants.values().stream().mapToInt(t -> t.usage() != null ? t.usage().executionsToday() : 0).sum();
        long tokensMonth = tenants.values().stream().mapToLong(t -> t.usage() != null ? t.usage().tokensThisMonth() : 0).sum();
        return new TenantStatsDto((int) active, (int) trial, execToday, tokensMonth);
    }

    @Override
    public TenantDto createTenant(CreateTenantRequest request) {
        log.info("Creating tenant: {} ({})", request.name(), request.tenantId());
        var limits = request.limits() != null ? request.limits() :
                new TenantLimitsDto(100, 1_000_000, 5, 3, List.of(), List.of());
        var usage = new TenantUsageDto(0, 0, 0, 0);
        var tenant = new TenantDto(request.tenantId(), request.name(), request.plan(),
                "trial".equals(request.plan()) ? "trial" : "active",
                request.adminEmail(), request.sector(), limits, usage,
                request.allowedModels() != null ? request.allowedModels() : List.of(),
                Instant.now().toString().substring(0, 7));
        tenants.put(request.tenantId(), tenant);
        return tenant;
    }

    @Override
    public TenantDto getTenant(String tenantId) {
        TenantDto tenant = tenants.get(tenantId);
        if (tenant == null) throw new IllegalArgumentException("Tenant not found: " + tenantId);
        return tenant;
    }

    @Override
    public TenantDto updateTenant(String tenantId, TenantDto update) {
        TenantDto existing = getTenant(tenantId);
        var updated = new TenantDto(tenantId,
                update.name() != null ? update.name() : existing.name(),
                update.plan() != null ? update.plan() : existing.plan(),
                update.status() != null ? update.status() : existing.status(),
                update.adminEmail() != null ? update.adminEmail() : existing.adminEmail(),
                update.sector() != null ? update.sector() : existing.sector(),
                update.limits() != null ? update.limits() : existing.limits(),
                existing.usage(),
                update.allowedModels() != null ? update.allowedModels() : existing.allowedModels(),
                existing.createdAt());
        tenants.put(tenantId, updated);
        return updated;
    }

    @Override
    public void suspendTenant(String tenantId) {
        log.info("Suspending tenant: {}", tenantId);
        TenantDto t = getTenant(tenantId);
        tenants.put(tenantId, new TenantDto(t.id(), t.name(), t.plan(), "suspended",
                t.adminEmail(), t.sector(), t.limits(), t.usage(), t.allowedModels(), t.createdAt()));
    }

    @Override
    public void activateTenant(String tenantId) {
        log.info("Activating tenant: {}", tenantId);
        TenantDto t = getTenant(tenantId);
        tenants.put(tenantId, new TenantDto(t.id(), t.name(), t.plan(), "active",
                t.adminEmail(), t.sector(), t.limits(), t.usage(), t.allowedModels(), t.createdAt()));
    }

    @Override
    public void deleteTenant(String tenantId) {
        log.info("Deleting tenant: {}", tenantId);
        tenants.remove(tenantId);
    }
}
