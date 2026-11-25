package br.com.archflow.api.flow;

import br.com.archflow.agent.orchestration.OrchestrationListener;
import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;
import br.com.archflow.agent.streaming.EventStreamRegistry;

import java.util.HashMap;
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

    /**
     * PROGRESS com {@code phase=failed}, e não ERROR: a falha é de uma subtarefa, não do fluxo — quem lê
     * o feed não pode tomá-la como o fim da execução. O motivo vai junto (RCHFLW-61).
     */
    @Override
    public void onWorkerFailed(int round, Object subtask, String error) {
        emit(ArchflowEventType.PROGRESS, Map.of("phase", "failed", "round", round,
                "subtask", String.valueOf(subtask), "error", String.valueOf(error)));
    }

    /**
     * PROGRESS com {@code phase=dispatched}: qual componente atendeu a subtarefa, o score do roteador e se
     * deu certo (RCHFLW-64). {@code componentId} e {@code score} vão {@code null} quando nenhum agente
     * casou — por isso um {@link HashMap}, que aceita nulos, e não {@code Map.of}.
     */
    @Override
    public void onDispatched(int round, Object subtask, String componentId, Double score, boolean success) {
        Map<String, Object> data = new HashMap<>();
        data.put("phase", "dispatched");
        data.put("round", round);
        data.put("subtask", String.valueOf(subtask));
        data.put("componentId", componentId);
        data.put("score", score);
        data.put("success", success);
        emit(ArchflowEventType.PROGRESS, data);
    }

    /** PROGRESS com {@code phase=usage-unknown}: esse gasto não entrou no orçamento (RCHFLW-62). */
    @Override
    public void onUsageUnknown(int round, Object subtask, String source) {
        Map<String, Object> data = new HashMap<>();
        data.put("phase", "usage-unknown");
        data.put("round", round);
        data.put("subtask", subtask == null ? null : String.valueOf(subtask));
        data.put("source", source);
        emit(ArchflowEventType.PROGRESS, data);
    }

    /** PROGRESS com {@code phase=budget-exhausted}: a execução parou de despachar por orçamento (RCHFLW-62). */
    @Override
    public void onBudgetExhausted(int round, long spentTokens, Long maxTokens) {
        Map<String, Object> data = new HashMap<>();
        data.put("phase", "budget-exhausted");
        data.put("round", round);
        data.put("spentTokens", spentTokens);
        data.put("maxTokens", maxTokens);
        emit(ArchflowEventType.PROGRESS, data);
    }

    /** PROGRESS com {@code phase=not-dispatched}: a subtarefa planejada não saiu, e por quê (RCHFLW-62). */
    @Override
    public void onNotDispatched(int round, Object subtask, String reason) {
        emit(ArchflowEventType.PROGRESS, Map.of("phase", "not-dispatched", "round", round,
                "subtask", String.valueOf(subtask), "reason", String.valueOf(reason)));
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
