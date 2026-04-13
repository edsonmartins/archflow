package br.com.archflow.api.admin.observability.impl;

import br.com.archflow.api.admin.observability.ObservabilityDtos.SpanDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.TraceDetailDto;
import br.com.archflow.engine.core.TraceRecorder;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.FlowResult;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Adapter that bridges {@link TraceRecorder} (archflow-core) to
 * {@link InMemoryTraceStore} (archflow-api). Wire this into the
 * {@code DefaultFlowEngine} constructor to get automatic trace
 * production on every flow execution.
 *
 * <p>Each flow start/end pair produces a single {@link TraceDetailDto}
 * with one root span. Step-level spans require deeper integration with
 * the {@code FlowExecutor} — this recorder captures the flow-level
 * envelope which is sufficient for the observability overview, traces
 * list, and timeline.
 */
public class TraceStoreRecorder implements TraceRecorder {

    private final InMemoryTraceStore store;

    public TraceStoreRecorder(InMemoryTraceStore store) {
        this.store = store;
    }

    @Override
    public void onFlowStart(String flowId, String tenantId, String personaId) {
        // No-op — we record on completion when we have the full result.
        // A future improvement could record a RUNNING trace here and update
        // it on completion, enabling live trace visibility.
    }

    @Override
    public void onFlowEnd(String flowId, String tenantId, FlowResult result, long durationMs) {
        String traceId = "trace-" + UUID.randomUUID().toString().substring(0, 12);
        String status = result.getStatus() == ExecutionStatus.COMPLETED ? "OK" : "ERROR";
        String error = result.getErrors().isEmpty() ? null
                : result.getErrors().get(0).message();

        Map<String, String> attrs = new HashMap<>();
        attrs.put("flow.id", flowId);
        if (tenantId != null) attrs.put("tenant.id", tenantId);

        Instant startedAt = Instant.now().minusMillis(durationMs);

        SpanDto rootSpan = InMemoryTraceStore.span(
                "span-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                "flow.execute",
                "INTERNAL",
                startedAt,
                durationMs,
                status,
                attrs,
                List.of());

        TraceDetailDto trace = InMemoryTraceStore.trace(
                traceId,
                tenantId,
                null,
                flowId,
                flowId,
                startedAt,
                durationMs,
                status,
                error,
                attrs,
                List.of(rootSpan));

        store.record(trace);
    }
}
