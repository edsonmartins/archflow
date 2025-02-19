package br.com.archflow.agent.metrics;

import br.com.archflow.agent.config.AgentConfig;
import br.com.archflow.model.flow.FlowStatus;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.stream.Collectors;

/**
 * Registro de métricas que mantém contadores e valores
 */
class MetricsRegistry {
    private final Map<String, AtomicLong> counters;
    private final Map<String, DoubleAdder> values;
    private final Map<String, List<Double>> histories;

    public MetricsRegistry() {
        this.counters = new ConcurrentHashMap<>();
        this.values = new ConcurrentHashMap<>();
        this.histories = new ConcurrentHashMap<>();
    }

    public void incrementCounter(String name) {
        counters.computeIfAbsent(name, k -> new AtomicLong())
                .incrementAndGet();
    }

    public void recordValue(String name, double value) {
        values.computeIfAbsent(name, k -> new DoubleAdder())
              .add(value);
        
        histories.computeIfAbsent(name, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(value);
    }

    public long getCounter(String name) {
        return counters.getOrDefault(name, new AtomicLong()).get();
    }

    public double getValue(String name) {
        return values.getOrDefault(name, new DoubleAdder()).sum();
    }

    public Map<String, Long> getCounters() {
        return counters.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().get()
            ));
    }

    public Map<String, Double> getValues() {
        return values.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().sum()
            ));
    }

    public Map<String, List<Double>> getHistories() {
        return new HashMap<>(histories);
    }

    public void reset() {
        counters.clear();
        values.clear();
        histories.clear();
    }
}


