package br.com.archflow.api.admin.observability;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Data-transfer objects exchanged with the admin observability UI.
 *
 * <p>All DTOs are plain records to keep serialization framework-agnostic —
 * both Jackson (Spring Boot) and any other mapper can turn them into JSON
 * without extra annotations.
 */
public final class ObservabilityDtos {

    private ObservabilityDtos() {}

    /**
     * Paginated envelope used by every list-style endpoint.
     */
    public record PageDto<T>(List<T> items, int page, int pageSize, long total) {
        public static <T> PageDto<T> of(List<T> items, int page, int pageSize, long total) {
            return new PageDto<>(items, page, pageSize, total);
        }
    }

    /**
     * High-level overview shown on the observability landing page.
     *
     * @param totalExecutionsToday running total of flow executions in the
     *                             current UTC day
     * @param successRate          ratio 0..1 of successful executions
     * @param avgLatencyMs         average end-to-end flow latency (ms)
     * @param p95LatencyMs         p95 flow latency (ms)
     * @param errorRate            ratio 0..1 of failed executions
     * @param activeStreams        active SSE emitters at snapshot time
     * @param totalAuditEventsToday total audit events recorded today
     * @param topPersonas          top personas by execution count in the
     *                             last 24h (may be empty)
     * @param latencySparkline     series of latency samples for a
     *                             sparkline render
     */
    public record OverviewDto(
            long totalExecutionsToday,
            double successRate,
            double avgLatencyMs,
            double p95LatencyMs,
            double errorRate,
            int activeStreams,
            long totalAuditEventsToday,
            List<PersonaStatDto> topPersonas,
            List<Double> latencySparkline
    ) {}

    public record PersonaStatDto(String personaId, long executionCount, double successRate) {}

    // ── Traces ─────────────────────────────────────────────────────

    /**
     * Summary entry listed on the traces table.
     */
    public record TraceSummaryDto(
            String traceId,
            String tenantId,
            String personaId,
            String flowId,
            String executionId,
            Instant startedAt,
            long durationMs,
            String status,
            int spanCount,
            String error
    ) {}

    /**
     * Drill-down view of a single trace with the full span tree.
     */
    public record TraceDetailDto(
            String traceId,
            String tenantId,
            String personaId,
            String flowId,
            String executionId,
            Instant startedAt,
            long durationMs,
            String status,
            String error,
            Map<String, String> attributes,
            List<SpanDto> spans
    ) {}

    /**
     * Individual span inside a trace.
     */
    public record SpanDto(
            String spanId,
            String parentSpanId,
            String name,
            String kind,
            Instant startedAt,
            long durationMs,
            String status,
            Map<String, String> attributes,
            List<SpanEventDto> events
    ) {}

    public record SpanEventDto(String name, Instant at, Map<String, String> attributes) {}

    // ── Metrics ────────────────────────────────────────────────────

    /**
     * Aggregated metric snapshot — counters + value-style gauges.
     */
    public record MetricsSnapshotDto(
            Instant timestamp,
            Map<String, Long> counters,
            Map<String, Double> values,
            Map<String, MetricStatsDto> stats
    ) {}

    public record MetricStatsDto(
            long count,
            double min,
            double max,
            double mean,
            double median
    ) {}

    /**
     * Time-bucketed series for a single metric.
     */
    public record MetricSeriesDto(
            String metric,
            List<Instant> buckets,
            List<Double> values
    ) {}

    // ── Audit ──────────────────────────────────────────────────────

    public record AuditEntryDto(
            String id,
            Instant timestamp,
            String action,
            String userId,
            String username,
            String resourceType,
            String resourceId,
            boolean success,
            String errorMessage,
            Map<String, String> context,
            String ipAddress,
            String sessionId,
            String traceId
    ) {}

    // ── Filter inputs ─────────────────────────────────────────────

    /**
     * Common filter applied to traces, metrics and audit queries.
     *
     * @param from      inclusive lower bound (UTC). Null means no lower bound.
     * @param to        inclusive upper bound (UTC). Null means no upper bound.
     * @param tenantId  optional tenant filter
     * @param personaId optional persona filter (traces only)
     * @param status    optional status filter (traces, e.g. OK/ERROR)
     * @param actor     optional actor filter (audit)
     * @param action    optional action filter (audit)
     * @param search    free-text filter — matches on name/id
     * @param page      0-indexed
     * @param pageSize  1..500
     */
    public record FilterDto(
            Instant from,
            Instant to,
            String tenantId,
            String personaId,
            String status,
            String actor,
            String action,
            String search,
            int page,
            int pageSize
    ) {
        public FilterDto {
            if (pageSize <= 0) pageSize = 50;
            if (pageSize > 500) pageSize = 500;
            if (page < 0) page = 0;
        }

        public static FilterDto empty() {
            return new FilterDto(null, null, null, null, null, null, null, null, 0, 50);
        }
    }
}
