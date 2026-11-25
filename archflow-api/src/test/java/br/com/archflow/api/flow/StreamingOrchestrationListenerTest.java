package br.com.archflow.api.flow;

import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StreamingOrchestrationListenerTest {

    /**
     * RCHFLW-61: a falha de uma subtarefa não aparecia no stream do nó orchestrate. Vai como PROGRESS
     * {@code phase=failed} — não ERROR, que quem lê o feed tomaria como o fim da execução.
     */
    @Test
    void streamsAWorkerFailureWithTheSubtaskAndTheReason() {
        EventStreamRegistry registry = mock(EventStreamRegistry.class);
        var listener = new StreamingOrchestrationListener(registry, "exec-1");

        listener.onWorkerFailed(2, "compare carriers", "research-agent: Unsupported task type");

        ArgumentCaptor<ArchflowEvent> event = ArgumentCaptor.forClass(ArchflowEvent.class);
        verify(registry).broadcast(eq("exec-1"), event.capture());
        assertThat(event.getValue().getType()).isEqualTo(ArchflowEventType.PROGRESS);
        assertThat(event.getValue().getData())
                .containsEntry("phase", "failed")
                .containsEntry("round", 2)
                .containsEntry("subtask", "compare carriers")
                .containsEntry("error", "research-agent: Unsupported task type");
    }

    private static java.util.Map<String, Object> dataOf(EventStreamRegistry registry) {
        ArgumentCaptor<ArchflowEvent> event = ArgumentCaptor.forClass(ArchflowEvent.class);
        verify(registry).broadcast(eq("exec-1"), event.capture());
        assertThat(event.getValue().getType()).isEqualTo(ArchflowEventType.PROGRESS);
        return event.getValue().getData();
    }

    /** RCHFLW-64: o SSE do nó orchestrate diz qual componente atendeu a subtarefa. */
    @Test
    void streamsTheDispatchWithTheChosenComponent() {
        EventStreamRegistry registry = mock(EventStreamRegistry.class);

        new StreamingOrchestrationListener(registry, "exec-1")
                .onDispatched(1, "compare carriers", "research-agent", 0.42, true);

        assertThat(dataOf(registry))
                .containsEntry("phase", "dispatched")
                .containsEntry("round", 1)
                .containsEntry("subtask", "compare carriers")
                .containsEntry("componentId", "research-agent")
                .containsEntry("score", 0.42)
                .containsEntry("success", true);
    }

    /** Nenhum agente casou: o evento sai assim mesmo, com componente nulo — {@code Map.of} lançaria NPE. */
    @Test
    void streamsTheDispatchEvenWhenNoAgentMatched() {
        EventStreamRegistry registry = mock(EventStreamRegistry.class);

        new StreamingOrchestrationListener(registry, "exec-1").onDispatched(1, "zzz", null, null, false);

        assertThat(dataOf(registry))
                .containsEntry("phase", "dispatched")
                .containsEntry("componentId", null)
                .containsEntry("score", null)
                .containsEntry("success", false);
    }

    /** RCHFLW-62: o esgotamento do orçamento e o uso não informado também chegam ao feed. */
    @Test
    void streamsBudgetExhaustionAndUnknownUsage() {
        EventStreamRegistry exhausted = mock(EventStreamRegistry.class);
        new StreamingOrchestrationListener(exhausted, "exec-1").onBudgetExhausted(2, 160, 150L);
        assertThat(dataOf(exhausted))
                .containsEntry("phase", "budget-exhausted")
                .containsEntry("round", 2)
                .containsEntry("spentTokens", 160L)
                .containsEntry("maxTokens", 150L);

        EventStreamRegistry unknown = mock(EventStreamRegistry.class);
        new StreamingOrchestrationListener(unknown, "exec-1").onUsageUnknown(1, null, "planner");
        assertThat(dataOf(unknown))
                .containsEntry("phase", "usage-unknown")
                .containsEntry("subtask", null)
                .containsEntry("source", "planner");
    }

    /** RCHFLW-62: a subtarefa que o orçamento deixou de fora chega ao feed, com o motivo. */
    @Test
    void streamsASubtaskThatWasNotDispatched() {
        EventStreamRegistry registry = mock(EventStreamRegistry.class);

        new StreamingOrchestrationListener(registry, "exec-1").onNotDispatched(1, "c", "budget exhausted");

        assertThat(dataOf(registry))
                .containsEntry("phase", "not-dispatched")
                .containsEntry("round", 1)
                .containsEntry("subtask", "c")
                .containsEntry("reason", "budget exhausted");
    }
}
