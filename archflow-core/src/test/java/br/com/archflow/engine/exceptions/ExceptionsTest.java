package br.com.archflow.engine.exceptions;

import br.com.archflow.model.flow.ErrorType;
import br.com.archflow.model.flow.StepError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Exceptions")
class ExceptionsTest {

    @Nested
    @DisplayName("FlowException")
    class FlowExceptionTest {

        @Test
        @DisplayName("should create with message")
        void shouldCreateWithMessage() {
            var ex = new FlowException("test error");

            assertThat(ex).hasMessage("test error");
            assertThat(ex).isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("should create with message and cause")
        void shouldCreateWithCause() {
            var cause = new RuntimeException("root cause");
            var ex = new FlowException("wrapper", cause);

            assertThat(ex).hasMessage("wrapper");
            assertThat(ex).hasCause(cause);
        }
    }

    @Nested
    @DisplayName("FlowEngineException")
    class FlowEngineExceptionTest {

        @Test
        @DisplayName("should extend FlowException")
        void shouldExtendFlowException() {
            var ex = new FlowEngineException("engine error");

            assertThat(ex).isInstanceOf(FlowException.class);
            assertThat(ex).hasMessage("engine error");
        }

        @Test
        @DisplayName("should create with cause")
        void shouldCreateWithCause() {
            var cause = new IllegalStateException("bad state");
            var ex = new FlowEngineException("engine error", cause);

            assertThat(ex).hasCause(cause);
        }
    }

    @Nested
    @DisplayName("FlowNotFoundException")
    class FlowNotFoundExceptionTest {

        @Test
        @DisplayName("should include flow id in message")
        void shouldIncludeFlowId() {
            var ex = new FlowNotFoundException("flow-123");

            assertThat(ex.getMessage()).contains("flow-123");
            assertThat(ex.getFlowId()).isEqualTo("flow-123");
        }

        @Test
        @DisplayName("should extend FlowException")
        void shouldExtendFlowException() {
            var ex = new FlowNotFoundException("id");
            assertThat(ex).isInstanceOf(FlowException.class);
        }
    }

    @Nested
    @DisplayName("FlowValidationException")
    class FlowValidationExceptionTest {

        @Test
        @DisplayName("should contain validation errors")
        void shouldContainErrors() {
            var errors = List.of(
                    new ValidationError("field1", "error1", "CODE1", Map.of()),
                    new ValidationError("field2", "error2", "CODE2", Map.of())
            );

            var ex = new FlowValidationException(errors);

            assertThat(ex.getErrors()).hasSize(2);
            assertThat(ex.getMessage()).contains("2 errors found");
        }

        @Test
        @DisplayName("should return unmodifiable error list")
        void shouldReturnUnmodifiableList() {
            var errors = List.of(
                    new ValidationError("f", "m", "c", Map.of())
            );
            var ex = new FlowValidationException(errors);

            assertThatThrownBy(() -> ex.getErrors().add(
                    new ValidationError("x", "y", "z", Map.of())))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("StepExecutionException")
    class StepExecutionExceptionTest {

        @Test
        @DisplayName("should include step id and error")
        void shouldIncludeStepIdAndError() {
            var error = StepError.of(ErrorType.EXECUTION, "ERR-001", "Step failed");
            var ex = new StepExecutionException("step-1", error);

            assertThat(ex.getStepId()).isEqualTo("step-1");
            assertThat(ex.getError()).isSameAs(error);
            assertThat(ex.getMessage()).contains("step-1");
        }
    }

    @Nested
    @DisplayName("ValidationError")
    class ValidationErrorTest {

        @Test
        @DisplayName("should create record with all fields")
        void shouldCreateRecord() {
            var ctx = Map.<String, Object>of("key", "value");
            var error = new ValidationError("flow.id", "ID required", "FLOW_ID_REQUIRED", ctx);

            assertThat(error.field()).isEqualTo("flow.id");
            assertThat(error.message()).isEqualTo("ID required");
            assertThat(error.code()).isEqualTo("FLOW_ID_REQUIRED");
            assertThat(error.context()).containsEntry("key", "value");
        }

        @Test
        @DisplayName("should support equality")
        void shouldSupportEquality() {
            var e1 = new ValidationError("f", "m", "c", Map.of());
            var e2 = new ValidationError("f", "m", "c", Map.of());

            assertThat(e1).isEqualTo(e2);
        }
    }
}
