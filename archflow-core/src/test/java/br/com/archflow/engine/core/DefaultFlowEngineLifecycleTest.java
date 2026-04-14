package br.com.archflow.engine.core;

import br.com.archflow.engine.exceptions.FlowEngineException;
import br.com.archflow.engine.lifecycle.FlowLifecycleListener;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.engine.validation.FlowValidator;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultFlowEngine — lifecycle listener")
class DefaultFlowEngineLifecycleTest {

    @Mock ExecutionManager executionManager;
    @Mock FlowRepository flowRepository;
    @Mock StateManager stateManager;
    @Mock FlowValidator flowValidator;

    private final CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();

    private final FlowLifecycleListener listener = new FlowLifecycleListener() {
        @Override
        public void onFlowStarted(br.com.archflow.model.flow.Flow f, ExecutionContext ctx, int sc) {
            events.add("started:" + f.getId() + ":steps=" + sc);
        }

        @Override
        public void onFlowCompleted(br.com.archflow.model.flow.Flow f, ExecutionContext ctx,
                                    FlowResult result, long d) {
            events.add("completed:" + f.getId());
        }

        @Override
        public void onFlowFailed(br.com.archflow.model.flow.Flow f, ExecutionContext ctx,
                                 Throwable e, long d) {
            events.add("failed:" + f.getId() + ":" + e.getMessage());
        }
    };

    private DefaultFlowEngine engine;

    @BeforeEach
    void setUp() {
        engine = new DefaultFlowEngine(
                executionManager, flowRepository, stateManager, flowValidator,
                null, null, 10, 30_000, listener);
    }

    private br.com.archflow.model.flow.Flow mockFlow(String id) {
        var flow = mock(br.com.archflow.model.flow.Flow.class);
        lenient().when(flow.getId()).thenReturn(id);
        lenient().when(flow.getSteps()).thenReturn(List.of());
        return flow;
    }

    private FlowResult mockResult() {
        return mock(FlowResult.class);
    }

    @Nested
    @DisplayName("onFlowStarted → onFlowCompleted")
    class SuccessPath {

        @Test
        @DisplayName("emits started then completed in order")
        void emitsStartedThenCompleted() throws Exception {
            var flow = mockFlow("flow-1");
            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
            when(executionManager.executeFlow(eq(flow), any())).thenReturn(mockResult());

            engine.startFlow("flow-1", Map.of()).get();

            assertThat(events).containsExactly("started:flow-1:steps=0", "completed:flow-1");
        }

        @Test
        @DisplayName("step count reflects actual flow steps")
        void stepCountIsCorrect() throws Exception {
            var step1 = mock(FlowStep.class);
            var step2 = mock(FlowStep.class);
            var flow = mockFlow("flow-2");
            when(flow.getSteps()).thenReturn(List.of(step1, step2));
            when(flowRepository.findById("flow-2")).thenReturn(Optional.of(flow));
            when(executionManager.executeFlow(eq(flow), any())).thenReturn(mockResult());

            engine.startFlow("flow-2", Map.of()).get();

            assertThat(events).anySatisfy(e -> assertThat(e).contains("steps=2"));
        }
    }

    @Nested
    @DisplayName("onFlowStarted → onFlowFailed")
    class FailurePath {

        @Test
        @DisplayName("emits started then failed on execution error")
        void emitsStartedThenFailed() {
            var flow = mockFlow("flow-1");
            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
            when(executionManager.executeFlow(eq(flow), any()))
                    .thenThrow(new RuntimeException("boom"));

            assertThatThrownBy(() -> engine.startFlow("flow-1", Map.of()).get())
                    .isInstanceOf(ExecutionException.class);

            assertThat(events).hasSize(2);
            assertThat(events.get(0)).startsWith("started:flow-1");
            assertThat(events.get(1)).startsWith("failed:flow-1");
        }

        @Test
        @DisplayName("failed event contains the error message")
        void failedEventContainsMessage() {
            var flow = mockFlow("flow-1");
            when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
            when(executionManager.executeFlow(eq(flow), any()))
                    .thenThrow(new RuntimeException("connection refused"));

            assertThatThrownBy(() -> engine.startFlow("flow-1", Map.of()).get())
                    .isInstanceOf(ExecutionException.class);

            assertThat(events).anySatisfy(e ->
                    assertThat(e).contains("connection refused"));
        }
    }

    @Test
    @DisplayName("listener exception is swallowed — flow still completes")
    void listenerExceptionSwallowed() throws Exception {
        var throwingListener = new FlowLifecycleListener() {
            @Override
            public void onFlowStarted(br.com.archflow.model.flow.Flow f, ExecutionContext ctx, int sc) {
                throw new RuntimeException("listener explodes!");
            }
        };

        var engineWithThrowingListener = new DefaultFlowEngine(
                executionManager, flowRepository, stateManager, flowValidator,
                null, null, 10, 30_000, throwingListener);

        var flow = mockFlow("flow-1");
        when(flowRepository.findById("flow-1")).thenReturn(Optional.of(flow));
        var expected = mockResult();
        when(executionManager.executeFlow(eq(flow), any())).thenReturn(expected);

        var result = engineWithThrowingListener.startFlow("flow-1", Map.of()).get();

        assertThat(result).isSameAs(expected);
    }
}
