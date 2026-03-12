package br.com.archflow.model.error;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ExecutionError")
class ExecutionErrorTest {

    @Test
    @DisplayName("should create with factory method (code, message, type)")
    void shouldCreateWithBasicFactory() {
        var error = ExecutionError.of("ERR-001", "Test error", ExecutionErrorType.EXECUTION);

        assertThat(error.code()).isEqualTo("ERR-001");
        assertThat(error.message()).isEqualTo("Test error");
        assertThat(error.type()).isEqualTo(ExecutionErrorType.EXECUTION);
        assertThat(error.component()).isNull();
        assertThat(error.timestamp()).isNotNull();
        assertThat(error.cause()).isNull();
        assertThat(error.details()).isEmpty();
    }

    @Test
    @DisplayName("should create with component")
    void shouldCreateWithComponent() {
        var error = ExecutionError.of("ERR-002", "Config error", ExecutionErrorType.CONFIGURATION, "FlowEngine");

        assertThat(error.component()).isEqualTo("FlowEngine");
        assertThat(error.type()).isEqualTo(ExecutionErrorType.CONFIGURATION);
    }

    @Test
    @DisplayName("should create from exception")
    void shouldCreateFromException() {
        var cause = new IllegalArgumentException("bad input");
        var error = ExecutionError.fromException("EXC-001", cause, "Validator");

        assertThat(error.code()).isEqualTo("EXC-001");
        assertThat(error.message()).isEqualTo("bad input");
        assertThat(error.type()).isEqualTo(ExecutionErrorType.SYSTEM);
        assertThat(error.component()).isEqualTo("Validator");
        assertThat(error.cause()).isSameAs(cause);
    }

    @Test
    @DisplayName("should add details immutably")
    void shouldAddDetailsImmutably() {
        var original = ExecutionError.of("ERR-001", "Test", ExecutionErrorType.EXECUTION);
        var withDetail = original.withDetail("stepId", "step-1");

        assertThat(original.details()).isEmpty();
        assertThat(withDetail.details()).containsEntry("stepId", "step-1");
        assertThat(withDetail.code()).isEqualTo(original.code());
        assertThat(withDetail.message()).isEqualTo(original.message());
    }

    @Test
    @DisplayName("should chain multiple details")
    void shouldChainMultipleDetails() {
        var error = ExecutionError.of("ERR-001", "Test", ExecutionErrorType.VALIDATION)
                .withDetail("field", "name")
                .withDetail("expected", "non-null");

        assertThat(error.details()).hasSize(2);
        assertThat(error.details()).containsEntry("field", "name");
        assertThat(error.details()).containsEntry("expected", "non-null");
    }

    @Test
    @DisplayName("ExecutionErrorType should have all expected values")
    void errorTypeShouldHaveAllValues() {
        assertThat(ExecutionErrorType.values()).contains(
                ExecutionErrorType.CONFIGURATION,
                ExecutionErrorType.VALIDATION,
                ExecutionErrorType.EXECUTION,
                ExecutionErrorType.SYSTEM,
                ExecutionErrorType.CONNECTION,
                ExecutionErrorType.AUTHORIZATION,
                ExecutionErrorType.TIMEOUT,
                ExecutionErrorType.NOT_FOUND,
                ExecutionErrorType.INVALID_STATE,
                ExecutionErrorType.UNKNOWN
        );
    }
}
