package br.com.archflow.performance.benchmark;

/**
 * Immutable result of a benchmark run containing latency percentiles,
 * throughput, and summary statistics.
 *
 * @param name                the benchmark name
 * @param iterations          the number of iterations executed
 * @param p50Ms               the 50th percentile latency in milliseconds
 * @param p95Ms               the 95th percentile latency in milliseconds
 * @param p99Ms               the 99th percentile latency in milliseconds
 * @param minMs               the minimum latency in milliseconds
 * @param maxMs               the maximum latency in milliseconds
 * @param avgMs               the average latency in milliseconds
 * @param totalMs             the total elapsed time in milliseconds
 * @param throughputPerSecond the number of operations per second
 */
public record BenchmarkResult(
        String name,
        int iterations,
        double p50Ms,
        double p95Ms,
        double p99Ms,
        double minMs,
        double maxMs,
        double avgMs,
        double totalMs,
        double throughputPerSecond
) {

    @Override
    public String toString() {
        return String.format(
                "BenchmarkResult{name='%s', iterations=%d, p50=%.3fms, p95=%.3fms, p99=%.3fms, " +
                        "min=%.3fms, max=%.3fms, avg=%.3fms, total=%.3fms, throughput=%.1f ops/s}",
                name, iterations, p50Ms, p95Ms, p99Ms, minMs, maxMs, avgMs, totalMs, throughputPerSecond
        );
    }
}
