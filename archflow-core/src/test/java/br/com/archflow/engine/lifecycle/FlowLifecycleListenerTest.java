package br.com.archflow.engine.lifecycle;

import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.FlowStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

@DisplayName("FlowLifecycleListener")
class FlowLifecycleListenerTest {

    private final Flow flow = mock(Flow.class);
    private final FlowStep step = mock(FlowStep.class);
    private final ExecutionContext context = mock(ExecutionContext.class);
    private final FlowResult result = mock(FlowResult.class);

    @Test
    @DisplayName("NO_OP does not throw on any callback")
    void noOpDoesNotThrow() {
        FlowLifecycleListener listener = FlowLifecycleListener.NO_OP;

        assertThatCode(() -> {
            listener.onFlowStarted(flow, context, 3);
            listener.onFlowCompleted(flow, context, result, 100L);
            listener.onFlowFailed(flow, context, new RuntimeException("test"), 50L);
            listener.onStepStarted(flow, step, context, 0, 3);
            listener.onStepCompleted(flow, step, context, 20L);
            listener.onStepFailed(flow, step, context, new RuntimeException("step fail"), 10L);
            listener.onStepSkipped(flow, step, context);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("custom implementation receives correct arguments")
    void customImplementationReceivesArgs() {
        var received = new java.util.ArrayList<String>();

        FlowLifecycleListener listener = new FlowLifecycleListener() {
            @Override
            public void onFlowStarted(Flow f, ExecutionContext ctx, int stepCount) {
                received.add("flowStarted:" + stepCount);
            }

            @Override
            public void onFlowCompleted(Flow f, ExecutionContext ctx,
                                        FlowResult r, long durationMs) {
                received.add("flowCompleted:" + durationMs);
            }

            @Override
            public void onStepStarted(Flow f, FlowStep s, ExecutionContext ctx,
                                      int stepIndex, int stepCount) {
                received.add("stepStarted:" + stepIndex + "/" + stepCount);
            }

            @Override
            public void onStepCompleted(Flow f, FlowStep s, ExecutionContext ctx,
                                        long durationMs) {
                received.add("stepCompleted:" + durationMs);
            }
        };

        listener.onFlowStarted(flow, context, 5);
        listener.onFlowCompleted(flow, context, result, 200L);
        listener.onStepStarted(flow, step, context, 2, 5);
        listener.onStepCompleted(flow, step, context, 30L);

        org.assertj.core.api.Assertions.assertThat(received)
                .containsExactly(
                        "flowStarted:5",
                        "flowCompleted:200",
                        "stepStarted:2/5",
                        "stepCompleted:30"
                );
    }

    @Test
    @DisplayName("default methods for skipped/failed do nothing by default")
    void defaultMethodsAreNoOp() {
        // Only override the ones that should record calls
        var skippedCalled = new boolean[]{false};
        var failedCalled = new boolean[]{false};

        FlowLifecycleListener listener = new FlowLifecycleListener() {
            @Override
            public void onStepSkipped(Flow f, FlowStep s, ExecutionContext ctx) {
                skippedCalled[0] = true;
            }

            @Override
            public void onFlowFailed(Flow f, ExecutionContext ctx, Throwable error, long durationMs) {
                failedCalled[0] = true;
            }
        };

        listener.onStepSkipped(flow, step, context);
        listener.onFlowFailed(flow, context, new RuntimeException("x"), 1L);

        org.assertj.core.api.Assertions.assertThat(skippedCalled[0]).isTrue();
        org.assertj.core.api.Assertions.assertThat(failedCalled[0]).isTrue();
        // Other default methods still work without throwing
        assertThatCode(() -> listener.onFlowStarted(flow, context, 1)).doesNotThrowAnyException();
    }
}
