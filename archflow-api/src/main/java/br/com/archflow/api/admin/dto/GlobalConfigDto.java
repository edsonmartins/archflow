package br.com.archflow.api.admin.dto;

import java.util.List;

public record GlobalConfigDto() {

    public record LLMModelDto(
            String id,
            String name,
            String provider,
            String status,
            double costInputPer1M,
            double costOutputPer1M
    ) {}

    public record ToggleModelRequest(boolean active) {}

    public record PlanDefaultsDto(
            String plan,
            int executionsPerDay,
            long tokensPerMonth,
            int maxWorkflows,
            int maxUsers
    ) {}

    public record FeatureTogglesDto(
            boolean allowLocalModels,
            boolean humanInTheLoop,
            boolean brainSentry,
            boolean debugMode,
            boolean linktorNotifications,
            boolean auditLog
    ) {}

    public record AuditEntryDto(
            String id,
            String timestamp,
            String actor,
            String action,
            String details
    ) {}

    public record UsageRowDto(
            String tenantId,
            String tenantName,
            int executions,
            long tokensInput,
            long tokensOutput,
            double estimatedCost,
            double percentOfTotal,
            long planLimit
    ) {}
}
