package br.com.archflow.plugins.agents;

import br.com.archflow.model.ai.AIAgent;
import br.com.archflow.model.ai.domain.*;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.plugin.api.spi.ComponentPlugin;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent specialized for continuous monitoring and anomaly detection.
 *
 * <p>Collects metrics from configurable sources, detects anomalies using
 * pluggable detection strategies, and dispatches alerts when thresholds
 * are breached. Supports continuous polling, on-demand checks, and
 * alert-only modes.
 */
public class MonitoringAgent implements AIAgent, ComponentPlugin {

    private static final String COMPONENT_ID = "monitoring-agent";
    private static final String VERSION = "1.0.0";

    private static final Set<String> SUPPORTED_TASK_TYPES = Set.of(
            "monitor", "health_check", "analyze_metrics"
    );

    // ── Monitoring modes ────────────────────────────────────────────────

    /**
     * Monitoring execution mode.
     */
    public enum MonitoringMode {
        /** Periodic polling of all metric sources. */
        CONTINUOUS,
        /** Single one-shot metric collection. */
        ON_DEMAND,
        /** Only fires when an anomaly is detected. */
        ALERT_ONLY
    }

    // ── Inner types ─────────────────────────────────────────────────────

    /**
     * A snapshot of a single metric reading.
     */
    public record MetricSnapshot(
            String name,
            double value,
            Instant timestamp,
            Map<String, Object> metadata
    ) {}

    /**
     * Functional interface for collecting metric snapshots.
     */
    @FunctionalInterface
    public interface MetricSource {
        MetricSnapshot collect(String metricName);
    }

    /**
     * Severity levels for alerts.
     */
    public enum AlertSeverity {
        INFO, WARNING, CRITICAL
    }

    /**
     * An alert raised when an anomaly is detected.
     */
    public record Alert(
            AlertSeverity severity,
            String metricName,
            String message,
            double currentValue,
            double threshold,
            Instant timestamp
    ) {}

    /**
     * Functional interface for dispatching alerts.
     */
    @FunctionalInterface
    public interface AlertDispatcher {
        void dispatch(Alert alert);
    }

    /**
     * Strategy interface for anomaly detection.
     */
    public interface AnomalyDetector {
        boolean isAnomaly(String metricName, double value, List<Double> history);
    }

    /**
     * Built-in anomaly detector that flags values outside configurable
     * upper and lower bounds.
     */
    public static class ThresholdAnomalyDetector implements AnomalyDetector {

        private final double upperBound;
        private final double lowerBound;

        public ThresholdAnomalyDetector(double lowerBound, double upperBound) {
            this.lowerBound = lowerBound;
            this.upperBound = upperBound;
        }

        @Override
        public boolean isAnomaly(String metricName, double value, List<Double> history) {
            return value > upperBound || value < lowerBound;
        }

        public double getUpperBound() {
            return upperBound;
        }

        public double getLowerBound() {
            return lowerBound;
        }
    }

    // ── Builder ─────────────────────────────────────────────────────────

    /**
     * Creates a new builder for {@code MonitoringAgent}.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Map<String, MetricSource> metricSources = new LinkedHashMap<>();
        private AlertDispatcher alertDispatcher;
        private AnomalyDetector anomalyDetector;
        private int checkIntervalSeconds = 60;
        private int historySize = 100;
        private MonitoringMode mode = MonitoringMode.ON_DEMAND;

        public Builder metricSource(String name, MetricSource source) {
            metricSources.put(name, source);
            return this;
        }

        public Builder metricSources(Map<String, MetricSource> sources) {
            metricSources.putAll(sources);
            return this;
        }

        public Builder alertDispatcher(AlertDispatcher dispatcher) {
            this.alertDispatcher = dispatcher;
            return this;
        }

        public Builder anomalyDetector(AnomalyDetector detector) {
            this.anomalyDetector = detector;
            return this;
        }

        public Builder checkIntervalSeconds(int seconds) {
            this.checkIntervalSeconds = seconds;
            return this;
        }

        public Builder historySize(int size) {
            this.historySize = size;
            return this;
        }

        public Builder mode(MonitoringMode mode) {
            this.mode = mode;
            return this;
        }

        public MonitoringAgent build() {
            return new MonitoringAgent(this);
        }
    }

    // ── Instance state ──────────────────────────────────────────────────

    private final Map<String, MetricSource> metricSources;
    private final AlertDispatcher alertDispatcher;
    private final AnomalyDetector anomalyDetector;
    private final int checkIntervalSeconds;
    private final int historySize;
    private final MonitoringMode mode;

    private final Map<String, LinkedList<Double>> metricHistory = new ConcurrentHashMap<>();

    private Map<String, Object> config;
    private boolean initialized = false;

    /**
     * No-arg constructor for plugin discovery / reflective instantiation.
     * Uses sensible defaults; call {@link #initialize(Map)} before use.
     */
    public MonitoringAgent() {
        this.metricSources = new LinkedHashMap<>();
        this.alertDispatcher = alert -> {};
        this.anomalyDetector = new ThresholdAnomalyDetector(0, Double.MAX_VALUE);
        this.checkIntervalSeconds = 60;
        this.historySize = 100;
        this.mode = MonitoringMode.ON_DEMAND;
    }

    private MonitoringAgent(Builder builder) {
        this.metricSources = new LinkedHashMap<>(builder.metricSources);
        this.alertDispatcher = builder.alertDispatcher != null ? builder.alertDispatcher : alert -> {};
        this.anomalyDetector = builder.anomalyDetector != null
                ? builder.anomalyDetector
                : new ThresholdAnomalyDetector(0, Double.MAX_VALUE);
        this.checkIntervalSeconds = builder.checkIntervalSeconds;
        this.historySize = builder.historySize;
        this.mode = builder.mode;
    }

    // ── AIComponent / ComponentPlugin lifecycle ─────────────────────────

    @Override
    public void initialize(Map<String, Object> config) {
        validateConfig(config);
        this.config = config;
        this.initialized = true;
    }

    @Override
    public ComponentMetadata getMetadata() {
        return new ComponentMetadata(
                COMPONENT_ID,
                "Monitoring Agent",
                "Autonomous monitoring agent that collects metrics, detects anomalies, and dispatches alerts",
                ComponentType.AGENT,
                VERSION,
                Set.of("monitoring", "anomaly-detection", "alerting"),
                List.of(
                        new ComponentMetadata.OperationMetadata(
                                "executeTask", "Execute Task", "Execute a monitoring task",
                                List.of(new ComponentMetadata.ParameterMetadata("task", "object", "Task to execute", true)),
                                List.of(new ComponentMetadata.ParameterMetadata("result", "object", "Task result", true))
                        ),
                        new ComponentMetadata.OperationMetadata(
                                "analyzeRequest", "Analyze Request", "Interpret a monitoring query",
                                List.of(new ComponentMetadata.ParameterMetadata("query", "string", "Monitoring query", true)),
                                List.of(new ComponentMetadata.ParameterMetadata("result", "object", "Analysis result", true))
                        )
                ),
                Map.of(),
                Set.of("agent", "monitoring", "observability")
        );
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("Agent not initialized. Call initialize() first.");
        }

        return switch (operation) {
            case "executeTask" -> executeTask((Task) input, context);
            case "makeDecision" -> makeDecision(context);
            case "planActions" -> planActions((Goal) input, context);
            case "analyzeRequest" -> analyzeRequest((String) input);
            default -> throw new IllegalArgumentException("Unsupported operation: " + operation);
        };
    }

    @Override
    public void validateConfig(Map<String, Object> config) {
        // No required configuration for the reference implementation
    }

    @Override
    public void shutdown() {
        this.config = null;
        this.initialized = false;
        this.metricHistory.clear();
    }

    // ── AIAgent contract ────────────────────────────────────────────────

    @Override
    public Result executeTask(Task task, ExecutionContext context) {
        if (task == null) {
            return Result.failure("Task cannot be null");
        }

        if (!SUPPORTED_TASK_TYPES.contains(task.type())) {
            return Result.failure("Unsupported task type: " + task.type()
                    + ". Supported: " + SUPPORTED_TASK_TYPES);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("task_id", task.id());
        output.put("task_type", task.type());
        output.put("mode", mode.name());

        List<MetricSnapshot> snapshots = collectAllMetrics();
        output.put("metrics_collected", snapshots.size());

        List<Alert> alerts = new ArrayList<>();
        for (MetricSnapshot snapshot : snapshots) {
            recordHistory(snapshot.name(), snapshot.value());
            List<Double> history = getHistory(snapshot.name());
            if (anomalyDetector.isAnomaly(snapshot.name(), snapshot.value(), history)) {
                double threshold = anomalyDetector instanceof ThresholdAnomalyDetector tad
                        ? tad.getUpperBound() : 0.0;
                Alert alert = new Alert(
                        AlertSeverity.WARNING,
                        snapshot.name(),
                        "Anomaly detected for metric " + snapshot.name() + ": value=" + snapshot.value(),
                        snapshot.value(),
                        threshold,
                        Instant.now()
                );
                alerts.add(alert);
                alertDispatcher.dispatch(alert);
            }
        }

        output.put("alerts", alerts.size());
        output.put("status", alerts.isEmpty() ? "healthy" : "anomalies_detected");

        return new Result(true, output, Map.of("agent", COMPONENT_ID), List.of("Monitoring task completed"));
    }

    @Override
    public Decision makeDecision(ExecutionContext context) {
        boolean hasAlerts = metricHistory.values().stream()
                .anyMatch(history -> !history.isEmpty()
                        && anomalyDetector.isAnomaly("", history.getLast(), new ArrayList<>(history)));

        if (hasAlerts) {
            return new Decision(
                    "escalate",
                    "Anomalies detected in recent metrics; escalation required",
                    0.90,
                    List.of("ignore", "investigate")
            );
        }

        return new Decision(
                "continue_monitoring",
                "All metrics within normal range; continue monitoring",
                0.85,
                List.of("increase_frequency", "reduce_frequency")
        );
    }

    @Override
    public List<Action> planActions(Goal goal, ExecutionContext context) {
        if (goal == null) {
            return List.of(Action.of("error", "No goal provided"));
        }

        List<Action> actions = new ArrayList<>();

        actions.add(new Action("configure_sources", "Configure Metric Sources",
                Map.of("goal", goal.description()), true));

        actions.add(new Action("collect_baseline", "Collect Baseline Metrics",
                Map.of("duration", "5m"), true));

        actions.add(new Action("set_thresholds", "Set Anomaly Thresholds",
                Map.of("criteria", goal.successCriteria()), false));

        actions.add(new Action("start_monitoring", "Start Continuous Monitoring",
                Map.of("interval_seconds", checkIntervalSeconds), false));

        actions.add(new Action("configure_alerts", "Configure Alert Dispatch",
                Map.of("severity_levels", List.of("WARNING", "CRITICAL")), false));

        return actions;
    }

    // ── Monitoring-specific operations ──────────────────────────────────

    /**
     * Interprets a natural-language monitoring query and returns a result
     * with relevant metric information.
     *
     * @param query the monitoring query (e.g. "is the system healthy?", "show me CPU usage")
     * @return a {@link Result} containing the analysis
     */
    public Result analyzeRequest(String query) {
        if (query == null || query.isBlank()) {
            return Result.failure("Query cannot be null or empty");
        }

        String lowerQuery = query.toLowerCase();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("query", query);

        if (lowerQuery.contains("health") || lowerQuery.contains("status")) {
            output.put("type", "health_check");
            List<MetricSnapshot> snapshots = collectAllMetrics();
            boolean healthy = true;
            Map<String, Object> metricSummary = new LinkedHashMap<>();
            for (MetricSnapshot s : snapshots) {
                recordHistory(s.name(), s.value());
                List<Double> history = getHistory(s.name());
                boolean anomaly = anomalyDetector.isAnomaly(s.name(), s.value(), history);
                if (anomaly) {
                    healthy = false;
                }
                metricSummary.put(s.name(), Map.of("value", s.value(), "anomaly", anomaly));
            }
            output.put("healthy", healthy);
            output.put("metrics", metricSummary);
        } else if (lowerQuery.contains("show") || lowerQuery.contains("usage") || lowerQuery.contains("get")) {
            output.put("type", "metric_query");
            // Try to find matching metric source
            Map<String, Object> metricValues = new LinkedHashMap<>();
            for (Map.Entry<String, MetricSource> entry : metricSources.entrySet()) {
                if (lowerQuery.contains(entry.getKey().toLowerCase())) {
                    MetricSnapshot snapshot = entry.getValue().collect(entry.getKey());
                    recordHistory(snapshot.name(), snapshot.value());
                    metricValues.put(snapshot.name(), snapshot.value());
                }
            }
            if (metricValues.isEmpty()) {
                // Collect all metrics if no specific match
                for (MetricSnapshot s : collectAllMetrics()) {
                    recordHistory(s.name(), s.value());
                    metricValues.put(s.name(), s.value());
                }
            }
            output.put("metrics", metricValues);
        } else {
            output.put("type", "general");
            output.put("metrics_available", new ArrayList<>(metricSources.keySet()));
            output.put("history_size", metricHistory.size());
        }

        return Result.success(output);
    }

    /**
     * Collects a single metric by name.
     *
     * @param metricName the metric name
     * @return the snapshot, or {@code null} if no source is registered for the name
     */
    public MetricSnapshot collectMetric(String metricName) {
        MetricSource source = metricSources.get(metricName);
        if (source == null) {
            return null;
        }
        MetricSnapshot snapshot = source.collect(metricName);
        recordHistory(metricName, snapshot.value());
        return snapshot;
    }

    /**
     * Returns the recorded history for a given metric.
     */
    public List<Double> getHistory(String metricName) {
        LinkedList<Double> history = metricHistory.get(metricName);
        if (history != null) {
            synchronized (history) {
                return new ArrayList<>(history);
            }
        }
        return List.of();
    }

    /**
     * Returns the current monitoring mode.
     */
    public MonitoringMode getMode() {
        return mode;
    }

    /**
     * Returns the configured check interval in seconds.
     */
    public int getCheckIntervalSeconds() {
        return checkIntervalSeconds;
    }

    // ── Internal helpers ────────────────────────────────────────────────

    private List<MetricSnapshot> collectAllMetrics() {
        List<MetricSnapshot> snapshots = new ArrayList<>();
        for (Map.Entry<String, MetricSource> entry : metricSources.entrySet()) {
            try {
                MetricSnapshot snapshot = entry.getValue().collect(entry.getKey());
                if (snapshot != null) {
                    snapshots.add(snapshot);
                }
            } catch (Exception e) {
                // Log and continue; one failing source should not halt collection
            }
        }
        return snapshots;
    }

    private void recordHistory(String metricName, double value) {
        metricHistory.computeIfAbsent(metricName, k -> new LinkedList<>());
        LinkedList<Double> history = metricHistory.get(metricName);
        synchronized (history) {
            history.addLast(value);
            while (history.size() > historySize) {
                history.removeFirst();
            }
        }
    }
}
