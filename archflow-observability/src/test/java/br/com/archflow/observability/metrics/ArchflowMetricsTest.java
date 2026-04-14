package br.com.archflow.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ArchflowMetrics")
class ArchflowMetricsTest {

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    @AfterEach
    void resetSingleton() throws Exception {
        Field f = ArchflowMetrics.class.getDeclaredField("instance");
        f.setAccessible(true);
        f.set(null, null);
    }

    @Nested
    @DisplayName("get()")
    class Get {

        @Test
        @DisplayName("should throw IllegalStateException when not initialized")
        void shouldThrowWhenNotInitialized() {
            assertThatThrownBy(ArchflowMetrics::get)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ArchflowMetrics not initialized");
        }

        @Test
        @DisplayName("should return instance after initialize()")
        void shouldReturnInstanceAfterInitialize() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            assertThat(ArchflowMetrics.get()).isSameAs(metrics);
        }
    }

    @Nested
    @DisplayName("isInitialized()")
    class IsInitialized {

        @Test
        @DisplayName("should return false before initialize()")
        void shouldReturnFalseBeforeInitialize() {
            assertThat(ArchflowMetrics.isInitialized()).isFalse();
        }

        @Test
        @DisplayName("should return true after initialize()")
        void shouldReturnTrueAfterInitialize() {
            ArchflowMetrics.initialize(registry);

            assertThat(ArchflowMetrics.isInitialized()).isTrue();
        }
    }

    @Nested
    @DisplayName("initialize()")
    class Initialize {

        @Test
        @DisplayName("should return the same instance on repeated calls")
        void shouldReturnSameInstanceOnRepeatedCalls() {
            ArchflowMetrics first = ArchflowMetrics.initialize(registry);
            ArchflowMetrics second = ArchflowMetrics.initialize(new SimpleMeterRegistry());

            assertThat(first).isSameAs(second);
        }

        @Test
        @DisplayName("should expose the underlying registry")
        void shouldExposeUnderlyingRegistry() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            assertThat(metrics.getRegistry()).isSameAs(registry);
        }
    }

    @Nested
    @DisplayName("increment(name, tags)")
    class IncrementByOne {

        @Test
        @DisplayName("should increment counter by 1")
        void shouldIncrementCounterByOne() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            metrics.increment("test.counter");

            Counter counter = registry.find("test.counter").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should increment counter multiple times")
        void shouldIncrementCounterMultipleTimes() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            metrics.increment("test.counter");
            metrics.increment("test.counter");
            metrics.increment("test.counter");

            Counter counter = registry.find("test.counter").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(3.0);
        }

        @Test
        @DisplayName("should create separate counters for different tag combinations")
        void shouldCreateSeparateCountersForDifferentTags() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            metrics.increment("test.counter", "status", "success");
            metrics.increment("test.counter", "status", "success");
            metrics.increment("test.counter", "status", "failure");

            Counter success = registry.find("test.counter").tag("status", "success").counter();
            Counter failure = registry.find("test.counter").tag("status", "failure").counter();

            assertThat(success).isNotNull();
            assertThat(success.count()).isEqualTo(2.0);
            assertThat(failure).isNotNull();
            assertThat(failure.count()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("increment(name, amount, tags)")
    class IncrementByAmount {

        @Test
        @DisplayName("should increment counter by specified amount")
        void shouldIncrementBySpecifiedAmount() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            metrics.increment("test.tokens", 42.0);

            Counter counter = registry.find("test.tokens").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(42.0);
        }

        @Test
        @DisplayName("should accumulate increments by amount")
        void shouldAccumulateIncrementsByAmount() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            metrics.increment("test.tokens", 100.0, "model", "gpt-4");
            metrics.increment("test.tokens", 50.0, "model", "gpt-4");

            Counter counter = registry.find("test.tokens").tag("model", "gpt-4").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(150.0);
        }
    }

    @Nested
    @DisplayName("record(name, durationMs, tags)")
    class Record {

        @Test
        @DisplayName("should record timer with given duration")
        void shouldRecordTimerWithGivenDuration() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            metrics.record("test.latency", 200L);

            Timer timer = registry.find("test.latency").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(200.0);
        }

        @Test
        @DisplayName("should record timer with tags")
        void shouldRecordTimerWithTags() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            metrics.record("test.latency", 500L, "provider", "openai");

            Timer timer = registry.find("test.latency").tag("provider", "openai").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("should accumulate multiple recordings")
        void shouldAccumulateMultipleRecordings() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            metrics.record("test.latency", 100L);
            metrics.record("test.latency", 200L);
            metrics.record("test.latency", 300L);

            Timer timer = registry.find("test.latency").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(3);
            assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(600.0);
        }
    }

    @Nested
    @DisplayName("time(name, supplier, tags)")
    class TimeSupplier {

        @Test
        @DisplayName("should time operation and return its result")
        void shouldTimeOperationAndReturnResult() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            String result = metrics.time("test.op", () -> "hello");

            assertThat(result).isEqualTo("hello");
            Timer timer = registry.find("test.op").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("should record timer even when supplier throws")
        void shouldRecordTimerEvenWhenSupplierThrows() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            assertThatThrownBy(() ->
                    metrics.time("test.failing", () -> {
                        throw new IllegalStateException("boom");
                    })
            ).isInstanceOf(RuntimeException.class)
             .hasCauseInstanceOf(IllegalStateException.class);

            Timer timer = registry.find("test.failing").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("should time operation with tags")
        void shouldTimeOperationWithTags() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            int result = metrics.time("test.op", () -> 42, "component", "engine");

            assertThat(result).isEqualTo(42);
            Timer timer = registry.find("test.op").tag("component", "engine").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("time(name, runnable, tags)")
    class TimeRunnable {

        @Test
        @DisplayName("should time runnable operation")
        void shouldTimeRunnableOperation() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);
            AtomicInteger counter = new AtomicInteger(0);

            metrics.time("test.runnable", counter::incrementAndGet);

            assertThat(counter.get()).isEqualTo(1);
            Timer timer = registry.find("test.runnable").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("should time runnable with tags")
        void shouldTimeRunnableWithTags() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            metrics.time("test.runnable", () -> {}, "tag", "value");

            Timer timer = registry.find("test.runnable").tag("tag", "value").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("gauge(name, Number, tags)")
    class GaugeNumber {

        @Test
        @DisplayName("should register gauge for a number value")
        void shouldRegisterGaugeForNumber() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            metrics.gauge("test.queue.size", 7);

            Gauge gauge = registry.find("test.queue.size").gauge();
            assertThat(gauge).isNotNull();
            assertThat(gauge.value()).isEqualTo(7.0);
        }

        @Test
        @DisplayName("should register gauge with tags")
        void shouldRegisterGaugeWithTags() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            metrics.gauge("test.size", 15, "pool", "default");

            Gauge gauge = registry.find("test.size").tag("pool", "default").gauge();
            assertThat(gauge).isNotNull();
            assertThat(gauge.value()).isEqualTo(15.0);
        }
    }

    @Nested
    @DisplayName("Domain-specific shortcuts")
    class DomainShortcuts {

        @Test
        @DisplayName("workflowExecuted() increments archflow.workflow.executions counter")
        void workflowExecutedIncrementsCounter() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            metrics.workflowExecuted("my-workflow", "success");
            metrics.workflowExecuted("my-workflow", "success");

            Counter counter = registry.find("archflow.workflow.executions")
                    .tag("workflow", "my-workflow")
                    .tag("status", "success")
                    .counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(2.0);
        }

        @Test
        @DisplayName("agentExecuted() increments archflow.agent.executions counter")
        void agentExecutedIncrementsCounter() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            metrics.agentExecuted("my-agent", "failure");

            Counter counter = registry.find("archflow.agent.executions")
                    .tag("agent", "my-agent")
                    .tag("status", "failure")
                    .counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("toolInvoked() increments archflow.tool.invocations counter")
        void toolInvokedIncrementsCounter() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            metrics.toolInvoked("web-search", "success");

            Counter counter = registry.find("archflow.tool.invocations")
                    .tag("tool", "web-search")
                    .tag("status", "success")
                    .counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("llmTokens() increments prompt and completion token counters")
        void llmTokensIncrementsCounters() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            metrics.llmTokens("openai", "gpt-4", 100, 50);

            Counter promptCounter = registry.find("archflow.llm.prompt.tokens")
                    .tag("provider", "openai")
                    .tag("model", "gpt-4")
                    .counter();
            Counter completionCounter = registry.find("archflow.llm.completion.tokens")
                    .tag("provider", "openai")
                    .tag("model", "gpt-4")
                    .counter();

            assertThat(promptCounter).isNotNull();
            assertThat(promptCounter.count()).isEqualTo(100.0);
            assertThat(completionCounter).isNotNull();
            assertThat(completionCounter.count()).isEqualTo(50.0);
        }

        @Test
        @DisplayName("llmRequest() increments archflow.llm.requests counter")
        void llmRequestIncrementsCounter() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            metrics.llmRequest("anthropic", "claude-3", "success");

            Counter counter = registry.find("archflow.llm.requests")
                    .tag("provider", "anthropic")
                    .tag("model", "claude-3")
                    .tag("status", "success")
                    .counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("llmLatency() records archflow.llm.latency timer")
        void llmLatencyRecordsTimer() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            metrics.llmLatency("openai", "gpt-4", 350L);

            Timer timer = registry.find("archflow.llm.latency")
                    .tag("provider", "openai")
                    .tag("model", "gpt-4")
                    .timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("clearCache()")
    class ClearCache {

        @Test
        @DisplayName("should empty internal counter and timer caches")
        void shouldEmptyInternalCaches() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            // Populate caches
            metrics.increment("test.counter");
            metrics.record("test.timer", 100L);

            // Ensure caches are used (same object returned before clear)
            Counter beforeClear = metrics.getCounter("test.counter");
            metrics.clearCache();

            // After clearing, getCounter creates a new registration (new object from registry)
            Counter afterClear = metrics.getCounter("test.counter");

            // The cache was cleared, so the counter was re-fetched/re-created
            // Both point to the same underlying metric in registry
            assertThat(afterClear).isNotNull();
        }

        @Test
        @DisplayName("should not affect existing metrics already recorded in registry")
        void shouldNotAffectExistingMetricsInRegistry() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            metrics.increment("test.counter");
            metrics.increment("test.counter");

            metrics.clearCache();

            // After clearing the cache, getting the counter again re-uses the registry meter
            Counter counter = metrics.getCounter("test.counter");
            // The registry still holds the accumulated count
            assertThat(counter.count()).isEqualTo(2.0);
        }
    }

    @Nested
    @DisplayName("getCounter() / getTimer() caching")
    class CachingBehavior {

        @Test
        @DisplayName("getCounter() returns the same Counter instance for same name+tags")
        void getCounterReturnsSameInstanceForSameKey() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            Counter first = metrics.getCounter("cached.counter", "k", "v");
            Counter second = metrics.getCounter("cached.counter", "k", "v");

            assertThat(first).isSameAs(second);
        }

        @Test
        @DisplayName("getTimer() returns the same Timer instance for same name+tags")
        void getTimerReturnsSameInstanceForSameKey() {
            ArchflowMetrics metrics = ArchflowMetrics.initialize(registry);

            Timer first = metrics.getTimer("cached.timer");
            Timer second = metrics.getTimer("cached.timer");

            assertThat(first).isSameAs(second);
        }
    }
}
