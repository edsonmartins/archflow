package br.com.archflow.performance.benchmark;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PerformanceBenchmarker")
class PerformanceBenchmarkerTest {

    private PerformanceBenchmarker benchmarker;

    @BeforeEach
    void setUp() {
        benchmarker = new PerformanceBenchmarker(0); // No warmup for test speed
    }

    @Test
    @DisplayName("should run benchmark the specified number of iterations")
    void shouldRunBenchmark() {
        // Arrange
        AtomicInteger counter = new AtomicInteger(0);

        // Act
        BenchmarkResult result = benchmarker.benchmark("counter-test", counter::incrementAndGet, 100);

        // Assert
        assertThat(counter.get()).isEqualTo(100);
        assertThat(result.name()).isEqualTo("counter-test");
        assertThat(result.iterations()).isEqualTo(100);
    }

    @Test
    @DisplayName("should calculate valid percentiles")
    void shouldCalculatePercentiles() {
        // Act
        BenchmarkResult result = benchmarker.benchmark("percentile-test", () -> {
            // Simple no-op to get measurable latencies
            Math.random();
        }, 1000);

        // Assert
        assertThat(result.p50Ms()).isGreaterThanOrEqualTo(0.0);
        assertThat(result.p95Ms()).isGreaterThanOrEqualTo(result.p50Ms());
        assertThat(result.p99Ms()).isGreaterThanOrEqualTo(result.p95Ms());
        assertThat(result.minMs()).isLessThanOrEqualTo(result.p50Ms());
        assertThat(result.maxMs()).isGreaterThanOrEqualTo(result.p99Ms());
    }

    @Test
    @DisplayName("should measure latency with min <= avg <= max")
    void shouldMeasureLatency() {
        // Act
        BenchmarkResult result = benchmarker.benchmark("latency-test", () -> {
            // Small computation
            long sum = 0;
            for (int i = 0; i < 100; i++) {
                sum += i;
            }
        }, 500);

        // Assert
        assertThat(result.minMs()).isGreaterThanOrEqualTo(0.0);
        assertThat(result.avgMs()).isGreaterThanOrEqualTo(result.minMs());
        assertThat(result.maxMs()).isGreaterThanOrEqualTo(result.avgMs());
        assertThat(result.totalMs()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("should calculate positive throughput")
    void shouldCalculateThroughput() {
        // Act
        BenchmarkResult result = benchmarker.benchmark("throughput-test", () -> {
            // Minimal work
            String.valueOf(42);
        }, 200);

        // Assert
        assertThat(result.throughputPerSecond()).isGreaterThan(0.0);
        assertThat(result.totalMs()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("should handle very fast operations without errors")
    void shouldHandleFastOperations() {
        // Act
        BenchmarkResult result = benchmarker.benchmark("fast-op", () -> {
            // No-op
        }, 10000);

        // Assert
        assertThat(result.iterations()).isEqualTo(10000);
        assertThat(result.minMs()).isGreaterThanOrEqualTo(0.0);
        assertThat(result.throughputPerSecond()).isGreaterThan(0.0);
        assertThat(result.p50Ms()).isGreaterThanOrEqualTo(0.0);
        assertThat(result.p95Ms()).isGreaterThanOrEqualTo(0.0);
        assertThat(result.p99Ms()).isGreaterThanOrEqualTo(0.0);
    }
}
