package br.com.archflow.observability.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for Prometheus metrics export.
 *
 * <p>This class sets up:
 * <ul>
 *   <li><b>Prometheus Meter Registry:</b> Micrometer registry for Prometheus</li>
 *   <li><b>JVM Metrics:</b> Memory, threads, CPU</li>
 *   <li><b>Custom Metrics:</b> Archflow-specific metrics</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>
 * // Create Prometheus registry
 * PrometheusConfig prometheus = PrometheusConfig.builder()
 *     .jvmMetricsEnabled(true)
 *     .build();
 *
 * // Get metrics scrape text
 * String metrics = prometheus.scrape();
 *
 * // Expose via HTTP endpoint (e.g., Spring Boot Actuator)
 * // In Spring Boot, just add micrometer-registry-prometheus to classpath
 * // and metrics will be available at /actuator/prometheus
 * </pre>
 *
 * <p>Spring Boot configuration:</p>
 * <pre>
 * // In application.yml
 * management:
 *   endpoints:
 *     web:
 *       exposure:
 *         include: prometheus,health,info
 *   metrics:
 *     export:
 *       prometheus:
 *         enabled: true
 * </pre>
 */
public class PrometheusConfig {

    private static final Logger log = LoggerFactory.getLogger(PrometheusConfig.class);

    private final PrometheusMeterRegistry registry;
    private final boolean jvmMetricsEnabled;

    private PrometheusConfig(Builder builder) {
        this.jvmMetricsEnabled = builder.jvmMetricsEnabled;

        // Create Prometheus registry
        this.registry = new PrometheusMeterRegistry(DefaultPrometheusConfig.INSTANCE);

        // Enable JVM metrics if requested
        if (builder.jvmMetricsEnabled) {
            enableJvmMetrics();
        }

        log.info("PrometheusConfig created: jvmMetrics={}", builder.jvmMetricsEnabled);
    }

    /**
     * Creates a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the Prometheus meter registry.
     */
    public PrometheusMeterRegistry getRegistry() {
        return registry;
    }

    /**
     * Gets the metrics scrape text for Prometheus.
     * Use this to expose metrics via an HTTP endpoint.
     *
     * <p>Example with a simple HTTP server:</p>
     * <pre>
     * HttpServer server = HttpServer.create(new InetSocketAddress(9090), 0);
     * server.createContext("/metrics", exchange -> {
     *     String response = prometheusConfig.scrape();
     *     exchange.sendResponseHeaders(200, response.length());
     *     try (OutputStream os = exchange.getResponseBody()) {
     *         os.write(response.getBytes());
     *     }
     * });
     * server.start();
     * </pre>
     */
    public String scrape() {
        return registry.scrape();
    }

    /**
     * Initializes ArchflowMetrics with this registry.
     */
    public PrometheusConfig initializeArchflowMetrics() {
        ArchflowMetrics.initialize(registry);
        log.info("ArchflowMetrics initialized with Prometheus registry");
        return this;
    }

    private void enableJvmMetrics() {
        // JVM memory metrics
        new JvmMemoryMetrics().bindTo(registry);

        // JVM thread metrics
        new JvmThreadMetrics().bindTo(registry);

        // Processor metrics
        new ProcessorMetrics().bindTo(registry);

        log.debug("JVM metrics enabled");
    }

    /**
     * Builder for creating PrometheusConfig.
     */
    public static class Builder {
        private boolean jvmMetricsEnabled = true;

        private Builder() {
        }

        /**
         * Enables or disables JVM metrics.
         *
         * @param enabled Whether to enable JVM metrics (default: true)
         */
        public Builder jvmMetricsEnabled(boolean enabled) {
            this.jvmMetricsEnabled = enabled;
            return this;
        }

        /**
         * Builds the PrometheusConfig.
         */
        public PrometheusConfig build() {
            return new PrometheusConfig(this);
        }
    }

    /**
     * Configuration enum for PrometheusMeterRegistry.
     */
    public enum DefaultPrometheusConfig implements io.micrometer.prometheusmetrics.PrometheusConfig {
        INSTANCE;

        @Override
        public String get(String key) {
            return null;
        }

        @Override
        public String prefix() {
            return "prometheus";
        }
    }
}
