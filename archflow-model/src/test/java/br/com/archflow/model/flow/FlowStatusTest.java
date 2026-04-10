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
                FlowStatus.STOPPED,
                FlowStatus.AWAITING_APPROVAL
        );
    }

    @ParameterizedTest
    @EnumSource(value = FlowStatus.class, names = {"COMPLETED", "FAILED", "STOPPED"})
    @DisplayName("isFinal should return true for terminal states")
    void isFinalShouldReturnTrueForTerminalStates(FlowStatus status) {
        assertThat(status.isFinal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = FlowStatus.class, names = {"INITIALIZED", "RUNNING", "PAUSED", "AWAITING_APPROVAL"})
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
    @EnumSource(value = FlowStatus.class, names = {"COMPLETED", "FAILED", "STOPPED", "AWAITING_APPROVAL"})
    @DisplayName("canContinue should return false for non-active states")
    void canContinueShouldReturnFalseForNonActiveStates(FlowStatus status) {
        assertThat(status.canContinue()).isFalse();
    }

    @Test
    @DisplayName("AWAITING_APPROVAL is neither final nor continuable")
    void awaitingApprovalIsNeitherFinalNorContinuable() {
        assertThat(FlowStatus.AWAITING_APPROVAL.isFinal()).isFalse();
        assertThat(FlowStatus.AWAITING_APPROVAL.canContinue()).isFalse();
        assertThat(FlowStatus.AWAITING_APPROVAL.isWaitingForHuman()).isTrue();
    }

    @Test
    @DisplayName("isWaitingForHuman should return false for all states except AWAITING_APPROVAL")
    void isWaitingForHumanShouldReturnFalseForOtherStates() {
        for (FlowStatus status : FlowStatus.values()) {
            if (status == FlowStatus.AWAITING_APPROVAL) {
                assertThat(status.isWaitingForHuman()).isTrue();
            } else {
                assertThat(status.isWaitingForHuman()).isFalse();
            }
        }
    }
}
