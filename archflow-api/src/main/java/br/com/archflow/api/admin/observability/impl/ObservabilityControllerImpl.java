package br.com.archflow.api.admin.observability.impl;

import br.com.archflow.api.admin.observability.ObservabilityController;
import br.com.archflow.api.admin.observability.ObservabilityDtos.AuditEntryDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.FilterDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.MetricSeriesDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.MetricsSnapshotDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.OverviewDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.PageDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.RunningFlowDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.TraceDetailDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.TraceSummaryDto;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Framework-agnostic implementation of {@link ObservabilityController}.
 *
 * <p>Delegates every endpoint to {@link ObservabilityService}, which owns
 * the aggregation logic and the mapping to the public DTOs. The binding
 * layer (Spring WebFlux, Jetty, ...) is responsible for authentication,
 * tenant scoping and response serialization — this class only forwards
 * calls so every framework gets the exact same semantics.
 */
public class ObservabilityControllerImpl implements ObservabilityController {

    private final ObservabilityService service;

    public ObservabilityControllerImpl(ObservabilityService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public OverviewDto getOverview(String tenantId) {
        return service.getOverview(tenantId);
    }

    @Override
    public PageDto<TraceSummaryDto> listTraces(FilterDto filter) {
        return service.listTraces(filter);
    }

    @Override
    public Optional<TraceDetailDto> getTrace(String traceId) {
        return service.getTrace(traceId);
    }

    @Override
    public MetricsSnapshotDto getMetricsSnapshot(String tenantId) {
        return service.getMetricsSnapshot(tenantId);
    }

    @Override
    public List<MetricSeriesDto> getMetricSeries(
            String tenantId, String metric, int bucketSeconds, int buckets) {
        return service.getMetricSeries(tenantId, metric, bucketSeconds, buckets);
    }

    @Override
    public PageDto<AuditEntryDto> listAudit(FilterDto filter) {
        return service.listAudit(filter);
    }

    @Override
    public String exportAuditCsv(FilterDto filter) {
        return service.exportAuditCsv(filter);
    }

    @Override
    public List<RunningFlowDto> listRunningFlows(String tenantId) {
        return service.listRunningFlows(tenantId);
    }

    @Override
    public void cancelFlow(String tenantId, String flowId) {
        service.cancelFlow(tenantId, flowId);
    }
}
