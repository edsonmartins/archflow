package br.com.archflow.agent.metrics;

import br.com.archflow.agent.config.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exportador de métricas que envia dados para sistemas externos.
 *
 * <p>Supported backends:
 * <ul>
 *   <li>prometheus - Push metrics to Prometheus Pushgateway</li>
 *   <li>influxdb - Write metrics to InfluxDB</li>
 *   <li>log - Output to logs (default)</li>
 *   <li>custom - Custom HTTP endpoint</li>
 * </ul>
 *
 * <p>Configuration via AgentConfig:
 * <pre>{@code
 * metrics.export.backend=prometheus
 * metrics.export.url=http://localhost:9091/metrics/job/archflow
 * metrics.export.async=true
 * }</pre>
 */
class MetricsExporter {
    private static final Logger log = LoggerFactory.getLogger(MetricsExporter.class);

    private final AgentConfig config;
    private final ExecutorService executor;
    private final HttpClient httpClient;
    private final String backend;
    private final String exportUrl;
    private final boolean async;

    public MetricsExporter(AgentConfig config) {
        this.config = config;
        this.backend = getConfigValue("metrics.export.backend", "log");
        this.exportUrl = getConfigValue("metrics.export.url", "");
        this.async = Boolean.parseBoolean(getConfigValue("metrics.export.async", "true"));
        this.executor = this.async ? Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "metrics-exporter");
            t.setDaemon(true);
            return t;
        }) : null;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Gets a configuration value with a default.
     */
    private String getConfigValue(String key, String defaultValue) {
        Object value = config.extraConfig().get(key);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * Exports metrics to the configured backend.
     *
     * @param metrics The aggregated metrics to export
     */
    public void export(AggregatedMetrics metrics) {
        if (async && executor != null) {
            CompletableFuture.runAsync(() -> doExport(metrics), executor)
                    .exceptionally(e -> {
                        log.error("Async metrics export failed", e);
                        return null;
                    });
        } else {
            doExport(metrics);
        }
    }

    private void doExport(AggregatedMetrics metrics) {
        try {
            switch (backend.toLowerCase()) {
                case "prometheus" -> exportToPrometheus(metrics);
                case "influxdb" -> exportToInfluxDb(metrics);
                case "custom", "http" -> exportToHttpEndpoint(metrics);
                case "log", "stdout" -> exportToLog(metrics);
                default -> {
                    log.warn("Unknown metrics backend '{}', falling back to log", backend);
                    exportToLog(metrics);
                }
            }
        } catch (Exception e) {
            log.error("Metrics export failed for backend '{}'", backend, e);
        }
    }

    /**
     * Exports metrics to Prometheus Pushgateway.
     * Format: OpenMetrics text format
     */
    private void exportToPrometheus(AggregatedMetrics metrics) throws IOException, InterruptedException {
        if (exportUrl == null || exportUrl.isEmpty()) {
            log.warn("Prometheus export URL not configured, skipping export");
            return;
        }

        StringBuilder payload = new StringBuilder();
        long timestamp = metrics.timestamp().toEpochMilli();

        // Export counters
        for (Map.Entry<String, Long> entry : metrics.counters().entrySet()) {
            String metricName = sanitizeMetricName(entry.getKey());
            payload.append(String.format("# TYPE archflow_%s counter\n", metricName));
            payload.append(String.format("archflow_%s_total %d %d\n", metricName, entry.getValue(), timestamp));
        }

        // Export gauge values
        for (Map.Entry<String, Double> entry : metrics.values().entrySet()) {
            String metricName = sanitizeMetricName(entry.getKey());
            payload.append(String.format("# TYPE archflow_%s gauge\n", metricName));
            payload.append(String.format("archflow_%s %f %d\n", metricName, entry.getValue(), timestamp));
        }

        // Export stats
        for (Map.Entry<String, MetricStats> entry : metrics.stats().entrySet()) {
            String metricName = sanitizeMetricName(entry.getKey());
            MetricStats stats = entry.getValue();

            payload.append(String.format("# TYPE archflow_%s_count gauge\n", metricName));
            payload.append(String.format("archflow_%s_count %d %d\n", metricName, stats.count(), timestamp));

            payload.append(String.format("# TYPE archflow_%s_sum gauge\n", metricName));
            double sum = stats.mean() * stats.count();
            payload.append(String.format("archflow_%s_sum %f %d\n", metricName, sum, timestamp));

            payload.append(String.format("# TYPE archflow_%s_min gauge\n", metricName));
            payload.append(String.format("archflow_%s_min %f %d\n", metricName, stats.min(), timestamp));

            payload.append(String.format("# TYPE archflow_%s_max gauge\n", metricName));
            payload.append(String.format("archflow_%s_max %f %d\n", metricName, stats.max(), timestamp));

            payload.append(String.format("# TYPE archflow_%s_avg gauge\n", metricName));
            payload.append(String.format("archflow_%s_avg %f %d\n", metricName, stats.mean(), timestamp));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(exportUrl))
                .header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            log.debug("Exported {} metrics to Prometheus", metrics.counters().size() + metrics.values().size());
        } else {
            log.warn("Prometheus export failed with status: {}", response.statusCode());
        }
    }

    /**
     * Exports metrics to InfluxDB using line protocol.
     */
    private void exportToInfluxDb(AggregatedMetrics metrics) throws IOException, InterruptedException {
        if (exportUrl == null || exportUrl.isEmpty()) {
            log.warn("InfluxDB export URL not configured, skipping export");
            return;
        }

        String bucket = getConfigValue("metrics.export.influxdb.bucket", "archflow");
        String org = getConfigValue("metrics.export.influxdb.org", "default");

        StringBuilder payload = new StringBuilder();
        long timestamp = metrics.timestamp().toEpochMilli() * 1_000_000; // Convert to nanoseconds

        // Export counters
        for (Map.Entry<String, Long> entry : metrics.counters().entrySet()) {
            String metricName = sanitizeMetricName(entry.getKey());
            payload.append(String.format("archflow_counters,metric=%s value=%d %d\n",
                    metricName, entry.getValue(), timestamp));
        }

        // Export values
        for (Map.Entry<String, Double> entry : metrics.values().entrySet()) {
            String metricName = sanitizeMetricName(entry.getKey());
            payload.append(String.format("archflow_values,metric=%s value=%f %d\n",
                    metricName, entry.getValue(), timestamp));
        }

        // Export stats
        for (Map.Entry<String, MetricStats> entry : metrics.stats().entrySet()) {
            String metricName = sanitizeMetricName(entry.getKey());
            MetricStats stats = entry.getValue();
            double sum = stats.mean() * stats.count();
            payload.append(String.format("archflow_stats,metric=%s count=%di,sum=%f,min=%f,max=%f,avg=%f %d\n",
                    metricName, stats.count(), sum, stats.min(), stats.max(), stats.mean(), timestamp));
        }

        String url = exportUrl + "?bucket=" + bucket + "&org=" + org;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "text/plain; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            log.debug("Exported {} metrics to InfluxDB", metrics.counters().size() + metrics.values().size());
        } else {
            log.warn("InfluxDB export failed with status: {}", response.statusCode());
        }
    }

    /**
     * Exports metrics to a custom HTTP endpoint as JSON.
     */
    private void exportToHttpEndpoint(AggregatedMetrics metrics) throws IOException, InterruptedException {
        if (exportUrl == null || exportUrl.isEmpty()) {
            log.warn("HTTP export URL not configured, skipping export");
            return;
        }

        // Convert metrics to JSON
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"timestamp\":\"").append(metrics.timestamp()).append("\",");
        json.append("\"counters\":{");
        boolean first = true;
        for (Map.Entry<String, Long> entry : metrics.counters().entrySet()) {
            if (!first) json.append(",");
            json.append("\"").append(entry.getKey()).append("\":").append(entry.getValue());
            first = false;
        }
        json.append("},\"values\":{");
        first = true;
        for (Map.Entry<String, Double> entry : metrics.values().entrySet()) {
            if (!first) json.append(",");
            json.append("\"").append(entry.getKey()).append("\":").append(entry.getValue());
            first = false;
        }
        json.append("}}");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(exportUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            log.debug("Exported metrics to HTTP endpoint");
        } else {
            log.warn("HTTP endpoint export failed with status: {}", response.statusCode());
        }
    }

    /**
     * Exports metrics to log (fallback method).
     */
    private void exportToLog(AggregatedMetrics metrics) {
        log.info("Metrics[timestamp={}, counters={}, values={}, stats={}]",
                metrics.timestamp(),
                metrics.counters(),
                metrics.values(),
                metrics.stats());
    }

    /**
     * Sanitizes metric names for export systems.
     * Replaces invalid characters with underscores.
     */
    private String sanitizeMetricName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    /**
     * Shuts down the exporter.
     */
    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
        }
    }
}

