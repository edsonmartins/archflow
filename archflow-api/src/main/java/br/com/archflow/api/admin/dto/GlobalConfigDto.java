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

    /**
     * O consumo medido de um tenant no mês (RCHFLW-75).
     *
     * @param costCents              soma do custo pela tabela de preços, em centavos; {@code null}
     *                               quando nenhuma execução do tenant tinha preço
     * @param costReais              a mesma soma em reais, sem arredondar; {@code null} idem
     * @param declaredCostUsd        soma do custo declarado pelo provedor; {@code null} se nenhum declarou
     * @param executionsWithoutPrice execuções fora da soma de custo por não ter preço
     * @param percentOfTotal         parte dos tokens do mês que é deste tenant (0..100)
     * @param planLimit              tokens/mês do plano do tenant; {@code null} quando o tenant não tem
     *                               cadastro — o limite não é mostrado, em vez de inventado
     */
    public record UsageRowDto(
            String tenantId,
            String tenantName,
            int executions,
            long tokensInput,
            long tokensOutput,
            Long costCents,
            Double costReais,
            Double declaredCostUsd,
            int executionsWithoutPrice,
            double percentOfTotal,
            Long planLimit
    ) {}
}
