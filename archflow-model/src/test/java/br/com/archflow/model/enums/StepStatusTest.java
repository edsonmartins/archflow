package br.com.archflow.model.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

@DisplayName("StepStatus")
class StepStatusTest {

    @Test
    @DisplayName("should have all expected values")
    void shouldHaveAllValues() {
        assertThat(StepStatus.values()).containsExactly(
                StepStatus.PENDING,
                StepStatus.RUNNING,
                StepStatus.COMPLETED,
                StepStatus.FAILED,
                StepStatus.SKIPPED,
                StepStatus.CANCELLED,
                StepStatus.PAUSED,
                StepStatus.TIMEOUT
        );
    }

    @ParameterizedTest
    @EnumSource(value = StepStatus.class, names = {"COMPLETED", "FAILED", "SKIPPED", "CANCELLED", "TIMEOUT"})
    @DisplayName("isFinal should return true for terminal states")
    void isFinalShouldReturnTrue(StepStatus status) {
        assertThat(status.isFinal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = StepStatus.class, names = {"PENDING", "RUNNING", "PAUSED"})
    @DisplayName("isFinal should return false for non-terminal states")
    void isFinalShouldReturnFalse(StepStatus status) {
        assertThat(status.isFinal()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = StepStatus.class, names = {"FAILED", "TIMEOUT"})
    @DisplayName("isError should return true for error states")
    void isErrorShouldReturnTrue(StepStatus status) {
        assertThat(status.isError()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = StepStatus.class, names = {"PENDING", "RUNNING", "COMPLETED", "SKIPPED", "CANCELLED", "PAUSED"})
    @DisplayName("isError should return false for non-error states")
    void isErrorShouldReturnFalse(StepStatus status) {
        assertThat(status.isError()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = StepStatus.class, names = {"RUNNING", "PAUSED"})
    @DisplayName("isRunning should return true for active states")
    void isRunningShouldReturnTrue(StepStatus status) {
        assertThat(status.isRunning()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = StepStatus.class, names = {"PENDING", "COMPLETED", "FAILED", "SKIPPED", "CANCELLED", "TIMEOUT"})
    @DisplayName("isRunning should return false for inactive states")
    void isRunningShouldReturnFalse(StepStatus status) {
        assertThat(status.isRunning()).isFalse();
    }
}
