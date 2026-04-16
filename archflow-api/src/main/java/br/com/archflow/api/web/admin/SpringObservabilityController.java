package br.com.archflow.api.web.admin;

import br.com.archflow.api.admin.observability.ObservabilityController;
import br.com.archflow.api.admin.observability.ObservabilityDtos.*;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/observability")
public class SpringObservabilityController {

    private final ObservabilityController delegate;

    public SpringObservabilityController(ObservabilityController delegate) {
        this.delegate = delegate;
    }

    @GetMapping("/overview")
    public OverviewDto getOverview(@RequestParam(required = false) String tenantId) {
        return delegate.getOverview(tenantId);
    }

    @GetMapping("/traces")
    public PageDto<TraceSummaryDto> listTraces(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var filter = new FilterDto(
                from != null ? Instant.parse(from) : null,
                to != null ? Instant.parse(to) : null,
                tenantId, null, status, null, null, search, page, size);
        return delegate.listTraces(filter);
    }

    @GetMapping("/traces/{id}")
    public ResponseEntity<TraceDetailDto> getTrace(@PathVariable String id) {
        return delegate.getTrace(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/metrics")
    public MetricsSnapshotDto getMetrics(@RequestParam(required = false) String tenantId) {
        return delegate.getMetricsSnapshot(tenantId);
    }

    @GetMapping("/metrics/series")
    public List<MetricSeriesDto> getMetricSeries(
            @RequestParam(required = false) String tenantId,
            @RequestParam String metric,
            @RequestParam(defaultValue = "60") int bucketSeconds,
            @RequestParam(defaultValue = "60") int buckets) {
        return delegate.getMetricSeries(tenantId, metric, bucketSeconds, buckets);
    }

    @GetMapping("/audit")
    public PageDto<AuditEntryDto> listAudit(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var filter = new FilterDto(
                from != null ? Instant.parse(from) : null,
                to != null ? Instant.parse(to) : null,
                tenantId, null, null, null, action, search, page, size);
        return delegate.listAudit(filter);
    }

    @GetMapping("/audit/export")
    public String exportAuditCsv(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        var filter = new FilterDto(
                from != null ? Instant.parse(from) : null,
                to != null ? Instant.parse(to) : null,
                tenantId, null, null, null, null, null, 0, Integer.MAX_VALUE);
        return delegate.exportAuditCsv(filter);
    }

    @GetMapping("/running")
    public List<RunningFlowDto> listRunningFlows(@RequestParam(required = false) String tenantId) {
        return delegate.listRunningFlows(tenantId);
    }

    @PostMapping("/running/{flowId}/cancel")
    public void cancelFlow(@RequestParam(required = false) String tenantId, @PathVariable String flowId) {
        delegate.cancelFlow(tenantId, flowId);
    }
}
