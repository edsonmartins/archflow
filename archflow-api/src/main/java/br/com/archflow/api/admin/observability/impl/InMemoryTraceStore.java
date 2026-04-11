package br.com.archflow.api.admin.observability.impl;

import br.com.archflow.api.admin.observability.ObservabilityDtos.SpanDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.SpanEventDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.TraceDetailDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.TraceSummaryDto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Lightweight in-memory trace store.
 *
 * <p>{@code ArchflowTracer} is emit-only — it forwards spans to an
 * OpenTelemetry collector and does not retain them. The admin dashboard
 * needs a retrieval surface for drill-down, so this store acts as a
 * sidecar: the application layer calls {@link #record} whenever it wants
 * to make a trace visible in the UI.
 *
 * <p>Characteristics:
 * <ul>
 *   <li>Bounded by {@link #maxTraces} (default 1000) with FIFO eviction.</li>
 *   <li>Partitioned by {@code tenantId} for fast per-tenant queries.</li>
 *   <li>Thread-safe via concurrent collections.</li>
 *   <li>No persistence — intentional; the store is a rolling window meant
 *       for real-time inspection, not long-term storage.</li>
 * </ul>
 *
 * <p>The store is instantiated once and shared across all observability
 * endpoints. Producers (flow engine, agent runner, orchestrator) call
 * {@link #record(TraceDetailDto)} from their hot paths.
 */
public class InMemoryTraceStore {

    private final int maxTraces;
    private final ConcurrentHashMap<String, TraceDetailDto> byId = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> order = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<String>> byTenant = new ConcurrentHashMap<>();

    public InMemoryTraceStore() {
        this(1000);
    }

    public InMemoryTraceStore(int maxTraces) {
        if (maxTraces < 1) throw new IllegalArgumentException("maxTraces must be >= 1");
        this.maxTraces = maxTraces;
    }

    /**
     * Record a trace snapshot. Fully-formed traces win over partial ones:
     * calling {@code record} with a {@code traceId} that already exists
     * replaces the previous entry.
     */
    public void record(TraceDetailDto trace) {
        Objects.requireNonNull(trace, "trace");
        if (trace.traceId() == null || trace.traceId().isBlank()) return;

        boolean isNew = byId.put(trace.traceId(), trace) == null;
        if (isNew) {
            order.add(trace.traceId());
            byTenant
                    .computeIfAbsent(nullSafe(trace.tenantId()), k -> new ConcurrentLinkedDeque<>())
                    .add(trace.traceId());
            evictIfNeeded();
        }
    }

    public Optional<TraceDetailDto> findById(String traceId) {
        return Optional.ofNullable(byId.get(traceId));
    }

    /**
     * List traces that match the (optional) filters. Results are sorted
     * by {@code startedAt} descending.
     */
    public List<TraceSummaryDto> query(
            String tenantId,
            String personaId,
            String status,
            String search,
            Instant from,
            Instant to) {
        List<TraceDetailDto> all;
        if (tenantId != null && !tenantId.isBlank()) {
            ConcurrentLinkedDeque<String> ids = byTenant.get(tenantId);
            if (ids == null) return List.of();
            all = new ArrayList<>(ids.size());
            for (String id : ids) {
                TraceDetailDto t = byId.get(id);
                if (t != null) all.add(t);
            }
        } else {
            all = new ArrayList<>(byId.values());
        }

        String lowered = search == null ? null : search.toLowerCase();
        List<TraceSummaryDto> filtered = new ArrayList<>();
        for (TraceDetailDto t : all) {
            if (personaId != null && !personaId.equals(t.personaId())) continue;
            if (status != null && !status.equalsIgnoreCase(t.status())) continue;
            if (from != null && t.startedAt().isBefore(from)) continue;
            if (to != null && t.startedAt().isAfter(to)) continue;
            if (lowered != null) {
                String haystack = ((t.traceId() == null ? "" : t.traceId()) + " "
                        + (t.flowId() == null ? "" : t.flowId()) + " "
                        + (t.executionId() == null ? "" : t.executionId())).toLowerCase();
                if (!haystack.contains(lowered)) continue;
            }
            filtered.add(toSummary(t));
        }
        filtered.sort(Comparator.comparing(TraceSummaryDto::startedAt).reversed());
        return filtered;
    }

    public int size() {
        return byId.size();
    }

    public void clear() {
        byId.clear();
        order.clear();
        byTenant.clear();
    }

    // ── helpers ─────────────────────────────────────────────────

    private static TraceSummaryDto toSummary(TraceDetailDto t) {
        int spanCount = t.spans() == null ? 0 : t.spans().size();
        return new TraceSummaryDto(
                t.traceId(),
                t.tenantId(),
                t.personaId(),
                t.flowId(),
                t.executionId(),
                t.startedAt(),
                t.durationMs(),
                t.status(),
                spanCount,
                t.error());
    }

    private void evictIfNeeded() {
        while (byId.size() > maxTraces) {
            String oldest = order.poll();
            if (oldest == null) break;
            TraceDetailDto removed = byId.remove(oldest);
            if (removed != null) {
                ConcurrentLinkedDeque<String> ids = byTenant.get(nullSafe(removed.tenantId()));
                if (ids != null) ids.remove(oldest);
            }
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "__none__" : s;
    }

    // ── Convenience builders used by producers + tests ────────────

    /** Build a trace record from primitive fields — avoids leaking DTO noise to producers. */
    public static TraceDetailDto trace(
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
            List<SpanDto> spans) {
        return new TraceDetailDto(
                traceId,
                tenantId,
                personaId,
                flowId,
                executionId,
                startedAt,
                durationMs,
                status,
                error,
                attributes == null ? new HashMap<>() : new HashMap<>(attributes),
                spans == null ? new ArrayList<>() : new ArrayList<>(spans));
    }

    public static SpanDto span(
            String spanId,
            String parentSpanId,
            String name,
            String kind,
            Instant startedAt,
            long durationMs,
            String status,
            Map<String, String> attributes,
            List<SpanEventDto> events) {
        return new SpanDto(
                spanId,
                parentSpanId,
                name,
                kind,
                startedAt,
                durationMs,
                status,
                attributes == null ? new HashMap<>() : new HashMap<>(attributes),
                events == null ? new ArrayList<>() : new ArrayList<>(events));
    }
}
