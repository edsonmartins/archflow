package br.com.archflow.observability.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Central tracer for Archflow using OpenTelemetry.
 *
 * <p>This class provides distributed tracing capabilities:
 * <ul>
 *   <li><b>Spans:</b> Timed operations with parent-child relationships</li>
 *   <li><b>Attributes:</b> Key-value metadata attached to spans</li>
 *   <li><b>Events:</b> Timestamped events within spans</li>
 *   <li><b>Exceptions:</b> Error recording with stack traces</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>
 * // Create a span for a workflow execution
 * try (ManagedSpan span = ArchflowTracer.start("workflow.execute", "workflow.id", "wf-123")) {
 *     // Add attributes
 *     span.setAttribute("input.size", input.size());
 *
 *     // Do work
 *     executeWorkflow(input);
 * }
 *
 * // Time an operation
 * ArchflowTracer.time("agent.process", () -&gt; agent.process(input), "agent.id", "agent-1");
 *
 * // Record an error
 * ArchflowTracer.error("tool.invocation", e, "tool.name", "web-search");
 * </pre>
 */
public class ArchflowTracer {

    private static final Logger log = LoggerFactory.getLogger(ArchflowTracer.class);

    private static volatile ArchflowTracer instance;
    private static final Object LOCK = new Object();

    private final Tracer tracer;
    private final String serviceName;

    private ArchflowTracer(Tracer tracer, String serviceName) {
        this.tracer = tracer;
        this.serviceName = serviceName;
    }

    /**
     * Initializes or returns the singleton ArchflowTracer instance.
     *
     * @param tracer The OpenTelemetry Tracer to use
     * @param serviceName The service name for this application
     * @return The ArchflowTracer instance
     */
    public static ArchflowTracer initialize(Tracer tracer, String serviceName) {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new ArchflowTracer(tracer, serviceName);
                    log.info("ArchflowTracer initialized for service: {}", serviceName);
                }
            }
        }
        return instance;
    }

    /**
     * Initializes with default service name "archflow".
     */
    public static ArchflowTracer initialize(Tracer tracer) {
        return initialize(tracer, "archflow");
    }

    /**
     * Gets the current ArchflowTracer instance.
     *
     * @return The ArchflowTracer instance
     * @throws IllegalStateException if not initialized
     */
    public static ArchflowTracer get() {
        if (instance == null) {
            throw new IllegalStateException("ArchflowTracer not initialized. Call initialize() first.");
        }
        return instance;
    }

    /**
     * Checks if ArchflowTracer is initialized.
     */
    public static boolean isInitialized() {
        return instance != null;
    }

    // ========== Span Creation ==========

    /**
     * Starts a new span with attributes (varargs).
     *
     * @param name The span name
     * @param attributes Key-value pairs (key1, value1, key2, value2, ...)
     * @return A ManagedSpan that will be closed automatically
     */
    public ManagedSpan start(String name, String... attributes) {
        Span span = Span.current();
        boolean isNew = false;

        if (!span.getSpanContext().isValid()) {
            // No current span, create a root span
            span = tracer.spanBuilder(name)
                    .setSpanKind(SpanKind.INTERNAL)
                    .startSpan();
            isNew = true;
        }

        ManagedSpan managed = new ManagedSpan(span, isNew);

        // Add attributes
        for (int i = 0; i < attributes.length; i += 2) {
            if (i + 1 < attributes.length) {
                managed.setAttribute(attributes[i], attributes[i + 1]);
            }
        }

        return managed;
    }

    /**
     * Starts a new child span of the current span.
     *
     * @param name The span name
     * @param attributes Key-value pairs for span attributes
     * @return A ManagedSpan that will be closed automatically
     */
    public ManagedSpan startChild(String name, String... attributes) {
        Span parent = Span.current();
        Span span = tracer.spanBuilder(name)
                .setParent(Context.current())
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();

        ManagedSpan managed = new ManagedSpan(span, true);

        // Add attributes
        for (int i = 0; i < attributes.length; i += 2) {
            if (i + 1 < attributes.length) {
                managed.setAttribute(attributes[i], attributes[i + 1]);
            }
        }

        return managed;
    }

    // ========== Timing Methods ==========

    /**
     * Times a supplier and records the duration.
     *
     * @param name The span name
     * @param supplier The operation to time
     * @param <T> The return type
     * @return The result of the operation
     */
    public <T> T time(String name, ThrowingSupplier<T> supplier, String... attributes) {
        Span span = tracer.spanBuilder(name)
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Add attributes
            for (int i = 0; i < attributes.length; i += 2) {
                if (i + 1 < attributes.length) {
                    span.setAttribute(attributes[i], attributes[i + 1]);
                }
            }

            T result = supplier.get();
            span.setStatus(io.opentelemetry.api.trace.StatusCode.OK);
            return result;
        } catch (Throwable e) {
            recordException(span, e);
            throw new RuntimeException(e);
        } finally {
            span.end();
        }
    }

    /**
     * Times a runnable.
     */
    public void time(String name, ThrowingRunnable runnable, String... attributes) {
        Span span = tracer.spanBuilder(name)
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Add attributes
            for (int i = 0; i < attributes.length; i += 2) {
                if (i + 1 < attributes.length) {
                    span.setAttribute(attributes[i], attributes[i + 1]);
                }
            }

            runnable.run();
            span.setStatus(io.opentelemetry.api.trace.StatusCode.OK);
        } catch (Throwable e) {
            recordException(span, e);
            throw new RuntimeException(e);
        } finally {
            span.end();
        }
    }

    // ========== Error Recording ==========

    /**
     * Records an exception in the current span.
     *
     * @param throwable The exception to record
     */
    public void recordException(Throwable throwable) {
        Span.current().recordException(throwable);
        Span.current().setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, throwable.getMessage());
    }

    /**
     * Records an exception in a new span.
     *
     * @param name The span name
     * @param throwable The exception to record
     * @param attributes Key-value pairs for span attributes
     */
    public void error(String name, Throwable throwable, String... attributes) {
        Span span = tracer.spanBuilder(name)
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            // Add attributes
            for (int i = 0; i < attributes.length; i += 2) {
                if (i + 1 < attributes.length) {
                    span.setAttribute(attributes[i], attributes[i + 1]);
                }
            }
            recordException(span, throwable);
        } finally {
            span.end();
        }
    }

    // ========== Domain-Specific Methods ==========

    /**
     * Starts a workflow execution span.
     */
    public ManagedSpan startWorkflow(String workflowId, String executionId) {
        return start("workflow.execute",
                "workflow.id", workflowId,
                "execution.id", executionId,
                "archflow.component", "workflow");
    }

    /**
     * Starts an agent execution span.
     */
    public ManagedSpan startAgent(String agentId, String executionId) {
        return startChild("agent.execute",
                "agent.id", agentId,
                "execution.id", executionId,
                "archflow.component", "agent");
    }

    /**
     * Starts a tool invocation span.
     */
    public ManagedSpan startTool(String toolName, String invocationId) {
        return startChild("tool.invoke",
                "tool.name", toolName,
                "invocation.id", invocationId,
                "archflow.component", "tool");
    }

    /**
     * Starts an LLM request span.
     */
    public ManagedSpan startLLM(String provider, String model, String requestId) {
        return startChild("llm.request",
                "llm.provider", provider,
                "llm.model", model,
                "request.id", requestId,
                "archflow.component", "llm");
    }

    /**
     * Records an event in the current span.
     *
     * @param name The event name
     * @param attributes Key-value pairs for event attributes
     */
    public static void event(String name, Map<String, String> attributes) {
        Span span = Span.current();
        if (span != null && span.isRecording()) {
            if (attributes != null && !attributes.isEmpty()) {
                AttributesBuilder builder = Attributes.builder();
                attributes.forEach(builder::put);
                span.addEvent(name, builder.build());
            } else {
                span.addEvent(name);
            }
        }
    }

    /**
     * Records an event in the current span (varargs).
     */
    public static void event(String name, String... attributes) {
        Span span = Span.current();
        if (span != null && span.isRecording()) {
            if (attributes.length > 0 && attributes.length % 2 == 0) {
                AttributesBuilder builder = Attributes.builder();
                for (int i = 0; i < attributes.length; i += 2) {
                    if (i + 1 < attributes.length) {
                        builder.put(attributes[i], attributes[i + 1]);
                    }
                }
                span.addEvent(name, builder.build());
            } else {
                span.addEvent(name);
            }
        }
    }

    // ========== Utility Methods ==========

    /**
     * Gets the underlying Tracer.
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
     * Gets the current span.
     */
    public Span currentSpan() {
        return Span.current();
    }

    /**
     * Makes a span the current context.
     */
    public Scope makeCurrent(Span span) {
        return span.makeCurrent();
    }

    private void recordException(Span span, Throwable throwable) {
        span.recordException(throwable);
        span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR,
                throwable != null ? throwable.getMessage() : "error");
    }

    // ========== Inner Classes ==========

    /**
     * A managed span that is closed automatically.
     * Use with try-with-resources for automatic span closing.
     */
    public static class ManagedSpan implements AutoCloseable {
        private final Span span;
        private final long startTimeNanos;
        private final boolean shouldEnd;
        private boolean ended = false;

        ManagedSpan(Span span, boolean shouldEnd) {
            this.span = span;
            this.startTimeNanos = System.nanoTime();
            this.shouldEnd = shouldEnd;
        }

        /**
         * Sets a string attribute on this span.
         */
        public ManagedSpan setAttribute(String key, String value) {
            span.setAttribute(key, value);
            return this;
        }

        /**
         * Sets a long attribute on this span.
         */
        public ManagedSpan setAttribute(String key, long value) {
            span.setAttribute(key, value);
            return this;
        }

        /**
         * Sets a double attribute on this span.
         */
        public ManagedSpan setAttribute(String key, double value) {
            span.setAttribute(key, value);
            return this;
        }

        /**
         * Sets a boolean attribute on this span.
         */
        public ManagedSpan setAttribute(String key, boolean value) {
            span.setAttribute(key, value);
            return this;
        }

        /**
         * Records an event in this span.
         */
        public ManagedSpan addEvent(String name) {
            span.addEvent(name);
            return this;
        }

        /**
         * Records an event with attributes.
         */
        public ManagedSpan addEvent(String name, Map<String, String> attributes) {
            if (attributes != null && !attributes.isEmpty()) {
                AttributesBuilder builder = Attributes.builder();
                attributes.forEach(builder::put);
                span.addEvent(name, builder.build());
            } else {
                span.addEvent(name);
            }
            return this;
        }

        /**
         * Records an event with attributes (varargs).
         */
        public ManagedSpan addEvent(String name, String... attributes) {
            if (attributes.length > 0 && attributes.length % 2 == 0) {
                AttributesBuilder builder = Attributes.builder();
                for (int i = 0; i < attributes.length; i += 2) {
                    if (i + 1 < attributes.length) {
                        builder.put(attributes[i], attributes[i + 1]);
                    }
                }
                span.addEvent(name, builder.build());
            } else {
                span.addEvent(name);
            }
            return this;
        }

        /**
         * Records an exception in this span.
         */
        public ManagedSpan recordException(Throwable throwable) {
            span.recordException(throwable);
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, throwable.getMessage());
            return this;
        }

        /**
         * Sets the span status to OK.
         */
        public ManagedSpan setStatusOk() {
            span.setStatus(io.opentelemetry.api.trace.StatusCode.OK);
            return this;
        }

        /**
         * Sets the span status to ERROR.
         */
        public ManagedSpan setStatusError(String description) {
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, description);
            return this;
        }

        /**
         * Gets the elapsed duration in milliseconds.
         */
        public long elapsedMs() {
            return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTimeNanos);
        }

        /**
         * Ends the span manually.
         */
        public void end() {
            if (!ended && shouldEnd) {
                span.end();
                ended = true;
            }
        }

        @Override
        public void close() {
            end();
        }

        /**
         * Gets the underlying Span.
         */
        public Span getSpan() {
            return span;
        }

        /**
         * Checks if the span has ended.
         */
        public boolean isEnded() {
            return ended;
        }

        /**
         * Checks if this span is valid (not ended and the underlying span is recording).
         */
        public boolean isValid() {
            return !ended && span.isRecording();
        }
    }

    // ========== Functional Interfaces ==========

    /**
     * Supplier that can throw a checked exception.
     */
    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }

    /**
     * Runnable that can throw a checked exception.
     */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
