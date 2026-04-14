package br.com.archflow.api.admin.observability;

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
import java.util.Optional;

/**
 * REST contract for the admin observability surface.
 *
 * <p>All endpoints are tenant-scoped. Superadmin callers may omit
 * {@code tenantId} from the filter to query across tenants; tenant-admin
 * callers have the filter server-forced to their own tenant via the
 * implementation layer.
 *
 * <p>Endpoints (base path {@code /api/admin/observability}):
 * <ul>
 *   <li>{@code GET  /overview}                — dashboard summary cards</li>
 *   <li>{@code GET  /traces}                  — paginated trace list</li>
 *   <li>{@code GET  /traces/{id}}             — full trace detail with spans</li>
 *   <li>{@code GET  /metrics}                 — current aggregated metrics snapshot</li>
 *   <li>{@code GET  /metrics/series?metric=}  — time-bucketed series for one metric</li>
 *   <li>{@code GET  /audit}                   — paginated audit log</li>
 *   <li>{@code GET  /audit/export}            — same as {@code /audit} but CSV body</li>
 * </ul>
 */
public interface ObservabilityController {

    OverviewDto getOverview(String tenantId);

    PageDto<TraceSummaryDto> listTraces(FilterDto filter);

    Optional<TraceDetailDto> getTrace(String traceId);

    MetricsSnapshotDto getMetricsSnapshot(String tenantId);

    List<MetricSeriesDto> getMetricSeries(String tenantId, String metric, int bucketSeconds, int buckets);

    PageDto<AuditEntryDto> listAudit(FilterDto filter);

    /**
     * Exports the filtered audit log as CSV. Returns the CSV body as a
     * string; binding layer wraps it into an {@code HttpResponse} with
     * {@code text/csv} content type.
     */
    String exportAuditCsv(FilterDto filter);

    // ── Running flows ──────────────────────────────────────────────

    /**
     * Returns a snapshot of all flows currently executing.
     *
     * @param tenantId optional tenant filter; null means all tenants (superadmin only)
     * @return list of running flow snapshots, empty when none are active
     */
    List<RunningFlowDto> listRunningFlows(String tenantId);

    /**
     * Requests cancellation of a running flow.
     *
     * <p>The authenticated caller's tenant is checked against the flow's
     * tenant to prevent cross-tenant cancellation.
     *
     * @param tenantId      the authenticated caller's tenant
     * @param flowId        flow to cancel
     * @throws IllegalArgumentException  if the flow is not running
     * @throws SecurityException         if tenantId does not match the flow's tenant
     */
    void cancelFlow(String tenantId, String flowId);
}
