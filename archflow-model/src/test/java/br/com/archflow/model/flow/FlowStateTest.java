package br.com.archflow.model.flow;

import br.com.archflow.model.error.ExecutionError;
import br.com.archflow.model.error.ExecutionErrorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("FlowState")
class FlowStateTest {

    @Test
    @DisplayName("should create with builder")
    void shouldCreateWithBuilder() {
        var state = FlowState.builder()
                .flowId("flow-1")
                .status(FlowStatus.INITIALIZED)
                .currentStepId("step-1")
                .variables(new HashMap<>())
                .build();

        assertThat(state.getFlowId()).isEqualTo("flow-1");
        assertThat(state.getStatus()).isEqualTo(FlowStatus.INITIALIZED);
        assertThat(state.getCurrentStepId()).isEqualTo("step-1");
        assertThat(state.getVariables()).isEmpty();
    }

    @Test
    @DisplayName("should create with no-args constructor")
    void shouldCreateWithNoArgs() {
        var state = new FlowState();

        assertThat(state.getFlowId()).isNull();
        assertThat(state.getStatus()).isNull();
    }

    @Test
    @DisplayName("should set and get all fields via Lombok")
    void shouldSetAndGetFields() {
        var state = new FlowState();
        state.setFlowId("flow-2");
        state.setStatus(FlowStatus.RUNNING);
        state.setCurrentStepId("step-2");

        var variables = new HashMap<String, Object>();
        variables.put("key", "value");
        state.setVariables(variables);

        var error = ExecutionError.of("ERR-1", "Test error", ExecutionErrorType.EXECUTION);
        state.setError(error);

        assertThat(state.getFlowId()).isEqualTo("flow-2");
        assertThat(state.getStatus()).isEqualTo(FlowStatus.RUNNING);
        assertThat(state.getCurrentStepId()).isEqualTo("step-2");
        assertThat(state.getVariables()).containsEntry("key", "value");
        assertThat(state.getError()).isNotNull();
        assertThat(state.getError().code()).isEqualTo("ERR-1");
    }

    @Test
    @DisplayName("should support Lombok equals and hashCode")
    void shouldSupportEquality() {
        var s1 = FlowState.builder().flowId("flow-1").status(FlowStatus.RUNNING).build();
        var s2 = FlowState.builder().flowId("flow-1").status(FlowStatus.RUNNING).build();

        assertThat(s1).isEqualTo(s2);
        assertThat(s1.hashCode()).isEqualTo(s2.hashCode());
    }

    @Test
    @DisplayName("should have toString with all fields")
    void shouldHaveToString() {
        var state = FlowState.builder().flowId("flow-1").status(FlowStatus.COMPLETED).build();

        assertThat(state.toString()).contains("flow-1", "COMPLETED");
    }
}
