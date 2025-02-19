package br.com.archflow.agent.metrics;

import java.time.Instant;
import java.util.*;

/**
 * Agregador de métricas que processa dados brutos
 */
class MetricsAggregator {
    private final MetricsRegistry registry;

    public MetricsAggregator(MetricsRegistry registry) {
        this.registry = registry;
    }

    public AggregatedMetrics aggregate() {
        Map<String, Long> counters = registry.getCounters();
        Map<String, Double> values = registry.getValues();
        Map<String, List<Double>> histories = registry.getHistories();

        Map<String, MetricStats> stats = new HashMap<>();
        
        // Calcula estatísticas para cada métrica com histórico
        histories.forEach((name, history) -> {
            if (!history.isEmpty()) {
                stats.put(name, calculateStats(history));
            }
        });

        return new AggregatedMetrics(
            Instant.now(),
            counters,
            values,
            stats
        );
    }

    private MetricStats calculateStats(List<Double> values) {
        if (values.isEmpty()) {
            return new MetricStats(0, 0, 0, 0, 0);
        }

        DoubleSummaryStatistics stats = values.stream()
            .mapToDouble(Double::doubleValue)
            .summaryStatistics();

        return new MetricStats(
            stats.getMin(),
            stats.getMax(),
            stats.getAverage(),
            stats.getCount(),
            calculateMedian(values)
        );
    }

    private double calculateMedian(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        
        int size = sorted.size();
        if (size == 0) return 0;
        
        if (size % 2 == 0) {
            return (sorted.get(size/2 - 1) + sorted.get(size/2)) / 2;
        } else {
            return sorted.get(size/2);
        }
    }
}