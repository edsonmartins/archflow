package br.com.archflow.agent.metrics;

import java.time.Instant;
import java.util.Map;

/**
 * Métricas agregadas com estatísticas
 */
record AggregatedMetrics(
    Instant timestamp,
    Map<String, Long> counters,
    Map<String, Double> values,
    Map<String, MetricStats> stats
) {}