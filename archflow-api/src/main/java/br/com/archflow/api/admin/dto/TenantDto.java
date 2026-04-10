package br.com.archflow.api.admin.dto;

import java.util.List;
import java.util.Map;

public record TenantDto(
        String id,
        String name,
        String plan,
        String status,
        String adminEmail,
        String sector,
        TenantLimitsDto limits,
        TenantUsageDto usage,
        List<String> allowedModels,
        String createdAt
) {
    public record TenantLimitsDto(
            int executionsPerDay,
            long tokensPerMonth,
            int maxWorkflows,
            int maxUsers,
            List<String> allowedModels,
            List<String> featuresEnabled
    ) {}

    public record TenantUsageDto(
            int executionsToday,
            long tokensThisMonth,
            int workflowCount,
            int userCount
    ) {}

    public record TenantStatsDto(
            int totalActive,
            int totalTrial,
            int executionsToday,
            long tokensThisMonth
    ) {}

    public record CreateTenantRequest(
            String name,
            String tenantId,
            String adminEmail,
            String sector,
            String plan,
            String expiresAt,
            TenantLimitsDto limits,
            List<String> allowedModels
    ) {}
}
