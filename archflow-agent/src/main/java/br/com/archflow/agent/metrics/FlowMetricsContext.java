package br.com.archflow.agent.metrics;

import br.com.archflow.model.flow.FlowStatus;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Contexto de métricas para um fluxo específico
 */
class FlowMetricsContext {
    private final String flowId;
    private final Instant startTime;
    private FlowStatus currentStatus;
    private final List<Throwable> errors;
    private final Map<String, Object> attributes;

    public FlowMetricsContext(String flowId, Instant startTime) {
        this.flowId = flowId;
        this.startTime = startTime;
        this.errors = Collections.synchronizedList(new ArrayList<>());
        this.attributes = new ConcurrentHashMap<>();
    }

    public long getDurationMillis() {
        return Instant.now().toEpochMilli() - startTime.toEpochMilli();
    }

    public void updateStatus(FlowStatus status) {
        this.currentStatus = status;
    }

    public void recordError(Throwable error) {
        this.errors.add(error);
    }

    public void setAttribute(String key, Object value) {
        this.attributes.put(key, value);
    }

    public Object getAttribute(String key) {
        return this.attributes.get(key);
    }

    public String getFlowId() {
        return flowId;
    }

    public FlowStatus getCurrentStatus() {
        return currentStatus;
    }

    public List<Throwable> getErrors() {
        return new ArrayList<>(errors);
    }

    public Map<String, Object> getAttributes() {
        return new HashMap<>(attributes);
    }
}