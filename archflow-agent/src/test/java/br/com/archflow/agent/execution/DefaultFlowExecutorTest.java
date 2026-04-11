package br.com.archflow.agent.execution;

import br.com.archflow.agent.config.AgentConfig;
import br.com.archflow.agent.metrics.MetricsCollector;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.*;
import br.com.archflow.model.metrics.StepMetrics;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultFlowExecutor")
class DefaultFlowExecutorTest {

    private MetricsCollector metricsCollector;
    private DefaultFlowExecutor executor;

    @BeforeEach
    void setUp() {
        AgentConfig config = AgentConfig.builder().build();
        metricsCollector = new MetricsCollector(config);
        executor = new DefaultFlowExecutor(Thread.currentThread().getContextClassLoader(), metricsCollector);
    }

    @Test
    @DisplayName("executes a single-step flow successfully")
    void singleStepSuccess() {
        FlowStep step = fakeStep("s1", StepStatus.COMPLETED, "hello");
        Flow flow = fakeFlow("flow-1", List.of(step));

        FlowResult result = executor.execute(flow, createContext("flow-1"));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(result.getOutput()).contains("hello");
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    @DisplayName("executes multi-step flow in sequence")
    void multiStepSequential() {
        FlowStep s1 = fakeStep("s1", StepStatus.COMPLETED, "out1");
        FlowStep s2 = fakeStep("s2", StepStatus.COMPLETED, "out2");
        Flow flow = fakeFlow("flow-2", List.of(s1, s2));

        FlowResult result = executor.execute(flow, createContext("flow-2"));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(result.getOutput()).contains("out2");
    }

    @Test
    @DisplayName("returns FAILED when any step fails")
    void stepFailurePropagates() {
        FlowStep s1 = fakeStep("s1", StepStatus.COMPLETED, "ok");
        FlowStep s2 = fakeStep("s2", StepStatus.FAILED, null);
        Flow flow = fakeFlow("flow-3", List.of(s1, s2));

        FlowResult result = executor.execute(flow, createContext("flow-3"));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getErrors()).isNotEmpty();
    }

    @Test
    @DisplayName("step throwing exception returns FAILED result")
    void stepExceptionReturnsFailedResult() {
        FlowStep step = throwingStep("s1", new RuntimeException("boom"));
        Flow flow = fakeFlow("flow-4", List.of(step));

        FlowResult result = executor.execute(flow, createContext("flow-4"));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    @DisplayName("handles empty flow (no steps)")
    void emptyFlow() {
        Flow flow = fakeFlow("flow-5", List.of());
        FlowResult result = executor.execute(flow, createContext("flow-5"));
        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(result.getOutput()).isEmpty();
    }

    @Test
    @DisplayName("handleResult ignores unknown stepId without throwing")
    void handleResultUnknown() {
        StepResult result = completedResult("unknown-step", "x");
        executor.handleResult(result);
    }

    @Test
    @DisplayName("SKIPPED step marks flow as not-COMPLETED")
    void skippedStepMarksFlowNotComplete() {
        FlowStep step = fakeStep("s1", StepStatus.SKIPPED, null);
        Flow flow = fakeFlow("flow-7", List.of(step));

        FlowResult flowResult = executor.execute(flow, createContext("flow-7"));

        assertThat(flowResult.getStatus()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    @DisplayName("records metrics on flow start")
    void metricsRecorded() {
        FlowStep step = fakeStep("s1", StepStatus.COMPLETED, "ok");
        Flow flow = fakeFlow("flow-8", List.of(step));

        executor.execute(flow, createContext("flow-8"));
        assertThat(metricsCollector.getAggregatedMetrics()).isNotNull();
    }

    @Test
    @DisplayName("error steps include StepError details")
    void errorStepsHaveDetails() {
        FlowStep step = fakeStep("s1", StepStatus.FAILED, null);
        Flow flow = fakeFlow("flow-9", List.of(step));

        FlowResult result = executor.execute(flow, createContext("flow-9"));

        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().get(0).message()).isEqualTo("step failed");
    }

    @Test
    @DisplayName("metrics in result comes from execution context")
    void metricsFromContext() {
        FlowStep step = fakeStep("s1", StepStatus.COMPLETED, "ok");
        Flow flow = fakeFlow("flow-10", List.of(step));
        ExecutionContext ctx = createContext("flow-10");

        FlowResult result = executor.execute(flow, ctx);

        assertThat(result.getMetrics()).isNotNull();
    }

    // ── helpers ─────────────────────────────────────────────────

    private ExecutionContext createContext(String flowId) {
        var chatMemory = MessageWindowChatMemory.builder().maxMessages(10).build();
        DefaultExecutionContext ctx = new DefaultExecutionContext("tenant-1", "user-1", "session-1", chatMemory);
        FlowState state = new FlowState();
        state.setFlowId(flowId);
        state.setTenantId("tenant-1");
        ctx.setState(state);
        return ctx;
    }

    private FlowStep fakeStep(String id, StepStatus status, Object output) {
        return new FlowStep() {
            @Override public String getId() { return id; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override
            public CompletableFuture<StepResult> execute(ExecutionContext context) {
                return CompletableFuture.completedFuture(new StepResult() {
                    @Override public String getStepId() { return id; }
                    @Override public StepStatus getStatus() { return status; }
                    @Override public Optional<Object> getOutput() { return Optional.ofNullable(output); }
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

    private FlowStep throwingStep(String id, RuntimeException error) {
        return new FlowStep() {
            @Override public String getId() { return id; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override
            public CompletableFuture<StepResult> execute(ExecutionContext context) {
                return CompletableFuture.failedFuture(error);
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

    private StepResult completedResult(String stepId, Object output) {
        return new StepResult() {
            @Override public String getStepId() { return stepId; }
            @Override public StepStatus getStatus() { return StepStatus.COMPLETED; }
            @Override public Optional<Object> getOutput() { return Optional.ofNullable(output); }
            @Override public StepMetrics getMetrics() { return null; }
            @Override public List<StepError> getErrors() { return List.of(); }
        };
    }
}
