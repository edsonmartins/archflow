package br.com.archflow.engine.core;

import br.com.archflow.engine.exceptions.FlowEngineException;
import br.com.archflow.engine.exceptions.FlowNotFoundException;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.engine.validation.FlowValidator;
import br.com.archflow.model.config.FlowConfiguration;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultFlowEngine")
class DefaultFlowEngineTest {

    @Mock
    private ExecutionManager executionManager;
    @Mock
    private FlowRepository flowRepository;
    @Mock
    private StateManager stateManager;
    @Mock
    private FlowValidator flowValidator;

    private DefaultFlowEngine engine;

    @BeforeEach
    void setUp() {
        engine = new DefaultFlowEngine(executionManager, flowRepository, stateManager, flowValidator);
    }

    private Flow createMockFlow(String flowId) {
        Flow flow = mock(Flow.class);
        lenient().when(flow.getId()).thenReturn(flowId);
        return flow;
    }

    private FlowResult createMockResult() {
        return mock(FlowResult.class);
    }

    @Nested
    @DisplayName("startFlow")
    class StartFlowTest {

        @Test
        @DisplayName("should execute flow successfully")
        void shouldExecuteFlowSuccessfully() throws Exception {
            var flow = createMockFlow("flow-1");
            var expectedResult = createMockResult();

            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
            when(executionManager.executeFlow(eq(flow), any(ExecutionContext.class)))
                    .thenReturn(expectedResult);

            var result = engine.startFlow("flow-1", Map.of("input", "test")).get();

            assertThat(result).isSameAs(expectedResult);
            verify(flowValidator).validate(flow);
            verify(executionManager).executeFlow(eq(flow), any(ExecutionContext.class));
        }

        @Test
        @DisplayName("should pass input variables to context")
        void shouldPassInputVariables() throws Exception {
            var flow = createMockFlow("flow-1");
            var expectedResult = createMockResult();
            var input = Map.<String, Object>of("key", "value");

            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
            when(executionManager.executeFlow(eq(flow), any(ExecutionContext.class)))
                    .thenReturn(expectedResult);

            engine.startFlow("flow-1", input).get();

            verify(executionManager).executeFlow(eq(flow), argThat(ctx ->
                    ctx.getState().getVariables().containsKey("key")));
        }

        @Test
        @DisplayName("should throw when flow not found")
        void shouldThrowWhenFlowNotFound() {
            when(flowRepository.findById("missing")).thenReturn(Optional.empty());

            var future = engine.startFlow("missing", Map.of());

            assertThatThrownBy(future::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(FlowEngineException.class);
        }

        @Test
        @DisplayName("should handle null input map")
        void shouldHandleNullInput() throws Exception {
            var flow = createMockFlow("flow-1");
            var expectedResult = createMockResult();

            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
            when(executionManager.executeFlow(eq(flow), any(ExecutionContext.class)))
                    .thenReturn(expectedResult);

            var result = engine.startFlow("flow-1", null).get();

            assertThat(result).isSameAs(expectedResult);
        }

        @Test
        @DisplayName("should track active executions")
        void shouldTrackActiveExecutions() throws Exception {
            var flow = createMockFlow("flow-1");
            var expectedResult = createMockResult();

            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
            when(executionManager.executeFlow(eq(flow), any(ExecutionContext.class)))
                    .thenAnswer(inv -> {
                        // During execution, flow should be active
                        assertThat(engine.getActiveFlows()).contains("flow-1");
                        return expectedResult;
                    });

            engine.startFlow("flow-1", Map.of()).get();
        }

        @Test
        @DisplayName("should save error state on failure")
        void shouldSaveErrorStateOnFailure() {
            var flow = createMockFlow("flow-1");

            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
            when(executionManager.executeFlow(eq(flow), any(ExecutionContext.class)))
                    .thenThrow(new RuntimeException("Execution failed"));

            var future = engine.startFlow("flow-1", Map.of());

            assertThatThrownBy(future::get)
                    .isInstanceOf(ExecutionException.class);

            verify(stateManager).saveState(eq("flow-1"), argThat(state ->
                    state.getStatus() == FlowStatus.FAILED && state.getError() != null));
        }
    }

    @Nested
    @DisplayName("execute")
    class ExecuteTest {

        @Test
        @DisplayName("should execute with provided context")
        void shouldExecuteWithContext() throws Exception {
            var flow = createMockFlow("flow-1");
            var context = mock(ExecutionContext.class);
            var state = FlowState.builder().flowId("flow-1").status(FlowStatus.INITIALIZED).build();
            when(context.getState()).thenReturn(state);

            var expectedResult = createMockResult();
            when(executionManager.executeFlow(flow, context)).thenReturn(expectedResult);

            var result = engine.execute(flow, context).get();

            assertThat(result).isSameAs(expectedResult);
            verify(flowValidator).validate(flow);
        }

        @Test
        @DisplayName("should create initial state when context has no state")
        void shouldCreateInitialStateWhenMissing() throws Exception {
            var flow = createMockFlow("flow-1");
            var context = mock(ExecutionContext.class);
            when(context.getState()).thenReturn(null);

            var expectedResult = createMockResult();
            when(executionManager.executeFlow(flow, context)).thenReturn(expectedResult);

            engine.execute(flow, context).get();

            verify(context).setState(argThat(state ->
                    state.getFlowId().equals("flow-1") &&
                    state.getStatus() == FlowStatus.INITIALIZED));
        }
    }

    @Nested
    @DisplayName("resumeFlow")
    class ResumeFlowTest {

        @Test
        @DisplayName("should resume paused flow")
        void shouldResumePausedFlow() throws Exception {
            var flow = createMockFlow("flow-1");
            var context = mock(ExecutionContext.class);
            var pausedState = FlowState.builder()
                    .flowId("flow-1")
                    .status(FlowStatus.PAUSED)
                    .build();

            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
            when(stateManager.loadState("flow-1")).thenReturn(pausedState);

            var expectedResult = createMockResult();
            when(executionManager.executeFlow(flow, context)).thenReturn(expectedResult);

            var result = engine.resumeFlow("flow-1", context).get();

            assertThat(result).isSameAs(expectedResult);
            // Estado é atualizado para RUNNING ao retomar
            var stateCaptor = org.mockito.ArgumentCaptor.forClass(FlowState.class);
            verify(context).setState(stateCaptor.capture());
            assertThat(stateCaptor.getValue().getStatus()).isEqualTo(FlowStatus.RUNNING);
            assertThat(stateCaptor.getValue().getFlowId()).isEqualTo("flow-1");
        }

        @Test
        @DisplayName("should throw when no state found")
        void shouldThrowWhenNoState() {
            var flow = createMockFlow("flow-1");
            var context = mock(ExecutionContext.class);

            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
            when(stateManager.loadState("flow-1")).thenReturn(null);

            var future = engine.resumeFlow("flow-1", context);

            assertThatThrownBy(future::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(FlowEngineException.class);
        }

        @Test
        @DisplayName("should throw when flow is in final state")
        void shouldThrowWhenFinalState() {
            var flow = createMockFlow("flow-1");
            var context = mock(ExecutionContext.class);
            var completedState = FlowState.builder()
                    .flowId("flow-1")
                    .status(FlowStatus.COMPLETED)
                    .build();

            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
            when(stateManager.loadState("flow-1")).thenReturn(completedState);

            var future = engine.resumeFlow("flow-1", context);

            assertThatThrownBy(future::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(FlowEngineException.class);
        }
    }

    @Nested
    @DisplayName("getFlowStatus")
    class GetFlowStatusTest {

        @Test
        @DisplayName("should return status from active execution")
        void shouldReturnActiveStatus() throws Exception {
            var flow = createMockFlow("flow-1");
            var expectedResult = createMockResult();

            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
            when(executionManager.executeFlow(eq(flow), any(ExecutionContext.class)))
                    .thenAnswer(inv -> {
                        // Check status during execution
                        var status = engine.getFlowStatus("flow-1");
                        assertThat(status).isEqualTo(FlowStatus.INITIALIZED);
                        return expectedResult;
                    });

            engine.startFlow("flow-1", Map.of()).get();
        }

        @Test
        @DisplayName("should return status from state manager for inactive flows")
        void shouldReturnStoredStatus() {
            var state = FlowState.builder()
                    .flowId("flow-1")
                    .status(FlowStatus.COMPLETED)
                    .build();
            when(stateManager.loadState("flow-1")).thenReturn(state);

            var status = engine.getFlowStatus("flow-1");

            assertThat(status).isEqualTo(FlowStatus.COMPLETED);
        }

        @Test
        @DisplayName("should throw when flow not found anywhere")
        void shouldThrowWhenNotFound() {
            when(stateManager.loadState("missing")).thenReturn(null);

            assertThatThrownBy(() -> engine.getFlowStatus("missing"))
                    .isInstanceOf(FlowEngineException.class);
        }
    }

    @Nested
    @DisplayName("pause")
    class PauseTest {

        @Test
        @DisplayName("should pause active flow")
        void shouldPauseActiveFlow() throws Exception {
            var flow = createMockFlow("flow-1");
            var expectedResult = createMockResult();

            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
            when(executionManager.executeFlow(eq(flow), any(ExecutionContext.class)))
                    .thenAnswer(inv -> {
                        engine.pause("flow-1");

                        verify(stateManager).saveState(eq("flow-1"), argThat(state ->
                                state.getStatus() == FlowStatus.PAUSED));
                        verify(executionManager).pauseFlow("flow-1");
                        return expectedResult;
                    });

            engine.startFlow("flow-1", Map.of()).get();
        }

        @Test
        @DisplayName("should throw when flow not active")
        void shouldThrowWhenNotActive() {
            assertThatThrownBy(() -> engine.pause("missing"))
                    .isInstanceOf(FlowEngineException.class);
        }
    }

    @Nested
    @DisplayName("cancel")
    class CancelTest {

        @Test
        @DisplayName("should cancel active flow — state STOPPED + executor notified")
        void shouldCancelActiveFlow() throws Exception {
            var flow = createMockFlow("flow-1");
            var expectedResult = createMockResult();

            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
            when(executionManager.executeFlow(eq(flow), any(ExecutionContext.class)))
                    .thenAnswer(inv -> {
                        // cancel() sets the STOPPED state and notifies the executor
                        // but does NOT remove from activeExecutions — that's done
                        // by submitFlow's finally block after the executor returns.
                        // This prevents the race where cancel() and submitFlow()
                        // both call remove() and confuse the cleanup path.
                        engine.cancel("flow-1");

                        verify(stateManager).saveState(eq("flow-1"), argThat(state ->
                                state.getStatus() == FlowStatus.STOPPED));
                        verify(executionManager).stopFlow("flow-1");
                        return expectedResult;
                    });

            engine.startFlow("flow-1", Map.of()).get();

            // After the virtual thread finishes, the flow is cleaned up.
            assertThat(engine.getActiveFlows()).doesNotContain("flow-1");
        }

        @Test
        @DisplayName("should throw when flow not active")
        void shouldThrowWhenNotActive() {
            assertThatThrownBy(() -> engine.cancel("missing"))
                    .isInstanceOf(FlowEngineException.class);
        }
    }

    @Nested
    @DisplayName("getActiveFlows")
    class GetActiveFlowsTest {

        @Test
        @DisplayName("should return empty set initially")
        void shouldReturnEmptySetInitially() {
            assertThat(engine.getActiveFlows()).isEmpty();
        }

        @Test
        @DisplayName("should return defensive copy")
        void shouldReturnDefensiveCopy() {
            var flows = engine.getActiveFlows();
            assertThatThrownBy(() -> flows.add("hack"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
