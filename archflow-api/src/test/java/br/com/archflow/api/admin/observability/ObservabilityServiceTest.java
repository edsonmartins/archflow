package br.com.archflow.api.admin.observability;

import br.com.archflow.api.admin.observability.ObservabilityDtos.AuditEntryDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.FilterDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.MetricsSnapshotDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.OverviewDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.PageDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.SpanDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.TraceDetailDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.TraceSummaryDto;
import br.com.archflow.api.admin.observability.impl.InMemoryTraceStore;
import br.com.archflow.api.admin.observability.impl.ObservabilityService;
import br.com.archflow.observability.audit.AuditAction;
import br.com.archflow.observability.audit.AuditEvent;
import br.com.archflow.observability.audit.AuditRepository;
import br.com.archflow.observability.audit.InMemoryAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityServiceTest {

    private InMemoryTraceStore store;
    private AuditRepository audit;
    private ObservabilityService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryTraceStore(100);
        audit = new InMemoryAuditRepository();
        service = new ObservabilityService(null, store, audit, null);
        seedTraces();
        seedAudit();
    }

    private void seedTraces() {
        Instant now = Instant.now();
        store.record(trace("trace-1", "acme", "order_tracking", "flow-sac", "exec-1",
                now.minusSeconds(60), 450, "OK", null));
        store.record(trace("trace-2", "acme", "complaint", "flow-sac", "exec-2",
                now.minusSeconds(30), 820, "OK", null));
        store.record(trace("trace-3", "acme", "order_tracking", "flow-sac", "exec-3",
                now.minusSeconds(10), 1200, "ERROR", "timeout"));
        store.record(trace("trace-4", "acme", "order_tracking", "flow-sac", "exec-4",
                now.minusSeconds(5), 300, "OK", null));
        // Another tenant — must not leak into acme queries
        store.record(trace("trace-5", "beta", "general", "flow-chat", "exec-5",
                now.minusSeconds(2), 500, "OK", null));
    }

    private void seedAudit() {
        audit.save(buildAudit("user-1", AuditAction.WORKFLOW_EXECUTE, "wf-sac", true));
        audit.save(buildAudit("user-1", AuditAction.WORKFLOW_EXECUTE, "wf-sac", true));
        audit.save(buildAudit("user-2", AuditAction.WORKFLOW_EXECUTE, "wf-chat", false));
        audit.save(buildAudit("user-1", AuditAction.LOGIN_SUCCESS, null, true));
    }

    @Test
    @DisplayName("overview aggregates traces for the given tenant")
    void overview() {
        OverviewDto overview = service.getOverview("acme");

        assertThat(overview.totalExecutionsToday()).isEqualTo(4);
        // 3 ok out of 4
        assertThat(overview.successRate()).isEqualTo(0.75);
        assertThat(overview.errorRate()).isEqualTo(0.25);
        // Avg of 450, 820, 1200, 300 = 692.5
        assertThat(overview.avgLatencyMs()).isEqualTo(692.5);
        assertThat(overview.p95LatencyMs()).isEqualTo(1200d);

        // Top personas: order_tracking (3), complaint (1)
        assertThat(overview.topPersonas()).hasSize(2);
        assertThat(overview.topPersonas().get(0).personaId()).isEqualTo("order_tracking");
        assertThat(overview.topPersonas().get(0).executionCount()).isEqualTo(3);

        // audit count for today >= 4
        assertThat(overview.totalAuditEventsToday()).isGreaterThanOrEqualTo(4);
        assertThat(overview.latencySparkline()).hasSize(12);
    }

    @Test
    @DisplayName("overview ignores cross-tenant traces")
    void overviewTenantIsolation() {
        OverviewDto overview = service.getOverview("beta");
        assertThat(overview.totalExecutionsToday()).isEqualTo(1);
        assertThat(overview.successRate()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("listTraces paginates and sorts by startedAt desc")
    void listTracesPaginates() {
        FilterDto filter = new FilterDto(null, null, "acme", null, null, null, null, null, 0, 2);
        PageDto<TraceSummaryDto> page = service.listTraces(filter);

        assertThat(page.total()).isEqualTo(4);
        assertThat(page.items()).hasSize(2);
        // first item is most recent
        assertThat(page.items().get(0).traceId()).isEqualTo("trace-4");
    }

    @Test
    @DisplayName("listTraces filters by persona")
    void listTracesFilterPersona() {
        FilterDto filter = new FilterDto(null, null, "acme", "complaint", null, null, null, null, 0, 10);
        PageDto<TraceSummaryDto> page = service.listTraces(filter);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).traceId()).isEqualTo("trace-2");
    }

    @Test
    @DisplayName("listTraces filters by status")
    void listTracesFilterStatus() {
        FilterDto filter = new FilterDto(null, null, "acme", null, "ERROR", null, null, null, 0, 10);
        PageDto<TraceSummaryDto> page = service.listTraces(filter);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).traceId()).isEqualTo("trace-3");
    }

    @Test
    @DisplayName("getTrace returns full detail with spans")
    void getTraceDetail() {
        TraceDetailDto detail = service.getTrace("trace-1").orElseThrow();
        assertThat(detail.spans()).hasSize(2);
        assertThat(detail.attributes()).containsEntry("flow.name", "sac-basic");
    }

    @Test
    @DisplayName("getTrace returns empty for unknown id")
    void getTraceUnknown() {
        assertThat(service.getTrace("missing")).isEmpty();
    }

    @Test
    @DisplayName("metrics snapshot returns empty when collector is null")
    void metricsNoCollector() {
        MetricsSnapshotDto snap = service.getMetricsSnapshot("acme");
        assertThat(snap.counters()).isEmpty();
        assertThat(snap.values()).isEmpty();
        assertThat(snap.stats()).isEmpty();
    }

    @Test
    @DisplayName("getMetricSeries builds synthetic buckets from trace latencies")
    void metricSeries() {
        var series = service.getMetricSeries("acme", "latency_ms", 60, 10);
        assertThat(series).hasSize(1);
        assertThat(series.get(0).metric()).isEqualTo("latency_ms");
        assertThat(series.get(0).buckets()).hasSize(10);
        assertThat(series.get(0).values()).hasSize(10);
    }

    @Test
    @DisplayName("getMetricSeries supports count aggregation")
    void metricSeriesCount() {
        var series = service.getMetricSeries("acme", "count", 60, 10);
        assertThat(series.get(0).metric()).isEqualTo("count");
        double total = series.get(0).values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(total).isGreaterThan(0d);
    }

    @Test
    @DisplayName("listAudit delegates to the underlying repository with pagination")
    void listAudit() {
        FilterDto filter = new FilterDto(null, null, null, null, null, null, null, null, 0, 10);
        PageDto<AuditEntryDto> page = service.listAudit(filter);

        assertThat(page.total()).isEqualTo(4);
        assertThat(page.items()).hasSize(4);
    }

    @Test
    @DisplayName("listAudit filters by actor")
    void listAuditByActor() {
        FilterDto filter = new FilterDto(null, null, null, null, null, "user-2", null, null, 0, 10);
        PageDto<AuditEntryDto> page = service.listAudit(filter);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).userId()).isEqualTo("user-2");
    }

    @Test
    @DisplayName("exportAuditCsv returns a CSV header + rows")
    void exportCsv() {
        String csv = service.exportAuditCsv(FilterDto.empty());
        assertThat(csv).startsWith("id,timestamp,action,userId");
        assertThat(csv.split("\n").length).isGreaterThanOrEqualTo(5); // header + 4 rows
        assertThat(csv).contains("WORKFLOW_EXECUTE");
    }

    @Test
    @DisplayName("listTraces respects time window")
    void listTracesTimeWindow() {
        Instant now = Instant.now();
        FilterDto filter = new FilterDto(
                now.minusSeconds(20), null, "acme", null, null, null, null, null, 0, 10);
        PageDto<TraceSummaryDto> page = service.listTraces(filter);

        // Only trace-3 and trace-4 fall inside the last 20s
        assertThat(page.items()).extracting(TraceSummaryDto::traceId)
                .containsExactlyInAnyOrder("trace-3", "trace-4");
    }

    @Test
    @DisplayName("trace store evicts oldest entries when cap is reached")
    void traceStoreEviction() {
        InMemoryTraceStore small = new InMemoryTraceStore(3);
        for (int i = 0; i < 5; i++) {
            small.record(trace("t" + i, "acme", "p", "f", "e",
                    Instant.now().minusSeconds(10 - i), 100, "OK", null));
        }
        assertThat(small.size()).isEqualTo(3);
        assertThat(small.findById("t0")).isEmpty();
        assertThat(small.findById("t1")).isEmpty();
        assertThat(small.findById("t4")).isPresent();
    }

    // ── test helpers ──────────────────────────────────────────────

    private static TraceDetailDto trace(
            String id, String tenant, String persona, String flow, String exec,
            Instant started, long duration, String status, String error) {
        List<SpanDto> spans = List.of(
                InMemoryTraceStore.span("span-1", null, "flow.execute", "INTERNAL",
                        started, duration / 2, status, Map.of("step", "router"), List.of()),
                InMemoryTraceStore.span("span-2", "span-1", "agent.run", "CLIENT",
                        started.plusMillis(10), duration / 2, status, Map.of("agent", persona), List.of()));
        return InMemoryTraceStore.trace(
                id, tenant, persona, flow, exec, started, duration, status, error,
                Map.of("flow.name", "sac-basic"), spans);
    }

    private static AuditEvent buildAudit(String userId, AuditAction action, String resourceId, boolean success) {
        AuditEvent.Builder b = AuditEvent.builder()
                .timestamp(Instant.now())
                .action(action)
                .userId(userId)
                .username(userId)
                .success(success);
        if (resourceId != null) {
            b.resourceType("workflow").resourceId(resourceId);
        }
        if (!success) {
            b.errorMessage("simulated failure");
        }
        return b.build();
    }
}
