package br.com.archflow.api.admin.dto;

import java.util.List;

public record GlobalConfigDto() {

    /**
     * Um modelo do catálogo da plataforma.
     *
     * @param id              id no formato do provider ({@code openai/gpt-4o} no OpenRouter)
     * @param provider        id do provider ({@code openrouter}, {@code openai}...)
     * @param status          {@code active}, {@code beta} ou {@code deprecated} — só o último sai do
     *                        seletor dos nós
     * @param costInputPer1M  custo declarado; {@code null} quando ninguém o informou (nunca zero
     *                        inventado — zero é "grátis")
     */
    public record LLMModelDto(
            String id,
            String name,
            String provider,
            String status,
            Double costInputPer1M,
            Double costOutputPer1M
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
