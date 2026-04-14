package br.com.archflow.agent.execution;

import br.com.archflow.agent.config.AgentConfig;
import br.com.archflow.agent.metrics.MetricsCollector;
import br.com.archflow.engine.lifecycle.FlowLifecycleListener;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.*;
import br.com.archflow.model.metrics.StepMetrics;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the FlowLifecycleListener callbacks wired into {@link DefaultFlowExecutor}.
 *
 * <p>Verifies that step-level lifecycle events fire in the right order and with
 * the right arguments, and that listener exceptions are swallowed (never break execution).
 */
@DisplayName("DefaultFlowExecutor — lifecycle callbacks")
class DefaultFlowExecutorLifecycleTest {

    private MetricsCollector metrics;
    private RecordingListener listener;

    @BeforeEach
    void setUp() {
        metrics = new MetricsCollector(AgentConfig.builder().build());
        listener = new RecordingListener();
    }

    // ----------------------------------------------------------------
    // Callback ordering — happy path
    // ----------------------------------------------------------------

    @Test
    @DisplayName("single completed step fires onStepStarted then onStepCompleted")
    void singleStepCompleted_firesStartedThenCompleted() {
        FlowStep step = fakeStep("s1", StepStatus.COMPLETED);
        Flow flow = fakeFlow("f1", List.of(step));
        DefaultFlowExecutor executor = new DefaultFlowExecutor(
                Thread.currentThread().getContextClassLoader(), metrics, listener);

        executor.execute(flow, ctx("f1"));

        assertThat(listener.events).containsExactly("STEP_STARTED:s1:0/1", "STEP_COMPLETED:s1");
    }

    @Test
    @DisplayName("two completed steps fire events in sequence")
    void twoSteps_callbacksInOrder() {
        FlowStep s1 = fakeStep("s1", StepStatus.COMPLETED);
        FlowStep s2 = fakeStep("s2", StepStatus.COMPLETED);
        Flow flow = fakeFlow("f2", List.of(s1, s2));
        DefaultFlowExecutor executor = new DefaultFlowExecutor(
                Thread.currentThread().getContextClassLoader(), metrics, listener);

        executor.execute(flow, ctx("f2"));

        assertThat(listener.events).containsExactly(
                "STEP_STARTED:s1:0/2", "STEP_COMPLETED:s1",
                "STEP_STARTED:s2:1/2", "STEP_COMPLETED:s2"
        );
    }

    @Test
    @DisplayName("failed step fires onStepStarted then onStepFailed")
    void failedStep_firesStartedThenFailed() {
        FlowStep step = fakeStep("s1", StepStatus.FAILED);
        Flow flow = fakeFlow("f3", List.of(step));
        DefaultFlowExecutor executor = new DefaultFlowExecutor(
                Thread.currentThread().getContextClassLoader(), metrics, listener);

        executor.execute(flow, ctx("f3"));

        assertThat(listener.events).containsExactly("STEP_STARTED:s1:0/1", "STEP_FAILED:s1");
    }

    @Test
    @DisplayName("skipped step fires onStepStarted then onStepSkipped")
    void skippedStep_firesStartedThenSkipped() {
        FlowStep step = fakeStep("s1", StepStatus.SKIPPED);
        Flow flow = fakeFlow("f4", List.of(step));
        DefaultFlowExecutor executor = new DefaultFlowExecutor(
                Thread.currentThread().getContextClassLoader(), metrics, listener);

        executor.execute(flow, ctx("f4"));

        assertThat(listener.events).containsExactly("STEP_STARTED:s1:0/1", "STEP_SKIPPED:s1");
    }

    @Test
    @DisplayName("throwing step fires onStepStarted then onStepFailed")
    void throwingStep_firesStartedThenFailed() {
        FlowStep step = throwingStep("s1", new RuntimeException("boom"));
        Flow flow = fakeFlow("f5", List.of(step));
        DefaultFlowExecutor executor = new DefaultFlowExecutor(
                Thread.currentThread().getContextClassLoader(), metrics, listener);

        executor.execute(flow, ctx("f5"));

        assertThat(listener.events).containsExactly("STEP_STARTED:s1:0/1", "STEP_FAILED:s1");
    }

    // ----------------------------------------------------------------
    // Step index and count accuracy
    // ----------------------------------------------------------------

    @Test
    @DisplayName("stepIndex and stepCount are passed correctly for 3-step flow")
    void stepIndexAndCount_threeStepped() {
        FlowStep s1 = fakeStep("s1", StepStatus.COMPLETED);
        FlowStep s2 = fakeStep("s2", StepStatus.COMPLETED);
        FlowStep s3 = fakeStep("s3", StepStatus.COMPLETED);
        Flow flow = fakeFlow("f6", List.of(s1, s2, s3));
        DefaultFlowExecutor executor = new DefaultFlowExecutor(
                Thread.currentThread().getContextClassLoader(), metrics, listener);

        executor.execute(flow, ctx("f6"));

        assertThat(listener.events).contains(
                "STEP_STARTED:s1:0/3",
                "STEP_STARTED:s2:1/3",
                "STEP_STARTED:s3:2/3"
        );
    }

    // ----------------------------------------------------------------
    // Exception safety
    // ----------------------------------------------------------------

    @Test
    @DisplayName("listener throwing on onStepStarted never breaks step execution")
    void listenerException_doesNotBreakExecution() {
        AtomicInteger callCount = new AtomicInteger(0);
        FlowLifecycleListener bomb = new FlowLifecycleListener() {
            @Override
            public void onStepStarted(Flow f, FlowStep s, ExecutionContext c, int idx, int cnt) {
                callCount.incrementAndGet();
                throw new RuntimeException("listener exploded!");
            }
        };

        FlowStep step = fakeStep("s1", StepStatus.COMPLETED);
        Flow flow = fakeFlow("f7", List.of(step));
        DefaultFlowExecutor executor = new DefaultFlowExecutor(
                Thread.currentThread().getContextClassLoader(), metrics, bomb);

        // Must not throw
        var result = executor.execute(flow, ctx("f7"));

        assertThat(callCount.get()).isEqualTo(1);
        assertThat(result.getStatus().name()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("listener throwing on onStepCompleted never breaks flow result")
    void listenerExceptionOnCompleted_doesNotBreakResult() {
        FlowLifecycleListener bomb = new FlowLifecycleListener() {
            @Override
            public void onStepCompleted(Flow f, FlowStep s, ExecutionContext c, long durationMs) {
                throw new RuntimeException("completed exploded!");
            }
        };

        FlowStep step = fakeStep("s1", StepStatus.COMPLETED);
        Flow flow = fakeFlow("f8", List.of(step));
        DefaultFlowExecutor executor = new DefaultFlowExecutor(
                Thread.currentThread().getContextClassLoader(), metrics, bomb);

        var result = executor.execute(flow, ctx("f8"));

        assertThat(result.getStatus().name()).isEqualTo("COMPLETED");
    }

    // ----------------------------------------------------------------
    // NO_OP listener (default constructor)
    // ----------------------------------------------------------------

    @Test
    @DisplayName("default constructor uses NO_OP listener without throwing")
    void defaultConstructor_noOpListener_doesNotThrow() {
        DefaultFlowExecutor executor = new DefaultFlowExecutor(
                Thread.currentThread().getContextClassLoader(), metrics);

        FlowStep step = fakeStep("s1", StepStatus.COMPLETED);
        Flow flow = fakeFlow("f9", List.of(step));

        var result = executor.execute(flow, ctx("f9"));
        assertThat(result.getStatus().name()).isEqualTo("COMPLETED");
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private ExecutionContext ctx(String flowId) {
        var mem = MessageWindowChatMemory.builder().maxMessages(10).build();
        DefaultExecutionContext ctx = new DefaultExecutionContext("tenant-1", "u1", "s1", mem);
        FlowState state = new FlowState();
        state.setFlowId(flowId);
        state.setTenantId("tenant-1");
        ctx.setState(state);
        return ctx;
    }

    private FlowStep fakeStep(String id, StepStatus status) {
        return new FlowStep() {
            @Override public String getId() { return id; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override
            public CompletableFuture<StepResult> execute(ExecutionContext context) {
                return CompletableFuture.completedFuture(new StepResult() {
                    @Override public String getStepId() { return id; }
                    @Override public StepStatus getStatus() { return status; }
                    @Override public Optional<Object> getOutput() { return Optional.of("out-" + id); }
                    @Override public StepMetrics getMetrics() { return null; }
                    @Override public List<StepError> getErrors() {
                        return status.isError()
                                ? List.of(StepError.of(ErrorType.EXECUTION, "ERR", "step failed"))
                                : List.of();
                    }
                });
            }
        };
    }

    private FlowStep throwingStep(String id, RuntimeException ex) {
        return new FlowStep() {
            @Override public String getId() { return id; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override
            public CompletableFuture<StepResult> execute(ExecutionContext ctx) {
                return CompletableFuture.failedFuture(ex);
            }
        };
    }

    private Flow fakeFlow(String id, List<FlowStep> steps) {
        return new Flow() {
            @Override public String getId() { return id; }
            @Override public FlowMetadata getMetadata() { return null; }
            @Override public List<FlowStep> getSteps() { return steps; }
            @Override public br.com.archflow.model.config.FlowConfiguration getConfiguration() { return null; }
            @Override public void validate() {}
        };
    }

    /** Records lifecycle events as strings for easy assertion. */
    private static class RecordingListener implements FlowLifecycleListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void onStepStarted(Flow flow, FlowStep step, ExecutionContext ctx,
                                  int stepIndex, int stepCount) {
            events.add("STEP_STARTED:" + step.getId() + ":" + stepIndex + "/" + stepCount);
        }

        @Override
        public void onStepCompleted(Flow flow, FlowStep step, ExecutionContext ctx, long durationMs) {
            events.add("STEP_COMPLETED:" + step.getId());
        }

        @Override
        public void onStepFailed(Flow flow, FlowStep step, ExecutionContext ctx,
                                 Throwable error, long durationMs) {
            events.add("STEP_FAILED:" + step.getId());
        }

        @Override
        public void onStepSkipped(Flow flow, FlowStep step, ExecutionContext ctx) {
            events.add("STEP_SKIPPED:" + step.getId());
        }
    }
}
