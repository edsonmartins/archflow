package br.com.archflow.api.flow;

import br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore;
import br.com.archflow.model.tenant.DefaultTenant;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StepRecordingListenerTest {

    private InMemoryWorkflowRuntimeStore store;
    private StepRecordingListener listener;
    private String executionId;
    private Flow flow;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowRuntimeStore();
        listener = new StepRecordingListener(store);
        executionId = String.valueOf(store.createExecution(DefaultTenant.ID, "wf-1", "Test Flow").get("id"));
        flow = mock(Flow.class);
        // In both execution paths (/execute and AG-UI) the flow id is the execution id.
        when(flow.getId()).thenReturn(executionId);
    }

    private FlowStep step(String id, StepType type) {
        FlowStep step = mock(FlowStep.class);
        when(step.getId()).thenReturn(id);
        when(step.getType()).thenReturn(type);
        return step;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> steps() {
        return (List<Map<String, Object>>) store.getExecution(DefaultTenant.ID, executionId).get("steps");
    }

    @Test
    @DisplayName("step lifecycle is recorded into the execution's steps list")
    void recordsStepLifecycle() {
        FlowStep stepA = step("step-a", StepType.AGENT);

        listener.onStepStarted(flow, stepA, null, 0, 2);
        Map<String, Object> running = steps().get(0);
        assertThat(running.get("stepId")).isEqualTo("step-a");
        assertThat(running.get("status")).isEqualTo("RUNNING");
        assertThat(running.get("stepIndex")).isEqualTo(0);
        assertThat(running.get("stepCount")).isEqualTo(2);
        assertThat(running.get("startedAt")).isNotNull();

        listener.onStepCompleted(flow, stepA, null, 123L);
        Map<String, Object> done = steps().get(0);
        assertThat(done.get("status")).isEqualTo("COMPLETED");
        assertThat(done.get("durationMs")).isEqualTo(123L);
        assertThat(done.get("finishedAt")).isNotNull();
        assertThat(steps()).hasSize(1);
    }

    @Test
    @DisplayName("failed step records status FAILED with the error message")
    void recordsFailure() {
        FlowStep stepB = step("step-b", StepType.TOOL);

        listener.onStepStarted(flow, stepB, null, 1, 2);
        listener.onStepFailed(flow, stepB, null, new RuntimeException("boom"), 45L);

        Map<String, Object> failed = steps().get(0);
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("error")).isEqualTo("boom");
        assertThat(failed.get("durationMs")).isEqualTo(45L);
    }

    @Test
    @DisplayName("flow completion records the execution duration")
    void recordsFlowDuration() {
        listener.onFlowCompleted(flow, null, null, 987L);

        assertThat(store.getExecution(DefaultTenant.ID, executionId).get("duration")).isEqualTo(987L);
    }

    @Test
    @DisplayName("events for unknown executions are ignored")
    void ignoresUnknownExecution() {
        Flow ghost = mock(Flow.class);
        when(ghost.getId()).thenReturn("no-such-exec");

        listener.onStepStarted(ghost, step("s", StepType.AGENT), null, 0, 1);
        listener.onFlowCompleted(ghost, null, null, 1L);

        assertThat(store.getExecution(DefaultTenant.ID, "no-such-exec")).isNull();
    }

    // ------------------------------------------------------------------ saída do passo

    private ExecutionContext contextWithOutput(String stepId, Object output) {
        ExecutionContext context = mock(ExecutionContext.class);
        when(context.get(anyString())).thenReturn(Optional.empty());
        when(context.get("step." + stepId + ".output")).thenReturn(Optional.ofNullable(output));
        return context;
    }

    @Test
    @DisplayName("passo concluído grava a saída do contexto como JSON")
    void recordsStepOutputAsJson() {
        FlowStep stepA = step("step-a", StepType.AGENT);
        ExecutionContext context = contextWithOutput("step-a", Map.of("answer", 42));

        listener.onStepStarted(flow, stepA, context, 0, 1);
        listener.onStepCompleted(flow, stepA, context, 10L);

        Map<String, Object> done = steps().get(0);
        assertThat(done.get("status")).isEqualTo("COMPLETED");
        assertThat(done.get("output")).isEqualTo("{\"answer\":42}");
    }

    @Test
    @DisplayName("saída textual é gravada como está, sem aspas de JSON")
    void recordsStringOutputVerbatim() {
        FlowStep stepA = step("step-a", StepType.AGENT);
        listener.onStepCompleted(flow, stepA, contextWithOutput("step-a", "olá"), 1L);

        assertThat(steps().get(0).get("output")).isEqualTo("olá");
    }

    @Test
    @DisplayName("saída grande é truncada com sufixo")
    void truncatesLargeOutput() {
        FlowStep stepA = step("step-a", StepType.AGENT);
        String big = "x".repeat(StepRecordingListener.MAX_OUTPUT_CHARS + 500);

        listener.onStepCompleted(flow, stepA, contextWithOutput("step-a", big), 1L);

        String output = (String) steps().get(0).get("output");
        assertThat(output).hasSize(StepRecordingListener.MAX_OUTPUT_CHARS
                + StepRecordingListener.TRUNCATED_SUFFIX.length());
        assertThat(output).endsWith(StepRecordingListener.TRUNCATED_SUFFIX);
    }

    /** Bean cujo getter explode — o Jackson não consegue serializar. */
    static final class Exploding {
        public String getValue() {
            throw new IllegalStateException("no");
        }

        @Override
        public String toString() {
            return "Exploding{}";
        }
    }

    @Test
    @DisplayName("saída não serializável cai para toString e o passo segue COMPLETED")
    void unserializableOutputFallsBackToString() {
        FlowStep stepA = step("step-a", StepType.AGENT);

        listener.onStepCompleted(flow, stepA, contextWithOutput("step-a", new Exploding()), 1L);

        Map<String, Object> done = steps().get(0);
        assertThat(done.get("status")).isEqualTo("COMPLETED");
        assertThat(done.get("output")).isEqualTo("Exploding{}");
    }

    @Test
    @DisplayName("sem saída (ou contexto que falha) não grava o campo e não quebra o registro")
    void missingOrBrokenOutputIsOmitted() {
        FlowStep stepA = step("step-a", StepType.AGENT);
        FlowStep stepB = step("step-b", StepType.AGENT);
        ExecutionContext broken = mock(ExecutionContext.class);
        when(broken.get(anyString())).thenThrow(new IllegalStateException("ctx"));

        listener.onStepCompleted(flow, stepA, contextWithOutput("step-a", null), 1L);
        assertThatCode(() -> listener.onStepCompleted(flow, stepB, broken, 2L))
                .doesNotThrowAnyException();
        listener.onStepCompleted(flow, step("step-c", StepType.AGENT), null, 3L);

        assertThat(steps()).hasSize(3);
        assertThat(steps()).allSatisfy(s -> {
            assertThat(s.get("status")).isEqualTo("COMPLETED");
            assertThat(s).doesNotContainKey("output");
        });
    }

    @Test
    @DisplayName("registro com saída continua serializável pelo mapper do JdbcWorkflowRuntimeStore")
    void recordWithOutputStaysJsonSerializable() throws Exception {
        // O store JDBC regrava o registro inteiro com um ObjectMapper padrão a
        // cada recordStep — a saída precisa chegar lá já como texto.
        FlowStep stepA = step("step-a", StepType.AGENT);
        listener.onStepCompleted(flow, stepA, contextWithOutput("step-a", new Exploding()), 1L);

        ObjectMapper jdbcLikeMapper = new ObjectMapper();
        String json = jdbcLikeMapper.writeValueAsString(store.getExecution(DefaultTenant.ID, executionId));
        @SuppressWarnings("unchecked")
        Map<String, Object> back = jdbcLikeMapper.readValue(json, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> backSteps = (List<Map<String, Object>>) back.get("steps");
        assertThat(backSteps.get(0).get("output")).isEqualTo("Exploding{}");
    }

    /** Objeto que se serializa como uma lista enorme — conta quantos itens o Jackson chegou a pedir. */
    static final class Huge {
        final int[] served = {0};

        public java.util.Iterator<String> getItems() {
            return new java.util.Iterator<>() {
                @Override
                public boolean hasNext() {
                    return served[0] < 10_000_000;
                }

                @Override
                public String next() {
                    served[0]++;
                    return "item-" + served[0];
                }
            };
        }
    }

    @Test
    @DisplayName("saída gigante é serializada só até o limite, não inteira e depois cortada")
    void hugeOutputIsSerializedOnlyUpToTheLimit() {
        Huge huge = new Huge();

        String output = StepRecordingListener.serialize(huge);

        assertThat(output).startsWith("{\"items\":[\"item-1\"");
        assertThat(output).endsWith(StepRecordingListener.TRUNCATED_SUFFIX);
        assertThat(output).hasSize(StepRecordingListener.MAX_OUTPUT_CHARS
                + StepRecordingListener.TRUNCATED_SUFFIX.length());
        // Cada item ocupa ~12 caracteres: com o limite de 4 KB o Jackson para em poucos milhares
        // (mais o buffer interno dele), longe dos 10 milhões.
        assertThat(huge.served[0]).isLessThan(10_000);
    }

    /** Nó de um grafo cíclico: a -> b -> a. */
    static final class Node {
        public String name;
        public Node next;

        Node(String name) {
            this.name = name;
        }
    }

    @Test
    @DisplayName("saída cíclica é truncada pelo limite e o passo segue COMPLETED")
    void cyclicOutputIsTruncatedWithoutBreakingTheRecord() {
        Node a = new Node("a");
        Node b = new Node("b");
        a.next = b;
        b.next = a;
        FlowStep stepA = step("step-a", StepType.AGENT);

        listener.onStepCompleted(flow, stepA, contextWithOutput("step-a", a), 1L);

        Map<String, Object> done = steps().get(0);
        assertThat(done.get("status")).isEqualTo("COMPLETED");
        assertThat((String) done.get("output"))
                .startsWith("{\"name\":\"a\",\"next\":{\"name\":\"b\"")
                .endsWith(StepRecordingListener.TRUNCATED_SUFFIX);
    }

    @Test
    @DisplayName("saída que cabe no limite não ganha sufixo")
    void outputAtTheLimitIsNotMarkedTruncated() {
        String exact = "y".repeat(StepRecordingListener.MAX_OUTPUT_CHARS);

        assertThat(StepRecordingListener.serialize(exact)).isEqualTo(exact);
        assertThat(StepRecordingListener.serialize(List.of("z"))).isEqualTo("[\"z\"]");
    }
}
