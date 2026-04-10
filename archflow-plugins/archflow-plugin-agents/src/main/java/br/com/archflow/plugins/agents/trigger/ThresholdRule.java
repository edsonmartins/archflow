package br.com.archflow.plugins.agents.trigger;

import java.util.Objects;

/**
 * Regra genérica de threshold para monitoramento por tenant.
 *
 * <p>O ArchFlow define a estrutura — produtos registram suas regras
 * conforme suas métricas de negócio. Quando a condição é atingida,
 * o agente configurado é acionado.
 *
 * @param tenantId  ID do tenant (obrigatório)
 * @param ruleId    Identificador único da regra dentro do tenant
 * @param metricId  ID da métrica a monitorar (fornecida via MetricProvider)
 * @param operator  Operador de comparação (GT, LT, GTE, LTE, EQ)
 * @param threshold Valor de threshold
 * @param agentId   ID do agente a acionar quando threshold atingido
 * @param cooldownSeconds Tempo mínimo entre acionamentos (evita alertas repetidos)
 */
public record ThresholdRule(
        String tenantId,
        String ruleId,
        String metricId,
        Operator operator,
        double threshold,
        String agentId,
        int cooldownSeconds
) {
    public ThresholdRule {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(ruleId, "ruleId is required");
        Objects.requireNonNull(metricId, "metricId is required");
        Objects.requireNonNull(operator, "operator is required");
        Objects.requireNonNull(agentId, "agentId is required");
        if (cooldownSeconds < 0) cooldownSeconds = 0;
    }

    /**
     * Verifica se o valor atual viola o threshold.
     */
    public boolean isBreached(double currentValue) {
        return switch (operator) {
            case GT -> currentValue > threshold;
            case LT -> currentValue < threshold;
            case GTE -> currentValue >= threshold;
            case LTE -> currentValue <= threshold;
            case EQ -> currentValue == threshold;
        };
    }

    public static ThresholdRule of(String tenantId, String ruleId, String metricId,
                                    Operator operator, double threshold, String agentId) {
        return new ThresholdRule(tenantId, ruleId, metricId, operator, threshold, agentId, 300);
    }

    /**
     * Operadores de comparação para threshold.
     */
    public enum Operator {
        GT,  // greater than
        LT,  // less than
        GTE, // greater than or equal
        LTE, // less than or equal
        EQ   // equal
    }
}
