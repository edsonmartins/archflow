package br.com.archflow.model.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("StepError")
class StepErrorTest {

    @Test
    @DisplayName("should create with factory method")
    void shouldCreateWithFactoryMethod() {
        var error = StepError.of(ErrorType.EXECUTION, "ERR-001", "Something failed");

        assertThat(error.type()).isEqualTo(ErrorType.EXECUTION);
        assertThat(error.code()).isEqualTo("ERR-001");
        assertThat(error.message()).isEqualTo("Something failed");
        assertThat(error.timestamp()).isNotNull();
        assertThat(error.context()).isEmpty();
        assertThat(error.cause()).isNull();
    }

    @Test
    @DisplayName("should create with context")
    void shouldCreateWithContext() {
        var context = Map.<String, Object>of("stepId", "step-1", "input", "test");
        var error = StepError.of(ErrorType.VALIDATION, "VAL-001", "Invalid input", context);

        assertThat(error.type()).isEqualTo(ErrorType.VALIDATION);
        assertThat(error.context()).containsEntry("stepId", "step-1");
        assertThat(error.context()).containsEntry("input", "test");
    }

    @Test
    @DisplayName("should create from exception")
    void shouldCreateFromException() {
        var exception = new RuntimeException("Test exception");
        var error = StepError.fromException(exception, "EXC-001");

        assertThat(error.type()).isEqualTo(ErrorType.EXECUTION);
        assertThat(error.code()).isEqualTo("EXC-001");
        assertThat(error.message()).isEqualTo("Test exception");
        assertThat(error.cause()).isSameAs(exception);
        assertThat(error.timestamp()).isNotNull();
    }

    @Test
    @DisplayName("should support record equality")
    void shouldSupportEquality() {
        var error1 = new StepError(ErrorType.EXECUTION, "ERR-001", "msg", null, Map.of(), null);
        var error2 = new StepError(ErrorType.EXECUTION, "ERR-001", "msg", null, Map.of(), null);

        assertThat(error1).isEqualTo(error2);
    }
}
