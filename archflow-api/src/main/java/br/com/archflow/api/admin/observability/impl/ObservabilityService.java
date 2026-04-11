package br.com.archflow.api.admin.observability.impl;

import br.com.archflow.agent.metrics.AggregatedMetrics;
import br.com.archflow.agent.metrics.MetricsCollector;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.admin.observability.ObservabilityDtos.AuditEntryDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.FilterDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.MetricSeriesDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.MetricStatsDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.MetricsSnapshotDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.OverviewDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.PageDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.PersonaStatDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.TraceDetailDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.TraceSummaryDto;
import br.com.archflow.observability.audit.AuditEvent;
import br.com.archflow.observability.audit.AuditRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Aggregator-only service that powers the admin observability surface.
 *
 * <p>This service owns zero state of its own — everything is delegated to
 * existing producers:
 * <ul>
 *   <li>{@link MetricsCollector} for counters, gauges and histograms</li>
 *   <li>{@link InMemoryTraceStore} for the trace rolling window</li>
 *   <li>{@link AuditRepository} for audit log queries</li>
 *   <li>{@link EventStreamRegistry} for active SSE emitter snapshots</li>
 * </ul>
 *
 * <p>The service is the single place in the codebase that translates
 * internal types into the public {@link ObservabilityDtos}, so every
 * framework binding (Spring WebFlux, Jetty, etc.) can reuse the same
 * logic.
 *
 * <p>All dependencies are optional — the service degrades gracefully to
 * empty responses when a collector is absent, so tests and Spring Boot
 * auto-configuration can wire components independently.
 */
public class ObservabilityService {

    private final MetricsCollector metricsCollector;
    private final InMemoryTraceStore traceStore;
    private final AuditRepository auditRepository;
    private final EventStreamRegistry eventStreamRegistry;

    public ObservabilityService(
            MetricsCollector metricsCollector,
            InMemoryTraceStore traceStore,
            AuditRepository auditRepository,
            EventStreamRegistry eventStreamRegistry) {
        this.metricsCollector = metricsCollector;
        this.traceStore = traceStore != null ? traceStore : new InMemoryTraceStore();
        this.auditRepository = auditRepository;
        this.eventStreamRegistry = eventStreamRegistry;
    }

    public InMemoryTraceStore traceStore() {
        return traceStore;
    }

    // ── Overview ──────────────────────────────────────────────────

    public OverviewDto getOverview(String tenantId) {
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);

        List<TraceSummaryDto> todayTraces = traceStore.query(
                tenantId, null, null, null, startOfDay, null);

        long total = todayTraces.size();
        long successes = todayTraces.stream()
                .filter(t -> t.status() != null && t.status().equalsIgnoreCase("OK"))
                .count();
        double successRate = total == 0 ? 0d : (double) successes / total;
        double errorRate = total == 0 ? 0d : 1d - successRate;

        double avgLatency = todayTraces.stream()
                .mapToLong(TraceSummaryDto::durationMs)
                .average()
                .orElse(0d);
        double p95 = percentile(todayTraces.stream()
                .map(TraceSummaryDto::durationMs)
                .sorted()
                .toList(), 0.95);

        List<PersonaStatDto> topPersonas = topPersonas(todayTraces, 5);
        List<Double> spark = latencySparkline(todayTraces, 12);

        int activeStreams = 0;
        if (eventStreamRegistry != null) {
            try {
                activeStreams = eventStreamRegistry.getStats().activeEmitters();
            } catch (Exception ignored) {
                activeStreams = 0;
            }
        }

        long auditCount = 0;
        if (auditRepository != null) {
            try {
                auditCount = auditRepository.count(AuditRepository.AuditQuery.builder()
                        .startTime(startOfDay)
                        .endTime(Instant.now()));
            } catch (Exception ignored) {
                auditCount = 0;
            }
        }

        return new OverviewDto(
                total,
                successRate,
                avgLatency,
                p95,
                errorRate,
                activeStreams,
                auditCount,
                topPersonas,
                spark);
    }

    // ── Traces ────────────────────────────────────────────────────

    public PageDto<TraceSummaryDto> listTraces(FilterDto filter) {
        FilterDto f = filter == null ? FilterDto.empty() : filter;
        List<TraceSummaryDto> all = traceStore.query(
                f.tenantId(),
                f.personaId(),
                f.status(),
                f.search(),
                f.from(),
                f.to());
        long total = all.size();
        int fromIdx = Math.min(f.page() * f.pageSize(), all.size());
        int toIdx = Math.min(fromIdx + f.pageSize(), all.size());
        List<TraceSummaryDto> page = all.subList(fromIdx, toIdx);
        return PageDto.of(new ArrayList<>(page), f.page(), f.pageSize(), total);
    }

    public Optional<TraceDetailDto> getTrace(String traceId) {
        return traceStore.findById(traceId);
    }

    // ── Metrics ───────────────────────────────────────────────────

    public MetricsSnapshotDto getMetricsSnapshot(String tenantId) {
        if (metricsCollector == null) {
            return new MetricsSnapshotDto(Instant.now(), Map.of(), Map.of(), Map.of());
        }
        AggregatedMetrics agg = metricsCollector.getAggregatedMetrics();
        Map<String, MetricStatsDto> stats = new HashMap<>();
        agg.stats().forEach((name, s) -> stats.put(
                name,
                new MetricStatsDto(s.count(), s.min(), s.max(), s.mean(), s.median())));

        return new MetricsSnapshotDto(
                agg.timestamp(),
                new HashMap<>(agg.counters()),
                new HashMap<>(agg.values()),
                stats);
    }

    /**
     * Build a synthetic time-bucketed series for a given metric by
     * sampling the latencies of recent traces. The current
     * {@link MetricsCollector} does not retain historical buckets, so
     * this projection is derived from {@link InMemoryTraceStore}.
     */
    public List<MetricSeriesDto> getMetricSeries(
            String tenantId, String metric, int bucketSeconds, int buckets) {
        if (buckets <= 0) buckets = 12;
        if (bucketSeconds <= 0) bucketSeconds = 300;
        Instant now = Instant.now();
        Instant windowStart = now.minusSeconds((long) buckets * bucketSeconds);

        List<TraceSummaryDto> traces = traceStore.query(
                tenantId, null, null, null, windowStart, null);

        List<Instant> bucketStarts = new ArrayList<>(buckets);
        List<Double> values = new ArrayList<>(buckets);
        for (int i = 0; i < buckets; i++) {
            Instant start = windowStart.plusSeconds((long) i * bucketSeconds);
            Instant end = start.plusSeconds(bucketSeconds);
            double bucketValue = aggregateBucket(metric, traces, start, end);
            bucketStarts.add(start);
            values.add(bucketValue);
        }
        return List.of(new MetricSeriesDto(metric == null ? "latency_ms" : metric, bucketStarts, values));
    }

    // ── Audit ─────────────────────────────────────────────────────

    public PageDto<AuditEntryDto> listAudit(FilterDto filter) {
        FilterDto f = filter == null ? FilterDto.empty() : filter;
        if (auditRepository == null) {
            return PageDto.of(List.of(), f.page(), f.pageSize(), 0);
        }
        AuditRepository.AuditQuery query = buildAuditQuery(f);
        query.limit(f.pageSize());
        query.offset(f.page() * f.pageSize());
        List<AuditEvent> events = auditRepository.query(query);
        long total = auditRepository.count(buildAuditQuery(f));
        List<AuditEntryDto> mapped = events.stream().map(this::toAuditDto).toList();
        return PageDto.of(mapped, f.page(), f.pageSize(), total);
    }

    public String exportAuditCsv(FilterDto filter) {
        FilterDto bigPage = filter == null
                ? new FilterDto(null, null, null, null, null, null, null, null, 0, 500)
                : new FilterDto(
                        filter.from(),
                        filter.to(),
                        filter.tenantId(),
                        filter.personaId(),
                        filter.status(),
                        filter.actor(),
                        filter.action(),
                        filter.search(),
                        0,
                        500);
        PageDto<AuditEntryDto> page = listAudit(bigPage);
        StringBuilder sb = new StringBuilder();
        sb.append("id,timestamp,action,userId,username,resourceType,resourceId,success,errorMessage,ipAddress\n");
        for (AuditEntryDto e : page.items()) {
            sb.append(csv(e.id())).append(',')
                    .append(csv(e.timestamp() == null ? "" : e.timestamp().toString())).append(',')
                    .append(csv(e.action())).append(',')
                    .append(csv(e.userId())).append(',')
                    .append(csv(e.username())).append(',')
                    .append(csv(e.resourceType())).append(',')
                    .append(csv(e.resourceId())).append(',')
                    .append(e.success()).append(',')
                    .append(csv(e.errorMessage())).append(',')
                    .append(csv(e.ipAddress())).append('\n');
        }
        return sb.toString();
    }

    // ── helpers ───────────────────────────────────────────────────

    private AuditRepository.AuditQuery buildAuditQuery(FilterDto f) {
        AuditRepository.AuditQuery q = AuditRepository.AuditQuery.builder()
                .sortBy("timestamp")
                .sortDescending(true);
        if (f.from() != null) q.startTime(f.from());
        if (f.to() != null) q.endTime(f.to());
        if (f.actor() != null && !f.actor().isBlank()) q.userId(f.actor());
        if (f.action() != null && !f.action().isBlank()) {
            try {
                q.action(br.com.archflow.observability.audit.AuditAction.valueOf(f.action()));
            } catch (IllegalArgumentException ignored) {
                // unknown action — leave unset so the query returns nothing
            }
        }
        return q;
    }

    private AuditEntryDto toAuditDto(AuditEvent e) {
        Map<String, String> ctx = e.getContext() == null ? new HashMap<>() : new HashMap<>(e.getContext());
        return new AuditEntryDto(
                e.getId(),
                e.getTimestamp(),
                e.getAction() == null ? null : e.getAction().name(),
                e.getUserId(),
                e.getUsername(),
                e.getResourceType(),
                e.getResourceId(),
                e.isSuccess(),
                e.getErrorMessage(),
                ctx,
                e.getIpAddress(),
                e.getSessionId(),
                e.getTraceId());
    }

    private static double percentile(List<Long> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) return 0d;
        int idx = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        idx = Math.max(0, Math.min(idx, sortedValues.size() - 1));
        return sortedValues.get(idx).doubleValue();
    }

    private static List<PersonaStatDto> topPersonas(List<TraceSummaryDto> traces, int limit) {
        Map<String, long[]> stats = new HashMap<>(); // [count, successes]
        for (TraceSummaryDto t : traces) {
            if (t.personaId() == null) continue;
            long[] s = stats.computeIfAbsent(t.personaId(), k -> new long[]{0, 0});
            s[0]++;
            if ("OK".equalsIgnoreCase(t.status())) s[1]++;
        }
        List<PersonaStatDto> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : stats.entrySet()) {
            long count = e.getValue()[0];
            long ok = e.getValue()[1];
            double rate = count == 0 ? 0d : (double) ok / count;
            out.add(new PersonaStatDto(e.getKey(), count, rate));
        }
        out.sort(Comparator.comparingLong(PersonaStatDto::executionCount).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private static List<Double> latencySparkline(List<TraceSummaryDto> traces, int buckets) {
        if (traces.isEmpty()) {
            return Collections.nCopies(buckets, 0d);
        }
        List<Long> recent = traces.stream()
                .sorted(Comparator.comparing(TraceSummaryDto::startedAt))
                .map(TraceSummaryDto::durationMs)
                .toList();
        int bucketSize = Math.max(1, recent.size() / buckets);
        List<Double> out = new ArrayList<>(buckets);
        for (int i = 0; i < buckets; i++) {
            int fromIdx = i * bucketSize;
            int toIdx = Math.min(fromIdx + bucketSize, recent.size());
            if (fromIdx >= recent.size()) {
                out.add(0d);
                continue;
            }
            double avg = recent.subList(fromIdx, toIdx).stream()
                    .mapToLong(Long::longValue)
                    .average()
                    .orElse(0d);
            out.add(avg);
        }
        return out;
    }

    private static double aggregateBucket(
            String metric, List<TraceSummaryDto> traces, Instant from, Instant to) {
        String key = metric == null ? "latency_ms" : metric.toLowerCase();
        List<TraceSummaryDto> inBucket = traces.stream()
                .filter(t -> !t.startedAt().isBefore(from) && t.startedAt().isBefore(to))
                .toList();
        if (inBucket.isEmpty()) return 0d;
        return switch (key) {
            case "throughput", "count" -> inBucket.size();
            case "error_rate" -> {
                long errs = inBucket.stream()
                        .filter(t -> t.status() != null && !"OK".equalsIgnoreCase(t.status()))
                        .count();
                yield (double) errs / inBucket.size();
            }
            default -> inBucket.stream()
                    .mapToLong(TraceSummaryDto::durationMs)
                    .average()
                    .orElse(0d);
        };
    }

    private static String csv(String value) {
        if (value == null) return "";
        boolean needsQuotes = value.contains(",") || value.contains("\"") || value.contains("\n");
        if (!needsQuotes) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
