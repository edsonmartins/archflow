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
 *
 * <p>Também é entregue no canal {@code tenantId:executionId}, a mesma regra dos eventos de fluxo e de
 * passo ({@code RegistryFlowLifecycleListener}, RCHFLW-50): é o único canal de execução que o stream
 * SSE deixa assinar, e só para o próprio tenant. O tenant é o do contexto da execução.
 */
public final class StreamingOrchestrationListener implements OrchestrationListener {

    private final EventStreamRegistry registry;
    private final String tenantId;
    private final String executionId;

    /**
     * Sem tenant: só o canal nu {@code executionId}, como antes do RCHFLW-50. Mantido para quem já
     * constrói o listener assim (testes e a frente de orquestração); o nó {@code orchestrate} usa o
     * construtor com o tenant da execução.
     */
    public StreamingOrchestrationListener(EventStreamRegistry registry, String executionId) {
        this(registry, null, executionId);
    }

    public StreamingOrchestrationListener(EventStreamRegistry registry, String tenantId, String executionId) {
        this.registry = registry;
        this.tenantId = tenantId;
        this.executionId = executionId;
    }

    private void emit(ArchflowEventType type, Map<String, Object> data) {
        if (registry == null || executionId == null) {
            return;
        }
        ArchflowEvent event = ArchflowEvent.builder()
                .domain(ArchflowDomain.FLOW)
                .type(type)
                .tenantId(tenantId)
                .executionId(executionId)
                .data(data)
                .build();
        registry.broadcast(executionId, event);
        if (tenantId != null) {
            // deliver, não broadcast: os ouvintes globais já receberam o evento acima.
            registry.deliver(EventStreamRegistry.tenantChannel(tenantId, executionId), event);
        }
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
