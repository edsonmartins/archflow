package br.com.archflow.api.flow;

import br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
        executionId = String.valueOf(store.createExecution("wf-1", "Test Flow").get("id"));
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
        return (List<Map<String, Object>>) store.getExecution(executionId).get("steps");
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

        assertThat(store.getExecution(executionId).get("duration")).isEqualTo(987L);
    }

    @Test
    @DisplayName("events for unknown executions are ignored")
    void ignoresUnknownExecution() {
        Flow ghost = mock(Flow.class);
        when(ghost.getId()).thenReturn("no-such-exec");

        listener.onStepStarted(ghost, step("s", StepType.AGENT), null, 0, 1);
        listener.onFlowCompleted(ghost, null, null, 1L);

        assertThat(store.getExecution("no-such-exec")).isNull();
    }
}
