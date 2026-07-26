package br.com.archflow.api.admin.observability.impl;

import br.com.archflow.api.admin.observability.ObservabilityDtos.SpanDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.TraceDetailDto;
import br.com.archflow.engine.core.TraceRecorder;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.error.ExecutionError;
import br.com.archflow.model.flow.FlowResult;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Adapter that bridges {@link TraceRecorder} (archflow-core) to
 * {@link InMemoryTraceStore} (archflow-api). Registered as a bean and
 * passed to the engine by {@code ArchflowBeanConfiguration}, so every
 * flow execution — successful or failed — produces a trace. It is the
 * store's only writer: without it the observability surface is empty.
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
        // O engine chama este hook também no caminho de exceção, com um resultado
        // sintético; um resultado ausente é tratado como erro, nunca como sucesso.
        ExecutionStatus execStatus = result != null ? result.getStatus() : null;
        String status = execStatus == ExecutionStatus.COMPLETED ? "OK" : "ERROR";
        List<ExecutionError> errors = result != null && result.getErrors() != null
                ? result.getErrors()
                : List.of();
        String error = errors.isEmpty() ? null : errors.get(0).message();

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
