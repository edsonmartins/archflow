package br.com.archflow.api.admin.dto;

import java.util.List;

/**
 * O resumo do workspace com números medidos (RCHFLW-75/85) — nada fixo no código.
 *
 * @param plan            o plano do cadastro do tenant; {@code null} quando o tenant não tem cadastro
 * @param status          o status do cadastro do tenant; {@code null} idem
 * @param executionsToday execuções de fluxo do tenant iniciadas hoje
 * @param tokensThisMonth tokens medidos no mês (entrada + saída); zero quando nada foi medido
 * @param usageThisMonth  o consumo medido no mês, com custo
 * @param limits          os limites do plano do tenant; {@code null} quando o tenant não tem cadastro
 *                        — o limite não é mostrado, em vez de inventado
 */
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
        UsageDto usageThisMonth,
        WorkspaceLimitsDto limits
) {
    public record WorkspaceLimitsDto(
            int executionsPerDay,
            long tokensPerMonth,
            int maxWorkflows,
            int maxUsers,
            List<String> allowedModels
    ) {}

    /**
     * Consumo de modelo medido.
     *
     * @param executions           execuções que chamaram modelo
     * @param costCents            soma do custo pela tabela de preços, em centavos; {@code null} quando
     *                             nenhuma execução tinha preço
     * @param costReais            a mesma soma em reais, sem arredondar; {@code null} idem
     * @param declaredCostUsd      soma do custo declarado pelo provedor; {@code null} se nenhum declarou
     * @param executionsWithoutPrice execuções que ficaram fora da soma por não ter preço
     */
    public record UsageDto(
            int executions,
            long tokensInput,
            long tokensOutput,
            Long costCents,
            Double costReais,
            Double declaredCostUsd,
            int executionsWithoutPrice
    ) {
        public static UsageDto of(br.com.archflow.api.usage.UsoSomado uso) {
            return new UsageDto(uso.execucoes(), uso.tokensEntrada(), uso.tokensSaida(), uso.custoCentavos(),
                    uso.custoReais(), uso.custoDeclaradoUsd(), uso.execucoesSemPreco());
        }
    }
}
