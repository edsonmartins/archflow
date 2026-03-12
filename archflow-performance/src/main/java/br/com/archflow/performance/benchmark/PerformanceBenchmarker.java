package br.com.archflow.performance.benchmark;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * Lightweight performance benchmark harness for measuring operation latencies.
 *
 * <p>Provides simple benchmarking without the complexity of JMH, suitable for
 * integration tests and quick performance checks.
 *
 * <p>Features:
 * <ul>
 *   <li>Synchronous and asynchronous benchmark methods</li>
 *   <li>Percentile calculations (p50, p95, p99)</li>
 *   <li>Throughput measurement</li>
 *   <li>Optional warmup iterations</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * PerformanceBenchmarker benchmarker = new PerformanceBenchmarker();
 *
 * BenchmarkResult result = benchmarker.benchmark("my-operation", () -> {
 *     // operation to benchmark
 * }, 1000);
 *
 * System.out.println(result);
 * }</pre>
 */
public class PerformanceBenchmarker {

    private static final Logger logger = LoggerFactory.getLogger(PerformanceBenchmarker.class);

    private final int warmupIterations;

    /**
     * Creates a PerformanceBenchmarker with default warmup (10 iterations).
     */
    public PerformanceBenchmarker() {
        this(10);
    }

    /**
     * Creates a PerformanceBenchmarker with the specified warmup iterations.
     *
     * @param warmupIterations the number of warmup iterations before measurement
     */
    public PerformanceBenchmarker(int warmupIterations) {
        if (warmupIterations < 0) {
            throw new IllegalArgumentException("warmupIterations must be >= 0");
        }
        this.warmupIterations = warmupIterations;
    }

    /**
     * Benchmarks a synchronous task.
     *
     * @param name       the benchmark name
     * @param task       the task to benchmark
     * @param iterations the number of measured iterations
     * @return the benchmark result with latency statistics
     */
    public BenchmarkResult benchmark(String name, Runnable task, int iterations) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(task, "task must not be null");
        if (iterations <= 0) {
            throw new IllegalArgumentException("iterations must be > 0");
        }

        logger.info("Starting benchmark '{}': {} warmup + {} measured iterations",
                name, warmupIterations, iterations);

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            task.run();
        }

        // Measured runs
        long[] latenciesNanos = new long[iterations];
        long totalStart = System.nanoTime();

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            task.run();
            latenciesNanos[i] = System.nanoTime() - start;
        }

        long totalNanos = System.nanoTime() - totalStart;

        return buildResult(name, iterations, latenciesNanos, totalNanos);
    }

    /**
     * Benchmarks an asynchronous (Callable) task.
     *
     * @param name       the benchmark name
     * @param task       the callable task to benchmark
     * @param iterations the number of measured iterations
     * @param <T>        the return type of the callable
     * @return the benchmark result with latency statistics
     * @throws Exception if the callable throws an exception
     */
    public <T> BenchmarkResult benchmarkAsync(String name, Callable<T> task, int iterations) throws Exception {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(task, "task must not be null");
        if (iterations <= 0) {
            throw new IllegalArgumentException("iterations must be > 0");
        }

        logger.info("Starting async benchmark '{}': {} warmup + {} measured iterations",
                name, warmupIterations, iterations);

        // Warmup
        for (int i = 0; i < warmupIterations; i++) {
            task.call();
        }

        // Measured runs
        long[] latenciesNanos = new long[iterations];
        long totalStart = System.nanoTime();

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            task.call();
            latenciesNanos[i] = System.nanoTime() - start;
        }

        long totalNanos = System.nanoTime() - totalStart;

        return buildResult(name, iterations, latenciesNanos, totalNanos);
    }

    private BenchmarkResult buildResult(String name, int iterations, long[] latenciesNanos, long totalNanos) {
        Arrays.sort(latenciesNanos);

        double p50 = percentile(latenciesNanos, 50) / 1_000_000.0;
        double p95 = percentile(latenciesNanos, 95) / 1_000_000.0;
        double p99 = percentile(latenciesNanos, 99) / 1_000_000.0;
        double min = latenciesNanos[0] / 1_000_000.0;
        double max = latenciesNanos[latenciesNanos.length - 1] / 1_000_000.0;
        double totalMs = totalNanos / 1_000_000.0;

        long sum = 0;
        for (long l : latenciesNanos) {
            sum += l;
        }
        double avg = (sum / (double) iterations) / 1_000_000.0;
        double throughput = iterations / (totalMs / 1000.0);

        BenchmarkResult result = new BenchmarkResult(
                name, iterations, p50, p95, p99, min, max, avg, totalMs, throughput
        );

        logger.info("Benchmark '{}' completed: {}", name, result);
        return result;
    }

    /**
     * Calculates the given percentile from a sorted array of latencies.
     *
     * @param sortedLatencies sorted array of latency values in nanoseconds
     * @param percentile      the percentile to calculate (0-100)
     * @return the latency value at the given percentile in nanoseconds
     */
    static long percentile(long[] sortedLatencies, int percentile) {
        if (sortedLatencies.length == 0) {
            return 0;
        }
        if (sortedLatencies.length == 1) {
            return sortedLatencies[0];
        }
        int index = (int) Math.ceil(percentile / 100.0 * sortedLatencies.length) - 1;
        return sortedLatencies[Math.max(0, Math.min(index, sortedLatencies.length - 1))];
    }
}
