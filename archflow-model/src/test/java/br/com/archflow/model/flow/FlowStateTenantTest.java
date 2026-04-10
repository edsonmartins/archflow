package br.com.archflow.model.flow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.*;

@DisplayName("FlowState — TenantId Field")
class FlowStateTenantTest {

    @Test
    @DisplayName("should store and retrieve tenantId via builder")
    void shouldStoreTenantId() {
        var state = FlowState.builder()
                .tenantId("tenant-1")
                .flowId("flow-1")
                .status(FlowStatus.RUNNING)
                .build();

        assertThat(state.getTenantId()).isEqualTo("tenant-1");
    }

    @Test
    @DisplayName("tenantId should be null when not set (backward compat)")
    void shouldBeNullWhenNotSet() {
        var state = FlowState.builder()
                .flowId("flow-1")
                .status(FlowStatus.RUNNING)
                .build();

        assertThat(state.getTenantId()).isNull();
    }

    @Test
    @DisplayName("tenantId should be preserved in builder copy")
    void shouldPreserveInBuilderCopy() {
        var original = FlowState.builder()
                .tenantId("t1")
                .flowId("f1")
                .status(FlowStatus.RUNNING)
                .variables(new HashMap<>())
                .build();

        var copy = FlowState.builder()
                .tenantId(original.getTenantId())
                .flowId(original.getFlowId())
                .status(FlowStatus.COMPLETED)
                .variables(original.getVariables())
                .build();

        assertThat(copy.getTenantId()).isEqualTo("t1");
        assertThat(copy.getStatus()).isEqualTo(FlowStatus.COMPLETED);
    }

    @Test
    @DisplayName("tenantId should be included in equals/hashCode")
    void shouldBeInEquality() {
        var state1 = FlowState.builder().tenantId("t1").flowId("f1").status(FlowStatus.RUNNING).build();
        var state2 = FlowState.builder().tenantId("t2").flowId("f1").status(FlowStatus.RUNNING).build();

        assertThat(state1).isNotEqualTo(state2);
    }
}
