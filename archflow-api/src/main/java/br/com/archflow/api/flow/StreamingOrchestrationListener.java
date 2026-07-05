package br.com.archflow.api.flow;

import br.com.archflow.agent.orchestration.OrchestrationListener;
import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;
import br.com.archflow.agent.streaming.EventStreamRegistry;

import java.util.List;
import java.util.Map;

/**
 * Bridges a {@link OrchestrationListener} (dynamic-workflow progress inside an
 * {@code OrchestrateStep}) to the per-tenant SSE feed (design-0005 step 3): each
 * plan/verify/round/converge transition is broadcast to the run's {@code
 * executionId} channel so the dynamic subtask tree streams live.
 */
public final class StreamingOrchestrationListener implements OrchestrationListener {

    private final EventStreamRegistry registry;
    private final String executionId;

    public StreamingOrchestrationListener(EventStreamRegistry registry, String executionId) {
        this.registry = registry;
        this.executionId = executionId;
    }

    private void emit(ArchflowEventType type, Map<String, Object> data) {
        if (registry == null || executionId == null) {
            return;
        }
        registry.broadcast(executionId, ArchflowEvent.builder()
                .domain(ArchflowDomain.FLOW)
                .type(type)
                .executionId(executionId)
                .data(data)
                .build());
    }

    @Override
    public void onPlanned(int round, List<?> items) {
        emit(ArchflowEventType.PROGRESS, Map.of("phase", "planned", "round", round, "items", items));
    }

    @Override
    public void onVerified(int round, Object finding, boolean confirmed) {
        emit(ArchflowEventType.VERIFICATION,
                Map.of("round", round, "finding", String.valueOf(finding), "confirmed", confirmed));
    }

    @Override
    public void onRoundCompleted(int round, boolean producedNew, int confirmedSoFar) {
        emit(ArchflowEventType.PROGRESS,
                Map.of("phase", "round", "round", round, "producedNew", producedNew, "confirmed", confirmedSoFar));
    }

    @Override
    public void onConverged(int rounds, int confirmedTotal) {
        emit(ArchflowEventType.END, Map.of("phase", "converged", "rounds", rounds, "confirmed", confirmedTotal));
    }
}
