import { api } from './api';

/**
 * TypeScript mirror of `ObservabilityDtos` in archflow-api. Types are
 * kept intentionally thin — they describe the wire format and nothing
 * more. React pages consume them via {@link observabilityApi}.
 */

export interface PageDto<T> {
    items: T[];
    page: number;
    pageSize: number;
    total: number;
}

export interface PersonaStat {
    personaId: string;
    executionCount: number;
    successRate: number;
}

export interface OverviewDto {
    totalExecutionsToday: number;
    successRate: number;
    avgLatencyMs: number;
    p95LatencyMs: number;
    errorRate: number;
    activeStreams: number;
    totalAuditEventsToday: number;
    topPersonas: PersonaStat[];
    latencySparkline: number[];
}

export type TraceStatus = 'OK' | 'ERROR' | 'UNKNOWN';

export interface TraceSummaryDto {
    traceId: string;
    tenantId: string | null;
    personaId: string | null;
    flowId: string | null;
    executionId: string | null;
    startedAt: string;
    durationMs: number;
    status: TraceStatus | string;
    spanCount: number;
    error: string | null;
}

export interface SpanEventDto {
    name: string;
    at: string;
    attributes: Record<string, string>;
}

export interface SpanDto {
    spanId: string;
    parentSpanId: string | null;
    name: string;
    kind: string;
    startedAt: string;
    durationMs: number;
    status: string;
    attributes: Record<string, string>;
    events: SpanEventDto[];
}

export interface TraceDetailDto {
    traceId: string;
    tenantId: string | null;
    personaId: string | null;
    flowId: string | null;
    executionId: string | null;
    startedAt: string;
    durationMs: number;
    status: string;
    error: string | null;
    attributes: Record<string, string>;
    spans: SpanDto[];
}

export interface MetricStatsDto {
    count: number;
    min: number;
    max: number;
    mean: number;
    median: number;
}

export interface MetricsSnapshotDto {
    timestamp: string;
    counters: Record<string, number>;
    values: Record<string, number>;
    stats: Record<string, MetricStatsDto>;
}

export interface MetricSeriesDto {
    metric: string;
    buckets: string[];
    values: number[];
}

export interface AuditEntryDto {
    id: string;
    timestamp: string;
    action: string;
    userId: string | null;
    username: string | null;
    resourceType: string | null;
    resourceId: string | null;
    success: boolean;
    errorMessage: string | null;
    context: Record<string, string>;
    ipAddress: string | null;
    sessionId: string | null;
    traceId: string | null;
}

export interface ObservabilityFilter {
    from?: string;
    to?: string;
    tenantId?: string;
    personaId?: string;
    status?: string;
    actor?: string;
    action?: string;
    search?: string;
    page?: number;
    pageSize?: number;
}

function buildQuery(filter: ObservabilityFilter | undefined): string {
    if (!filter) return '';
    const params = new URLSearchParams();
    if (filter.from) params.set('from', filter.from);
    if (filter.to) params.set('to', filter.to);
    if (filter.tenantId) params.set('tenantId', filter.tenantId);
    if (filter.personaId) params.set('personaId', filter.personaId);
    if (filter.status) params.set('status', filter.status);
    if (filter.actor) params.set('actor', filter.actor);
    if (filter.action) params.set('action', filter.action);
    if (filter.search) params.set('search', filter.search);
    if (filter.page !== undefined) params.set('page', String(filter.page));
    if (filter.pageSize !== undefined) params.set('pageSize', String(filter.pageSize));
    const qs = params.toString();
    return qs ? `?${qs}` : '';
}

export const observabilityApi = {
    getOverview: (tenantId?: string) =>
        api.get<OverviewDto>(
            `/admin/observability/overview${tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ''}`,
        ),

    listTraces: (filter?: ObservabilityFilter) =>
        api.get<PageDto<TraceSummaryDto>>(`/admin/observability/traces${buildQuery(filter)}`),

    getTrace: (traceId: string) =>
        api.get<TraceDetailDto>(`/admin/observability/traces/${encodeURIComponent(traceId)}`),

    getMetricsSnapshot: (tenantId?: string) =>
        api.get<MetricsSnapshotDto>(
            `/admin/observability/metrics${tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ''}`,
        ),

    getMetricSeries: (metric: string, params?: {
        tenantId?: string;
        bucketSeconds?: number;
        buckets?: number;
    }) => {
        const qs = new URLSearchParams();
        qs.set('metric', metric);
        if (params?.tenantId) qs.set('tenantId', params.tenantId);
        if (params?.bucketSeconds) qs.set('bucketSeconds', String(params.bucketSeconds));
        if (params?.buckets) qs.set('buckets', String(params.buckets));
        return api.get<MetricSeriesDto[]>(`/admin/observability/metrics/series?${qs.toString()}`);
    },

    listAudit: (filter?: ObservabilityFilter) =>
        api.get<PageDto<AuditEntryDto>>(`/admin/observability/audit${buildQuery(filter)}`),

    auditExportUrl: (filter?: ObservabilityFilter) =>
        `/api/admin/observability/audit/export${buildQuery(filter)}`,
};
