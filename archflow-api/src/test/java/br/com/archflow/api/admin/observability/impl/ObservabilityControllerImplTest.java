package br.com.archflow.api.admin.observability.impl;

import br.com.archflow.api.admin.observability.ObservabilityDtos.AuditEntryDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.FilterDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.MetricSeriesDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.MetricsSnapshotDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.OverviewDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.PageDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.TraceDetailDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.TraceSummaryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ObservabilityControllerImpl")
class ObservabilityControllerImplTest {

    @Mock
    ObservabilityService service;

    ObservabilityControllerImpl controller;

    @BeforeEach
    void setUp() {
        controller = new ObservabilityControllerImpl(service);
    }

    @Test
    @DisplayName("constructor rejects null service")
    void rejectsNullService() {
        assertThatThrownBy(() -> new ObservabilityControllerImpl(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("getOverview delegates with tenant id")
    void getOverview() {
        OverviewDto expected = new OverviewDto(
                42L, 0.95, 120.0, 300.0, 0.05, 3, 100L, List.of(), List.of(1.0, 2.0));
        when(service.getOverview("tenant-1")).thenReturn(expected);

        assertThat(controller.getOverview("tenant-1")).isSameAs(expected);
        verify(service).getOverview("tenant-1");
    }

    @Test
    @DisplayName("listTraces delegates with the filter")
    void listTraces() {
        FilterDto filter = FilterDto.empty();
        PageDto<TraceSummaryDto> page = new PageDto<>(List.of(), 0, 20, 0L);
        when(service.listTraces(filter)).thenReturn(page);

        assertThat(controller.listTraces(filter)).isSameAs(page);
    }

    @Test
    @DisplayName("getTrace delegates and propagates Optional.empty")
    void getTraceEmpty() {
        when(service.getTrace("trace-x")).thenReturn(Optional.empty());
        assertThat(controller.getTrace("trace-x")).isEmpty();
    }

    @Test
    @DisplayName("getTrace delegates and returns the detail when present")
    void getTracePresent() {
        TraceDetailDto detail = new TraceDetailDto(
                "trace-1", "tenant-1", null, "flow-1", "exec-1",
                Instant.now(), 100L, "OK", null, Map.of(), List.of());
        when(service.getTrace("trace-1")).thenReturn(Optional.of(detail));
        assertThat(controller.getTrace("trace-1")).contains(detail);
    }

    @Test
    @DisplayName("getMetricsSnapshot delegates with tenant id")
    void getMetricsSnapshot() {
        MetricsSnapshotDto snapshot = new MetricsSnapshotDto(
                Instant.now(), Map.of(), Map.of(), Map.of());
        when(service.getMetricsSnapshot("tenant-1")).thenReturn(snapshot);
        assertThat(controller.getMetricsSnapshot("tenant-1")).isSameAs(snapshot);
    }

    @Test
    @DisplayName("getMetricSeries forwards all four arguments")
    void getMetricSeries() {
        List<MetricSeriesDto> series = List.of();
        when(service.getMetricSeries("tenant-1", "latency", 60, 30)).thenReturn(series);
        assertThat(controller.getMetricSeries("tenant-1", "latency", 60, 30)).isSameAs(series);
        verify(service).getMetricSeries("tenant-1", "latency", 60, 30);
    }

    @Test
    @DisplayName("listAudit delegates with the filter")
    void listAudit() {
        FilterDto filter = FilterDto.empty();
        PageDto<AuditEntryDto> page = new PageDto<>(List.of(), 0, 50, 0L);
        when(service.listAudit(filter)).thenReturn(page);
        assertThat(controller.listAudit(filter)).isSameAs(page);
    }

    @Test
    @DisplayName("exportAuditCsv delegates with the filter")
    void exportAuditCsv() {
        FilterDto filter = FilterDto.empty();
        when(service.exportAuditCsv(filter)).thenReturn("id,tenant\n");
        assertThat(controller.exportAuditCsv(filter)).isEqualTo("id,tenant\n");
    }
}
