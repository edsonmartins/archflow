package br.com.archflow.engine.validation;

import br.com.archflow.engine.exceptions.FlowValidationException;
import br.com.archflow.model.config.FlowConfiguration;
import br.com.archflow.model.flow.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("DefaultFlowValidator")
class DefaultFlowValidatorTest {

    private DefaultFlowValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DefaultFlowValidator();
    }

    private Flow createValidFlow() {
        var step1 = createStep("step-1", StepType.AGENT, List.of(
                createConnection("step-1", "step-2", false)
        ));
        var step2 = createStep("step-2", StepType.TOOL, List.of());

        var flow = mock(Flow.class);
        when(flow.getId()).thenReturn("flow-1");
        when(flow.getSteps()).thenReturn(List.of(step1, step2));
        when(flow.getConfiguration()).thenReturn(mock(FlowConfiguration.class));
        return flow;
    }

    private FlowStep createStep(String id, StepType type, List<StepConnection> connections) {
        var step = mock(FlowStep.class);
        when(step.getId()).thenReturn(id);
        when(step.getType()).thenReturn(type);
        when(step.getConnections()).thenReturn(connections);
        return step;
    }

    private StepConnection createConnection(String source, String target, boolean errorPath) {
        var conn = mock(StepConnection.class);
        when(conn.getSourceId()).thenReturn(source);
        when(conn.getTargetId()).thenReturn(target);
        when(conn.isErrorPath()).thenReturn(errorPath);
        when(conn.getCondition()).thenReturn(Optional.empty());
        return conn;
    }

    @Nested
    @DisplayName("validate flow")
    class ValidateFlowTest {

        @Test
        @DisplayName("should accept valid flow")
        void shouldAcceptValidFlow() {
            var flow = createValidFlow();

            assertThatNoException().isThrownBy(() -> validator.validate(flow));
        }

        @Test
        @DisplayName("should reject flow with null id")
        void shouldRejectNullId() {
            var step = createStep("s1", StepType.TOOL, List.of());
            var flow = mock(Flow.class);
            when(flow.getId()).thenReturn(null);
            when(flow.getSteps()).thenReturn(List.of(step));

            assertThatThrownBy(() -> validator.validate(flow))
                    .isInstanceOf(FlowValidationException.class)
                    .satisfies(ex -> {
                        var errors = ((FlowValidationException) ex).getErrors();
                        assertThat(errors).anyMatch(e -> e.code().equals("FLOW_ID_REQUIRED"));
                    });
        }

        @Test
        @DisplayName("should reject flow with empty id")
        void shouldRejectEmptyId() {
            var step = createStep("s1", StepType.TOOL, List.of());
            var flow = mock(Flow.class);
            when(flow.getId()).thenReturn("  ");
            when(flow.getSteps()).thenReturn(List.of(step));

            assertThatThrownBy(() -> validator.validate(flow))
                    .isInstanceOf(FlowValidationException.class);
        }

        @Test
        @DisplayName("should reject flow with no steps")
        void shouldRejectNoSteps() {
            var flow = mock(Flow.class);
            when(flow.getId()).thenReturn("flow-1");
            when(flow.getSteps()).thenReturn(List.of());

            assertThatThrownBy(() -> validator.validate(flow))
                    .isInstanceOf(FlowValidationException.class)
                    .satisfies(ex -> {
                        var errors = ((FlowValidationException) ex).getErrors();
                        assertThat(errors).anyMatch(e -> e.code().equals("FLOW_EMPTY"));
                    });
        }

        @Test
        @DisplayName("should reject invalid connection target")
        void shouldRejectInvalidConnectionTarget() {
            var step = createStep("step-1", StepType.AGENT, List.of(
                    createConnection("step-1", "nonexistent", false)
            ));

            var flow = mock(Flow.class);
            when(flow.getId()).thenReturn("flow-1");
            when(flow.getSteps()).thenReturn(List.of(step));

            assertThatThrownBy(() -> validator.validate(flow))
                    .isInstanceOf(FlowValidationException.class)
                    .satisfies(ex -> {
                        var errors = ((FlowValidationException) ex).getErrors();
                        assertThat(errors).anyMatch(e -> e.code().equals("INVALID_CONNECTION_TARGET"));
                    });
        }

        @Test
        @DisplayName("should detect cycles")
        void shouldDetectCycles() {
            var step1 = createStep("step-1", StepType.AGENT, List.of(
                    createConnection("step-1", "step-2", false)
            ));
            var step2 = createStep("step-2", StepType.TOOL, List.of(
                    createConnection("step-2", "step-1", false)
            ));

            var flow = mock(Flow.class);
            when(flow.getId()).thenReturn("flow-1");
            when(flow.getSteps()).thenReturn(List.of(step1, step2));

            assertThatThrownBy(() -> validator.validate(flow))
                    .isInstanceOf(FlowValidationException.class)
                    .satisfies(ex -> {
                        var errors = ((FlowValidationException) ex).getErrors();
                        assertThat(errors).anyMatch(e -> e.code().equals("FLOW_CYCLE_DETECTED"));
                    });
        }

        @Test
        @DisplayName("should collect multiple errors")
        void shouldCollectMultipleErrors() {
            var flow = mock(Flow.class);
            when(flow.getId()).thenReturn(null);
            when(flow.getSteps()).thenReturn(List.of());

            assertThatThrownBy(() -> validator.validate(flow))
                    .isInstanceOf(FlowValidationException.class)
                    .satisfies(ex -> {
                        var errors = ((FlowValidationException) ex).getErrors();
                        assertThat(errors).hasSizeGreaterThanOrEqualTo(2);
                    });
        }
    }

    @Nested
    @DisplayName("validateStep")
    class ValidateStepTest {

        @Test
        @DisplayName("should accept valid step")
        void shouldAcceptValidStep() {
            var step = createStep("step-1", StepType.AGENT, List.of());
            var flow = createValidFlow();
            var context = new ValidationContext(flow);

            assertThatNoException().isThrownBy(() -> validator.validateStep(step, context));
        }

        @Test
        @DisplayName("should reject step with null id")
        void shouldRejectNullStepId() {
            var step = createStep(null, StepType.TOOL, List.of());
            var flow = createValidFlow();
            var context = new ValidationContext(flow);

            assertThatThrownBy(() -> validator.validateStep(step, context))
                    .isInstanceOf(FlowValidationException.class)
                    .satisfies(ex -> {
                        var errors = ((FlowValidationException) ex).getErrors();
                        assertThat(errors).anyMatch(e -> e.code().equals("STEP_ID_REQUIRED"));
                    });
        }

        @Test
        @DisplayName("should reject step with null type")
        void shouldRejectNullStepType() {
            var step = createStep("step-1", null, List.of());
            var flow = createValidFlow();
            var context = new ValidationContext(flow);

            assertThatThrownBy(() -> validator.validateStep(step, context))
                    .isInstanceOf(FlowValidationException.class)
                    .satisfies(ex -> {
                        var errors = ((FlowValidationException) ex).getErrors();
                        assertThat(errors).anyMatch(e -> e.code().equals("STEP_TYPE_REQUIRED"));
                    });
        }

        @Test
        @DisplayName("should reject step with CUSTOM type as unsupported")
        void shouldRejectCustomType() {
            var step = createStep("step-1", StepType.CUSTOM, List.of());
            var flow = createValidFlow();
            var context = new ValidationContext(flow);

            assertThatThrownBy(() -> validator.validateStep(step, context))
                    .isInstanceOf(FlowValidationException.class)
                    .satisfies(ex -> {
                        var errors = ((FlowValidationException) ex).getErrors();
                        assertThat(errors).anyMatch(e -> e.code().equals("UNSUPPORTED_STEP_TYPE"));
                    });
        }
    }
}
