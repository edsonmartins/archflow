package br.com.archflow.model.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ExecutionStatus")
class ExecutionStatusTest {

    @Test
    @DisplayName("should have all expected values")
    void shouldHaveAllValues() {
        assertThat(ExecutionStatus.values()).containsExactly(
                ExecutionStatus.COMPLETED,
                ExecutionStatus.FAILED,
                ExecutionStatus.CANCELLED,
                ExecutionStatus.RUNNING,
                ExecutionStatus.PAUSED
        );
    }

    @Test
    @DisplayName("should resolve from name")
    void shouldResolveFromName() {
        assertThat(ExecutionStatus.valueOf("COMPLETED")).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(ExecutionStatus.valueOf("FAILED")).isEqualTo(ExecutionStatus.FAILED);
        assertThat(ExecutionStatus.valueOf("RUNNING")).isEqualTo(ExecutionStatus.RUNNING);
    }

    @Test
    @DisplayName("should throw on invalid name")
    void shouldThrowOnInvalidName() {
        assertThatThrownBy(() -> ExecutionStatus.valueOf("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
