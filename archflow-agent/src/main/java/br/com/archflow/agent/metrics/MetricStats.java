package br.com.archflow.agent.metrics;

/**
 * Estatísticas calculadas para uma métrica
 */
public record MetricStats(
    double min,
    double max,
    double mean,
    long count,
    double median
) {}