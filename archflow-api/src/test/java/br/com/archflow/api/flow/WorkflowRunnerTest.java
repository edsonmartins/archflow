package br.com.archflow.api.flow;

import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowRunnerTest {

    private final WorkflowRunner runner = new WorkflowRunner();

    private ExecutionContext newContext() {
        return new DefaultExecutionContext(null, "test", "exec-1",
                MessageWindowChatMemory.builder().maxMessages(10).build());
    }

    private FlowStep step(String id, String componentId, ComponentCatalog catalog) {
        return new ComponentStep(id, StepType.TOOL, componentId, "execute", List.of(), catalog);
    }

    @Test
    void runsStepsInOrderThreadingOutputBetweenThem() throws Exception {
        ComponentCatalog catalog = mock(ComponentCatalog.class);
        AIComponent c1 = mock(AIComponent.class);
        AIComponent c2 = mock(AIComponent.class);
        when(catalog.getComponent("c1")).thenReturn(Optional.of(c1));
        when(catalog.getComponent("c2")).thenReturn(Optional.of(c2));
        when(c1.execute(any(), any(), any())).thenReturn("a");
        when(c2.execute(any(), any(), any())).thenReturn("b");

        var flow = new SimpleFlow("wf", meta(), List.of(step("s1", "c1", catalog), step("s2", "c2", catalog)));
        WorkflowRunner.RunResult result = runner.run(flow, newContext());

        assertThat(result.success()).isTrue();
        assertThat(result.steps()).extracting(WorkflowRunner.StepOutcome::status).containsExactly("COMPLETED", "COMPLETED");
        assertThat(result.steps()).extracting(WorkflowRunner.StepOutcome::output).containsExactly("a", "b");
        // s2 received s1's output ("a") as input — chaining works.
        verify(c2).execute(eq("execute"), eq("a"), any());
    }

    @Test
    void stopsOnFirstFailedStep() {
        ComponentCatalog catalog = mock(ComponentCatalog.class);
        when(catalog.getComponent("missing")).thenReturn(Optional.empty());

        var flow = new SimpleFlow("wf", meta(), List.of(step("s1", "missing", catalog), step("s2", "c2", catalog)));
        WorkflowRunner.RunResult result = runner.run(flow, newContext());

        assertThat(result.success()).isFalse();
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().get(0).status()).isEqualTo("FAILED");
    }

    private static FlowMetadata meta() {
        return new FlowMetadata("W", "", "1.0.0", null, null, List.of());
    }
}
