package br.com.archflow.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Counter.Builder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;

/**
 * Central metrics registry for Archflow.
 *
 * <p>This class provides a unified interface for collecting metrics across the system:
 * <ul>
 *   <li><b>Counters:</b> Monotonically increasing values (requests, errors, executions)</li>
 *   <li><b>Timers:</b> Latency measurements with percentiles</li>
 *   <li><b>Gauges:</b> Point-in-time values (queue sizes, memory usage)</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>
 * // Record a workflow execution
 * ArchflowMetrics.workflowExecuted("my-workflow", "success");
 *
 * // Time an operation
 * ArchflowMetrics.time("agent.execution", () -&gt; agent.execute(input));
 *
 * // Increment a counter
 * ArchflowMetrics.increment("llm.tokens", "provider", "openai", "model", "gpt-4");
 * </pre>
 */
public class ArchflowMetrics {

    private static final Logger log = LoggerFactory.getLogger(ArchflowMetrics.class);

    private static volatile ArchflowMetrics instance;
    private static final Object LOCK = new Object();

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();

    private ArchflowMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Initializes or returns the singleton ArchflowMetrics instance.
     *
     * @param registry The Micrometer MeterRegistry to use
     * @return The ArchflowMetrics instance
     */
    public static ArchflowMetrics initialize(MeterRegistry registry) {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new ArchflowMetrics(registry);
                    log.info("ArchflowMetrics initialized with registry: {}", registry.getClass().getSimpleName());
                }
            }
        }
        return instance;
    }

    /**
     * Gets the current ArchflowMetrics instance.
     *
     * @return The ArchflowMetrics instance
     * @throws IllegalStateException if not initialized
     */
    public static ArchflowMetrics get() {
        if (instance == null) {
            throw new IllegalStateException("ArchflowMetrics not initialized. Call initialize() first.");
        }
        return instance;
    }

    /**
     * Checks if ArchflowMetrics is initialized.
     */
    public static boolean isInitialized() {
        return instance != null;
    }

    // ========== Counter Methods ==========

    /**
     * Registers and increments a counter.
     *
     * @param name The counter name
     * @param tags Key-value pairs for dimensionalization
     */
    public void increment(String name, String... tags) {
        getCounter(name, tags).increment();
    }

    /**
     * Registers and increments a counter by a specific amount.
     *
     * @param name The counter name
     * @param amount The amount to increment by
     * @param tags Key-value pairs for dimensionalization
     */
    public void increment(String name, double amount, String... tags) {
        getCounter(name, tags).increment(amount);
    }

    /**
     * Gets or creates a counter.
     */
    public Counter getCounter(String name, String... tags) {
        String key = buildCounterKey(name, tags);
        return counterCache.computeIfAbsent(key, k -> {
            io.micrometer.core.instrument.Counter.Builder builder = Counter.builder(name)
                    .description(name + " counter");
            if (tags.length > 0) {
                builder.tags(tags);
            }
            return builder.register(registry);
        });
    }

    // ========== Timer Methods ==========

    /**
     * Records the duration of an operation in milliseconds.
     *
     * @param name The timer name
     * @param durationMs The duration in milliseconds
     * @param tags Key-value pairs for dimensionalization
     */
    public void record(String name, long durationMs, String... tags) {
        getTimer(name, tags).record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Times an operation and returns its result.
     *
     * @param name The timer name
     * @param operation The operation to time
     * @param tags Key-value pairs for dimensionalization
     * @param <T> The return type of the operation
     * @return The result of the operation
     */
    public <T> T time(String name, ThrowingSupplier<T> operation, String... tags) {
        Timer timer = getTimer(name, tags);
        long start = System.nanoTime();
        try {
            T result = operation.get();
            timer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            return result;
        } catch (Throwable e) {
            timer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            throw new RuntimeException(e);
        }
    }

    /**
     * Times a runnable operation.
     *
     * @param name The timer name
     * @param operation The operation to time
     * @param tags Key-value pairs for dimensionalization
     */
    public void time(String name, ThrowingRunnable operation, String... tags) {
        time(name, () -> {
            operation.run();
            return null;
        }, tags);
    }

    /**
     * Gets or creates a timer.
     */
    public Timer getTimer(String name, String... tags) {
        String key = buildTimerKey(name, tags);
        return timerCache.computeIfAbsent(key, k -> {
            Timer.Builder builder = Timer.builder(name)
                    .description(name + " timer")
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .publishPercentileHistogram(true);
            if (tags.length > 0) {
                builder.tags(tags);
            }
            return builder.register(registry);
        });
    }

    // ========== Gauge Methods ==========

    /**
     * Registers a gauge that samples a value on-demand.
     *
     * @param name The gauge name
     * @param obj The object to gauge
     * @param valueFunction Function to extract the numeric value
     * @param tags Key-value pairs for dimensionalization
     */
    public <T> void gauge(String name, T obj, java.util.function.ToDoubleFunction<T> valueFunction, String... tags) {
        io.micrometer.core.instrument.Gauge.builder(name, obj, valueFunction)
                .description(name + " gauge")
                .tags(tags)
                .register(registry);
    }

    /**
     * Registers a gauge for a double value.
     */
    public void gauge(String name, java.util.function.DoubleSupplier valueSupplier, String... tags) {
        // Using a simple wrapper object
        class DoubleBox {
            double value;
            DoubleBox(double value) { this.value = value; }
        }
        DoubleBox box = new DoubleBox(valueSupplier.getAsDouble());
        gauge(name, box, b -> {
            b.value = valueSupplier.getAsDouble();
            return b.value;
        }, tags);
    }

    /**
     * Registers a strong reference gauge for a number.
     * Use this when the Number object should not be garbage collected.
     */
    public void gauge(String name, Number number, String... tags) {
        io.micrometer.core.instrument.Gauge.builder(name, number, n -> n.doubleValue())
                .description(name + " gauge")
                .tags(tags)
                .register(registry);
    }

    // ========== Domain-Specific Metrics ==========

    /**
     * Records a workflow execution.
     *
     * @param workflowId The workflow identifier
     * @param status The execution status (success, failure, timeout)
     */
    public void workflowExecuted(String workflowId, String status) {
        increment("archflow.workflow.executions",
                "workflow", workflowId,
                "status", status);
    }

    /**
     * Records an agent execution.
     *
     * @param agentId The agent identifier
     * @param status The execution status
     */
    public void agentExecuted(String agentId, String status) {
        increment("archflow.agent.executions",
                "agent", agentId,
                "status", status);
    }

    /**
     * Records a tool invocation.
     *
     * @param toolName The tool name
     * @param status The invocation status
     */
    public void toolInvoked(String toolName, String status) {
        increment("archflow.tool.invocations",
                "tool", toolName,
                "status", status);
    }

    /**
     * Records LLM token usage.
     *
     * @param provider The LLM provider (openai, anthropic, etc.)
     * @param model The model name
     * @param promptTokens The number of prompt tokens
     * @param completionTokens The number of completion tokens
     */
    public void llmTokens(String provider, String model, long promptTokens, long completionTokens) {
        increment("archflow.llm.prompt.tokens", promptTokens,
                "provider", provider,
                "model", model);
        increment("archflow.llm.completion.tokens", completionTokens,
                "provider", provider,
                "model", model);
    }

    /**
     * Records an LLM request.
     *
     * @param provider The LLM provider
     * @param model The model name
     * @param status The request status
     */
    public void llmRequest(String provider, String model, String status) {
        increment("archflow.llm.requests",
                "provider", provider,
                "model", model,
                "status", status);
    }

    /**
     * Records the time taken for an LLM request.
     *
     * @param provider The LLM provider
     * @param model The model name
     * @param durationMs The duration in milliseconds
     */
    public void llmLatency(String provider, String model, long durationMs) {
        record("archflow.llm.latency", durationMs,
                "provider", provider,
                "model", model);
    }

    // ========== Utility Methods ==========

    /**
     * Gets the underlying MeterRegistry.
     */
    public MeterRegistry getRegistry() {
        return registry;
    }

    /**
     * Clears all cached counters and timers.
     * Useful for testing or when the registry changes.
     */
    public void clearCache() {
        counterCache.clear();
        timerCache.clear();
    }

    private String buildCounterKey(String name, String[] tags) {
        return name + ":" + String.join(":", tags);
    }

    private String buildTimerKey(String name, String[] tags) {
        return name + ":" + String.join(":", tags);
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
