package br.com.archflow.model.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

@DisplayName("FlowStatus")
class FlowStatusTest {

    @Test
    @DisplayName("should have all expected values")
    void shouldHaveAllValues() {
        assertThat(FlowStatus.values()).containsExactly(
                FlowStatus.INITIALIZED,
                FlowStatus.RUNNING,
                FlowStatus.PAUSED,
                FlowStatus.COMPLETED,
                FlowStatus.FAILED,
                FlowStatus.STOPPED
        );
    }

    @ParameterizedTest
    @EnumSource(value = FlowStatus.class, names = {"COMPLETED", "FAILED", "STOPPED"})
    @DisplayName("isFinal should return true for terminal states")
    void isFinalShouldReturnTrueForTerminalStates(FlowStatus status) {
        assertThat(status.isFinal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = FlowStatus.class, names = {"INITIALIZED", "RUNNING", "PAUSED"})
    @DisplayName("isFinal should return false for non-terminal states")
    void isFinalShouldReturnFalseForNonTerminal(FlowStatus status) {
        assertThat(status.isFinal()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = FlowStatus.class, names = {"INITIALIZED", "RUNNING", "PAUSED"})
    @DisplayName("canContinue should return true for active states")
    void canContinueShouldReturnTrueForActiveStates(FlowStatus status) {
        assertThat(status.canContinue()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = FlowStatus.class, names = {"COMPLETED", "FAILED", "STOPPED"})
    @DisplayName("canContinue should return false for terminal states")
    void canContinueShouldReturnFalseForTerminalStates(FlowStatus status) {
        assertThat(status.canContinue()).isFalse();
    }

    @Test
    @DisplayName("isFinal and canContinue should be mutually exclusive")
    void isFinalAndCanContinueShouldBeMutuallyExclusive() {
        for (FlowStatus status : FlowStatus.values()) {
            assertThat(status.isFinal()).isNotEqualTo(status.canContinue());
        }
    }
}
