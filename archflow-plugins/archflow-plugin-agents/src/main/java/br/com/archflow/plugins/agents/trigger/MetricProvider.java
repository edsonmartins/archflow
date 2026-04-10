package br.com.archflow.plugins.agents.trigger;

import java.util.Map;

/**
 * Interface genérica para fornecimento de métricas por tenant.
 *
 * <p>O ArchFlow define esta interface — produtos como VendaX a implementam
 * para fornecer métricas de negócio (churn rate, inadimplência, etc.).
 * O motor não conhece a semântica das métricas.
 */
@FunctionalInterface
public interface MetricProvider {

    /**
     * Obtém o valor atual de uma métrica para um tenant.
     *
     * @param tenantId ID do tenant
     * @param metricId Identificador da métrica
     * @param context  Contexto adicional (ex: filtros, período)
     * @return Valor atual da métrica
     */
    double getMetric(String tenantId, String metricId, Map<String, Object> context);
}
