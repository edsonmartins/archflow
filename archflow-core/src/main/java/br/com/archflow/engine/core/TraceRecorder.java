package br.com.archflow.engine.core;

import br.com.archflow.model.flow.FlowResult;

/**
 * Optional observer notified by {@link DefaultFlowEngine} at the start and
 * end of every flow execution. Implementations can record traces, emit
 * events or forward to external observability backends.
 *
 * <p>This is deliberately a simple callback interface — it does NOT
 * depend on the admin/observability DTOs so that the core engine module
 * stays decoupled from the API layer. The API layer's
 * {@code InMemoryTraceStore} can implement this interface to populate the
 * trace dashboard automatically.
 *
 * <p>Implementations must be thread-safe — the engine calls these methods
 * from virtual threads running flow executions concurrently.
 */
public interface TraceRecorder {

    /**
     * Called immediately before the flow begins executing.
     *
     * @param flowId    the flow identifier
     * @param tenantId  the tenant (may be null)
     * @param personaId the resolved persona (may be null)
     */
    void onFlowStart(String flowId, String tenantId, String personaId);

    /**
     * Called after the flow completes (success or failure).
     *
     * @param flowId     the flow identifier
     * @param tenantId   the tenant
     * @param result     the flow result (status, output, errors, metrics)
     * @param durationMs wall-clock duration in milliseconds
     */
    void onFlowEnd(String flowId, String tenantId, FlowResult result, long durationMs);
}
