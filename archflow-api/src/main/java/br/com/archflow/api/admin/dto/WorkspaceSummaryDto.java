package br.com.archflow.api.admin.dto;

import java.util.List;

public record WorkspaceSummaryDto(
        String tenantId,
        String tenantName,
        String plan,
        String status,
        int executionsToday,
        long tokensThisMonth,
        int workflowCount,
        int userCount,
        int apiKeyCount,
        WorkspaceLimitsDto limits
) {
    public record WorkspaceLimitsDto(
            int executionsPerDay,
            long tokensPerMonth,
            int maxWorkflows,
            int maxUsers,
            List<String> allowedModels
    ) {}
}
