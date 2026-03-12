package br.com.archflow.model.flow;

import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.metrics.StepMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("StateUpdate")
class StateUpdateTest {

    private FlowState state;

    @BeforeEach
    void setUp() {
        state = FlowState.builder()
                .flowId("flow-1")
                .status(FlowStatus.RUNNING)
                .currentStepId("step-0")
                .variables(new HashMap<>())
                .metrics(FlowMetrics.builder()
                        .stepMetrics(new HashMap<>())
                        .completedSteps(0)
                        .totalSteps(3)
                        .build())
                .build();
    }

    @Test
    @DisplayName("should update flow status")
    void shouldUpdateFlowStatus() {
        var update = StateUpdate.builder()
                .status(FlowStatus.COMPLETED)
                .build();

        update.apply(state);

        assertThat(state.getStatus()).isEqualTo(FlowStatus.COMPLETED);
    }

    @Test
    @DisplayName("should update current step id")
    void shouldUpdateCurrentStepId() {
        var update = StateUpdate.builder()
                .stepId("step-2")
                .build();

        update.apply(state);

        assertThat(state.getCurrentStepId()).isEqualTo("step-2");
    }

    @Test
    @DisplayName("should update variables")
    void shouldUpdateVariables() {
        var update = StateUpdate.builder()
                .variable("result", "success")
                .variable("count", 42)
                .build();

        update.apply(state);

        assertThat(state.getVariables()).containsEntry("result", "success");
        assertThat(state.getVariables()).containsEntry("count", 42);
    }

    @Test
    @DisplayName("should update variables with map")
    void shouldUpdateVariablesWithMap() {
        var update = StateUpdate.builder()
                .variables(Map.of("a", 1, "b", 2))
                .build();

        update.apply(state);

        assertThat(state.getVariables()).containsEntry("a", 1);
        assertThat(state.getVariables()).containsEntry("b", 2);
    }

    @Test
    @DisplayName("should update metrics with step result")
    void shouldUpdateMetricsWithStepResult() {
        var stepMetrics = new StepMetrics(100L, 50, 0, Map.of());
        var stepResult = Mockito.mock(StepResult.class);
        when(stepResult.getMetrics()).thenReturn(stepMetrics);
        when(stepResult.getStatus()).thenReturn(StepStatus.COMPLETED);

        var update = StateUpdate.builder()
                .stepId("step-1")
                .stepResult(stepResult)
                .build();

        update.apply(state);

        assertThat(state.getMetrics().getStepMetrics()).containsKey("step-1");
        assertThat(state.getMetrics().getCompletedSteps()).isEqualTo(1);
    }

    @Test
    @DisplayName("should create metrics if null when step result is applied")
    void shouldCreateMetricsIfNull() {
        state.setMetrics(null);

        var stepMetrics = new StepMetrics(100L, 50, 0, Map.of());
        var stepResult = Mockito.mock(StepResult.class);
        when(stepResult.getMetrics()).thenReturn(stepMetrics);
        when(stepResult.getStatus()).thenReturn(StepStatus.FAILED);

        var update = StateUpdate.builder()
                .stepId("step-1")
                .stepResult(stepResult)
                .build();

        update.apply(state);

        assertThat(state.getMetrics()).isNotNull();
        assertThat(state.getMetrics().getCompletedSteps()).isZero();
    }

    @Test
    @DisplayName("should not change fields when builder values are null")
    void shouldNotChangeFieldsWhenNull() {
        var update = StateUpdate.builder().build();

        update.apply(state);

        assertThat(state.getStatus()).isEqualTo(FlowStatus.RUNNING);
        assertThat(state.getCurrentStepId()).isEqualTo("step-0");
    }
}
