package br.com.archflow.observability.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Configuration for OTLP (OpenTelemetry Protocol) trace export.
 *
 * <p>This class sets up distributed tracing with OpenTelemetry:
 * <ul>
 *   <li><b>Tracer Provider:</b> SDK tracer with OTLP export</li>
 *   <li><b>Exporter:</b> gRPC to OTLP-compatible backends (Jaeger, Tempo, etc.)</li>
 *   <li><b>Resource Detection:</b> Service name, version, environment</li>
 * </ul>
 *
 * <p>Usage with Jaeger:</p>
 * <pre>
 * // Configure OTLP export to Jaeger
 * OtlpTracerConfig config = OtlpTracerConfig.builder()
 *     .serviceName("archflow")
 *     .endpoint("http://localhost:4317")  // Jaeger OTLP gRPC endpoint
 *     .build();
 *
 * // Initialize ArchflowTracer
 * config.initializeArchflowTracer();
 * </pre>
 *
 * <p>Or with environment variables:</p>
 * <pre>
 * export OTEL_SERVICE_NAME=archflow
 * export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
 * </pre>
 *
 * <p>Jaeger setup:</p>
 * <pre>
 * # Run Jaeger with Docker
 * docker run -d --name jaeger \
 *   -p 4317:4317 \
 *   -p 16686:16686 \
 *   jaegertracing/all-in-one:latest
 *
 * # Access Jaeger UI at http://localhost:16686
 * </pre>
 */
public class OtlpTracerConfig {

    private static final Logger log = LoggerFactory.getLogger(OtlpTracerConfig.class);

    private static final String DEFAULT_ENDPOINT = "http://localhost:4317";
    private static final String DEFAULT_SERVICE_NAME = "archflow";
    private static final Duration DEFAULT_EXPORT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_EXPORT_INTERVAL = Duration.ofSeconds(5);

    private final OpenTelemetry openTelemetry;
    private final SdkTracerProvider tracerProvider;
    private final SpanExporter spanExporter;
    private final Tracer tracer;
    private final String serviceName;

    private OtlpTracerConfig(Builder builder) {
        this.serviceName = builder.serviceName;
        this.spanExporter = createExporter(builder);
        this.tracerProvider = createTracerProvider(builder, spanExporter);
        this.openTelemetry = createOpenTelemetry(tracerProvider);
        this.tracer = openTelemetry.getTracer(builder.instrumentationName,
                builder.instrumentationVersion);

        log.info("OpenTelemetry initialized: service={}, endpoint={}",
                serviceName, builder.endpoint);
    }

    /**
     * Creates a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the OpenTelemetry instance.
     */
    public OpenTelemetry getOpenTelemetry() {
        return openTelemetry;
    }

    /**
     * Gets the Tracer.
     */
    public Tracer getTracer() {
        return tracer;
    }

    /**
     * Gets the service name.
     */
    public String getServiceName() {
        return serviceName;
    }

    /**
     * Initializes ArchflowTracer with this configuration.
     */
    public OtlpTracerConfig initializeArchflowTracer() {
        ArchflowTracer.initialize(tracer, serviceName);
        log.info("ArchflowTracer initialized with service: {}", serviceName);
        return this;
    }

    /**
     * Shuts down the tracer provider and exporter.
     */
    public void shutdown() {
        try {
            tracerProvider.shutdown();
            spanExporter.shutdown();
            log.info("OpenTelemetry shut down successfully");
        } catch (Exception e) {
            log.error("Failed to shut down OpenTelemetry", e);
        }
    }

    private SpanExporter createExporter(Builder builder) {
        return OtlpGrpcSpanExporter.builder()
                .setEndpoint(builder.endpoint)
                .setTimeout(builder.exportTimeout)
                .build();
    }

    private SdkTracerProvider createTracerProvider(Builder builder, SpanExporter exporter) {
        Resource resource = Resource.getDefault()
                .merge(Resource.builder()
                        .put("service.name", builder.serviceName)
                        .put("service.version", builder.serviceVersion)
                        .put("deployment.environment", builder.environment)
                        .build());

        return SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(exporter)
                        .setScheduleDelay(builder.exportInterval)
                        .build())
                .setResource(resource)
                .build();
    }

    private OpenTelemetry createOpenTelemetry(SdkTracerProvider tracerProvider) {
        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
    }

    /**
     * Builder for creating OtlpTracerConfig.
     */
    public static class Builder {
        private String endpoint = getEnv("OTEL_EXPORTER_OTLP_ENDPOINT", DEFAULT_ENDPOINT);
        private String serviceName = getEnv("OTEL_SERVICE_NAME", DEFAULT_SERVICE_NAME);
        private String serviceVersion = getEnv("OTEL_SERVICE_VERSION", "1.0.0");
        private String environment = getEnv("OTEL_ENVIRONMENT", "development");
        private String instrumentationName = "archflow-tracer";
        private String instrumentationVersion = "1.0.0";
        private Duration exportTimeout = DEFAULT_EXPORT_TIMEOUT;
        private Duration exportInterval = DEFAULT_EXPORT_INTERVAL;

        private Builder() {
        }

        /**
         * Sets the OTLP endpoint.
         *
         * @param endpoint The endpoint URL (e.g., http://localhost:4317 for gRPC)
         */
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /**
         * Sets the service name.
         *
         * @param serviceName The service name (default: archflow)
         */
        public Builder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        /**
         * Sets the service version.
         *
         * @param version The service version (default: 1.0.0)
         */
        public Builder serviceVersion(String version) {
            this.serviceVersion = version;
            return this;
        }

        /**
         * Sets the deployment environment.
         *
         * @param environment The environment (development, staging, production)
         */
        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        /**
         * Sets the instrumentation name.
         *
         * @param name The instrumentation name (default: archflow-tracer)
         */
        public Builder instrumentationName(String name) {
            this.instrumentationName = name;
            return this;
        }

        /**
         * Sets the instrumentation version.
         *
         * @param version The instrumentation version (default: 1.0.0)
         */
        public Builder instrumentationVersion(String version) {
            this.instrumentationVersion = version;
            return this;
        }

        /**
         * Sets the export timeout.
         *
         * @param timeout The timeout duration
         */
        public Builder exportTimeout(Duration timeout) {
            this.exportTimeout = timeout;
            return this;
        }

        /**
         * Sets the export interval.
         *
         * @param interval The interval duration
         */
        public Builder exportInterval(Duration interval) {
            this.exportInterval = interval;
            return this;
        }

        /**
         * Builds the OtlpTracerConfig.
         */
        public OtlpTracerConfig build() {
            return new OtlpTracerConfig(this);
        }

        private String getEnv(String key, String defaultValue) {
            String value = System.getenv(key);
            return value != null ? value : defaultValue;
        }
    }

    /**
     * Pre-configured endpoints for common backends.
     */
    public static class Endpoints {
        /**
         * Jaeger OTLP gRPC endpoint (default).
         */
        public static final String JAEGER = "http://localhost:4317";

        /**
         * Grafana Tempo OTLP gRPC endpoint.
         */
        public static final String TEMPO = "http://localhost:4317";
    }

    /**
     * Quick setup for Jaeger.
     */
    public static OtlpTracerConfig forJaeger() {
        return builder()
                .endpoint(Endpoints.JAEGER)
                .build();
    }

    /**
     * Quick setup for local development with Jaeger.
     */
    public static OtlpTracerConfig forLocalDevelopment() {
        return builder()
                .endpoint(Endpoints.JAEGER)
                .environment("development")
                .build();
    }
}
