package br.com.archflow.agent.metrics;

import br.com.archflow.agent.config.AgentConfig;
import br.com.archflow.model.metrics.StepMetrics;
import br.com.archflow.model.engine.ExecutionMetrics;
import br.com.archflow.model.flow.FlowStatus;

import java.io.Closeable;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.time.Instant;

/**
 * Coletor de métricas do ArchFlow Agent.
 * Responsável por coletar e agregar métricas de execução.
 */
public class MetricsCollector implements Closeable {
    private static final Logger logger = Logger.getLogger(MetricsCollector.class.getName());

    /**
     * Hard cap on how long an entry can sit in {@link #activeFlows} before
     * the stale-entry sweeper evicts it. Flows that neither complete nor
     * fail (crash, interrupted executor, missed callback) otherwise
     * accumulate indefinitely.
     */
    private static final long STALE_FLOW_TTL_MS = TimeUnit.HOURS.toMillis(2);
    private static final long STALE_FLOW_SWEEP_INTERVAL_S = 300; // every 5 min

    private final AgentConfig config;
    private final ScheduledExecutorService scheduler;
    private final Map<String, FlowMetricsContext> activeFlows;
    private final MetricsRegistry registry;
    private final MetricsAggregator aggregator;
    private final MetricsExporter exporter;

    public MetricsCollector(AgentConfig config) {
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("metrics-collector").factory());
        this.activeFlows = new ConcurrentHashMap<>();
        this.registry = new MetricsRegistry();
        this.aggregator = new MetricsAggregator(registry);
        this.exporter = new MetricsExporter(config);

        if (config.monitoringConfig().metricsEnabled()) {
            startPeriodicCollection();
        }
        startStaleFlowSweeper();
    }

    /**
     * Registra início de execução de um fluxo
     */
    public void recordFlowStart(String flowId) {
        logger.fine("Registrando início do fluxo: " + flowId);
        FlowMetricsContext context = new FlowMetricsContext(flowId, Instant.now());
        activeFlows.put(flowId, context);
        registry.incrementCounter("flows_started");
    }

    /**
     * Registra conclusão de execução de um fluxo
     */
    public void recordFlowCompletion(String flowId, ExecutionMetrics metrics, boolean success) {
        logger.fine("Registrando conclusão do fluxo: " + flowId + ", success=" + success);

        FlowMetricsContext context = activeFlows.remove(flowId);
        if (context != null) {
            long duration = context.getDurationMillis();

            registry.incrementCounter("flows_completed");
            if (success) {
                registry.incrementCounter("flows_succeeded");
            } else {
                registry.incrementCounter("flows_failed");
            }

            registry.recordValue("flow_duration", duration);
            registry.recordValue("flow_tokens", metrics.tokensUsed());

            // Registra métricas por passo
            metrics.stepMetrics().forEach((stepId, stepMetrics) ->
                    recordStepMetrics(flowId, stepId, stepMetrics)
            );
        }
    }

    /**
     * Registra erro na execução de um fluxo
     */
    public void recordFlowError(String flowId, Throwable error) {
        logger.fine("Registrando erro do fluxo: " + flowId);

        registry.incrementCounter("flows_errors");
        registry.incrementCounter("errors_total");

        FlowMetricsContext context = activeFlows.get(flowId);
        if (context != null) {
            context.recordError(error);
        }
    }

    /**
     * Registra métricas de um passo específico
     */
    public void recordStepMetrics(String flowId, String stepId, StepMetrics metrics) {
        registry.recordValue("step_duration", metrics.executionTime());
        registry.recordValue("step_tokens", metrics.tokensUsed());
        registry.recordValue("step_retries", metrics.retryCount());

        // Registra métricas adicionais se houver
        metrics.additionalMetrics().forEach((key, value) -> {
            if (value instanceof Number) {
                registry.recordValue("step_" + key, ((Number) value).doubleValue());
            }
        });
    }

    /**
     * Registra status atual de um fluxo
     */
    public void recordFlowStatus(String flowId, FlowStatus status) {
        logger.fine("Registrando status do fluxo: " + flowId + " = " + status);

        registry.incrementCounter("flow_status_" + status.name().toLowerCase());

        FlowMetricsContext context = activeFlows.get(flowId);
        if (context != null) {
            context.updateStatus(status);
        }
    }

    /**
     * Obtém métricas agregadas
     */
    public AggregatedMetrics getAggregatedMetrics() {
        return aggregator.aggregate();
    }

    @Override
    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Exporta métricas finais
        if (config.monitoringConfig().metricsEnabled()) {
            exporter.export(getAggregatedMetrics());
        }
    }

    private void startPeriodicCollection() {
        int interval = config.monitoringConfig().metricsInterval();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                AggregatedMetrics metrics = aggregator.aggregate();
                exporter.export(metrics);
            } catch (Exception e) {
                logger.warning("Erro coletando métricas: " + e.getMessage());
            }
        }, interval, interval, TimeUnit.SECONDS);
    }

    /**
     * Removes {@link FlowMetricsContext} entries older than
     * {@link #STALE_FLOW_TTL_MS}. Protects against memory leaks when a
     * flow crashes before {@code recordFlowCompletion} is called, since
     * the {@code activeFlows} map would otherwise grow without bound.
     */
    private void startStaleFlowSweeper() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                int removed = 0;
                for (Map.Entry<String, FlowMetricsContext> e : activeFlows.entrySet()) {
                    if (e.getValue().getDurationMillis() > STALE_FLOW_TTL_MS) {
                        if (activeFlows.remove(e.getKey(), e.getValue())) {
                            removed++;
                        }
                    }
                }
                if (removed > 0) {
                    logger.info("Evicted " + removed + " stale active-flow metrics contexts");
                    registry.incrementCounter("flows_abandoned_evicted");
                }
            } catch (Exception ex) {
                logger.warning("Stale flow sweeper failed: " + ex.getMessage());
            }
        }, STALE_FLOW_SWEEP_INTERVAL_S, STALE_FLOW_SWEEP_INTERVAL_S, TimeUnit.SECONDS);
    }
}