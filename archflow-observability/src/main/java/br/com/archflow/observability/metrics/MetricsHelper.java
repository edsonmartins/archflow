package br.com.archflow.observability.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Helper class for convenient metric recording.
 *
 * <p>Provides static methods that safely handle cases where metrics are not initialized.
 * This prevents metric collection failures from affecting application logic.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * // Safe increment - no-op if not initialized
 * MetricsHelper.increment("my.counter");
 *
 * // Safe timing - returns null if metrics not available
 * MetricsHelper.time(() -&gt; myOperation(), "my.operation");
 *
 * // Check if metrics are available
 * if (MetricsHelper.isEnabled()) {
 *     // Do metric-heavy operations
 * }
 * </pre>
 */
public final class MetricsHelper {

    private static final Logger log = LoggerFactory.getLogger(MetricsHelper.class);

    private MetricsHelper() {
        // Utility class
    }

    /**
     * Checks if metrics are enabled.
     */
    public static boolean isEnabled() {
        return ArchflowMetrics.isInitialized();
    }

    /**
     * Gets the ArchflowMetrics instance, or null if not initialized.
     */
    public static ArchflowMetrics getMetrics() {
        if (ArchflowMetrics.isInitialized()) {
            return ArchflowMetrics.get();
        }
        return null;
    }

    /**
     * Gets the MeterRegistry, or null if not initialized.
     */
    public static MeterRegistry getRegistry() {
        ArchflowMetrics metrics = getMetrics();
        return metrics != null ? metrics.getRegistry() : null;
    }

    // ========== Counter Methods ==========

    /**
     * Safely increments a counter.
     */
    public static void increment(String name, String... tags) {
        ArchflowMetrics metrics = getMetrics();
        if (metrics != null) {
            metrics.increment(name, tags);
        }
    }

    /**
     * Safely increments a counter by a specific amount.
     */
    public static void increment(String name, double amount, String... tags) {
        ArchflowMetrics metrics = getMetrics();
        if (metrics != null) {
            metrics.increment(name, amount, tags);
        }
    }

    // ========== Timer Methods ==========

    /**
     * Safely records a duration.
     */
    public static void record(String name, long durationMs, String... tags) {
        ArchflowMetrics metrics = getMetrics();
        if (metrics != null) {
            metrics.record(name, durationMs, tags);
        }
    }

    /**
     * Safely times a supplier.
     */
    public static <T> T time(Supplier<T> supplier, String name, String... tags) {
        ArchflowMetrics metrics = getMetrics();
        if (metrics != null) {
            return metrics.time(name, supplier::get, tags);
        }
        return supplier.get();
    }

    /**
     * Safely times a callable.
     */
    public static <T> T time(Callable<T> callable, String name, String... tags) throws Exception {
        ArchflowMetrics metrics = getMetrics();
        if (metrics != null) {
            return metrics.time(name, callable::call, tags);
        }
        return callable.call();
    }

    /**
     * Safely times a runnable.
     */
    public static void time(Runnable runnable, String name, String... tags) {
        ArchflowMetrics metrics = getMetrics();
        if (metrics != null) {
            metrics.time(name, runnable::run, tags);
        } else {
            runnable.run();
        }
    }

    /**
     * Creates a timed sample for a code block.
     *
     * <p>Usage:</p>
     * <pre>
     * try (TimedSample ignored = MetricsHelper.startTimer("operation")) {
     *     // code to time
     * }
     * </pre>
     */
    public static TimedSample startTimer(String name, String... tags) {
        return new TimedSample(name, tags);
    }

    // ========== Domain-Specific Methods ==========

    /**
     * Records a workflow execution.
     */
    public static void workflowExecuted(String workflowId, String status) {
        ArchflowMetrics metrics = getMetrics();
        if (metrics != null) {
            metrics.workflowExecuted(workflowId, status);
        }
    }

    /**
     * Records an agent execution.
     */
    public static void agentExecuted(String agentId, String status) {
        ArchflowMetrics metrics = getMetrics();
        if (metrics != null) {
            metrics.agentExecuted(agentId, status);
        }
    }

    /**
     * Records a tool invocation.
     */
    public static void toolInvoked(String toolName, String status) {
        ArchflowMetrics metrics = getMetrics();
        if (metrics != null) {
            metrics.toolInvoked(toolName, status);
        }
    }

    /**
     * Records LLM token usage.
     */
    public static void llmTokens(String provider, String model, long promptTokens, long completionTokens) {
        ArchflowMetrics metrics = getMetrics();
        if (metrics != null) {
            metrics.llmTokens(provider, model, promptTokens, completionTokens);
        }
    }

    /**
     * Records an LLM request.
     */
    public static void llmRequest(String provider, String model, String status) {
        ArchflowMetrics metrics = getMetrics();
        if (metrics != null) {
            metrics.llmRequest(provider, model, status);
        }
    }

    /**
     * Records LLM latency.
     */
    public static void llmLatency(String provider, String model, long durationMs) {
        ArchflowMetrics metrics = getMetrics();
        if (metrics != null) {
            metrics.llmLatency(provider, model, durationMs);
        }
    }

    /**
     * Records a counter for error conditions.
     */
    public static void error(String errorType, String component, String... additionalTags) {
        String[] allTags = new String[2 + additionalTags.length];
        allTags[0] = "type";
        allTags[1] = errorType;
        allTags[2] = "component";
        allTags[3] = component;
        System.arraycopy(additionalTags, 0, allTags, 4, additionalTags.length);
        increment("archflow.errors", allTags);
    }

    /**
     * A try-with-resources compatible timer.
     */
    public static class TimedSample implements AutoCloseable {
        private final String name;
        private final String[] tags;
        private final long startTimeNanos;
        private long recordedDurationNanos = -1;

        TimedSample(String name, String[] tags) {
            this.name = name;
            this.tags = tags;
            this.startTimeNanos = System.nanoTime();
        }

        /**
         * Records the elapsed time and returns the duration in milliseconds.
         */
        public long stop() {
            if (recordedDurationNanos >= 0) {
                return TimeUnit.NANOSECONDS.toMillis(recordedDurationNanos);
            }
            recordedDurationNanos = System.nanoTime() - startTimeNanos;
            record(name, TimeUnit.NANOSECONDS.toMillis(recordedDurationNanos), tags);
            return TimeUnit.NANOSECONDS.toMillis(recordedDurationNanos);
        }

        /**
         * Gets the elapsed time in milliseconds without stopping the timer.
         */
        public long elapsed() {
            long elapsed = System.nanoTime() - startTimeNanos;
            return TimeUnit.NANOSECONDS.toMillis(elapsed);
        }

        @Override
        public void close() {
            if (recordedDurationNanos < 0) {
                stop();
            }
        }

        /**
         * Gets the duration if stopped, or -1 if still running.
         */
        public long duration() {
            if (recordedDurationNanos >= 0) {
                return TimeUnit.NANOSECONDS.toMillis(recordedDurationNanos);
            }
            return -1;
        }
    }

    /**
     * Builder for creating timers with custom configuration.
     */
    public static class TimerBuilder {
        private final String name;
        private final java.util.List<String> tags = new java.util.ArrayList<>();

        TimerBuilder(String name) {
            this.name = name;
        }

        public static TimerBuilder timer(String name) {
            return new TimerBuilder(name);
        }

        public TimerBuilder tag(String key, String value) {
            tags.add(key);
            tags.add(value);
            return this;
        }

        public TimerBuilder tags(String... tags) {
            for (int i = 0; i < tags.length; i += 2) {
                if (i + 1 < tags.length) {
                    tag(tags[i], tags[i + 1]);
                }
            }
            return this;
        }

        /**
         * Records a duration.
         */
        public void record(long durationMs) {
            MetricsHelper.record(name, durationMs, tags.toArray(new String[0]));
        }

        /**
         * Times a runnable.
         */
        public void time(Runnable runnable) {
            MetricsHelper.time(runnable, name, tags.toArray(new String[0]));
        }

        /**
         * Times a supplier.
         */
        public <T> T time(Supplier<T> supplier) {
            return MetricsHelper.time(supplier, name, tags.toArray(new String[0]));
        }

        /**
         * Creates a TimedSample for manual timing.
         */
        public TimedSample start() {
            return MetricsHelper.startTimer(name, tags.toArray(new String[0]));
        }
    }
}
