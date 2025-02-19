package br.com.archflow.agent.metrics;

/**
 * Estatísticas calculadas para uma métrica
 */
record MetricStats(
    double min,
    double max,
    double mean,
    long count,
    double median
) {}