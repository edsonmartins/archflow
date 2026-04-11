package br.com.archflow.agent.e2e;

import br.com.archflow.agent.config.AgentConfig;
import br.com.archflow.agent.execution.DefaultExecutionManager;
import br.com.archflow.agent.execution.DefaultFlowExecutor;
import br.com.archflow.agent.execution.DefaultParallelExecutor;
import br.com.archflow.agent.metrics.MetricsCollector;
import br.com.archflow.model.config.FlowConfiguration;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.*;
import br.com.archflow.model.metrics.StepMetrics;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test that wires the real engine stack:
 * DefaultFlowExecutor → DefaultExecutionManager → DefaultParallelExecutor
 * and runs a multi-step flow with success, failure and parallel branches.
 */
@DisplayName("E2E Flow Engine Integration")
class FlowEngineE2ETest {

    private MetricsCollector metrics;
    private DefaultFlowExecutor executor;
    private DefaultExecutionManager manager;

    @BeforeEach
    void setUp() {
        metrics = new MetricsCollector(AgentConfig.builder().build());
        executor = new DefaultFlowExecutor(
                Thread.currentThread().getContextClassLoader(), metrics);
        var parallel = new DefaultParallelExecutor(Executors.newFixedThreadPool(4), 4);
        manager = new DefaultExecutionManager(executor, parallel, Executors.newSingleThreadExecutor());
    }

    @AfterEach
    void tearDown() {
        metrics.close();
    }

    @Test
    @DisplayName("3-step sequential flow completes successfully")
    void threeStepSequentialFlow() {
        AtomicInteger order = new AtomicInteger(0);
        FlowStep s1 = recordingStep("s1", order, StepStatus.COMPLETED, "step1-out");
        FlowStep s2 = recordingStep("s2", order, StepStatus.COMPLETED, "step2-out");
        FlowStep s3 = recordingStep("s3", order, StepStatus.COMPLETED, "step3-out");
        Flow flow = flow("f1", List.of(s1, s2, s3));

        FlowResult result = manager.executeFlow(flow, createCtx("f1"));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(result.getOutput()).contains("step3-out");
        assertThat(result.getErrors()).isEmpty();
        assertThat(order.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("step failure produces FAILED flow with error details")
    void stepFailureProducesFailedFlow() {
        FlowStep s1 = recordingStep("s1", new AtomicInteger(), StepStatus.COMPLETED, "ok");
        FlowStep s2 = recordingStep("s2", new AtomicInteger(), StepStatus.FAILED, null);
        Flow flow = flow("f2", List.of(s1, s2));

        FlowResult result = manager.executeFlow(flow, createCtx("f2"));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getErrors()).isNotEmpty();
    }

    @Test
    @DisplayName("step throwing exception wraps into FAILED result")
    void stepExceptionWrapped() {
        FlowStep ok = recordingStep("s1", new AtomicInteger(), StepStatus.COMPLETED, "fine");
        FlowStep boom = new FlowStep() {
            @Override public String getId() { return "s-boom"; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override
            public CompletableFuture<StepResult> execute(ExecutionContext ctx) {
                return CompletableFuture.failedFuture(new RuntimeException("engine failure"));
            }
        };
        Flow flow = flow("f3", List.of(ok, boom));

        FlowResult result = manager.executeFlow(flow, createCtx("f3"));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    @DisplayName("empty flow completes immediately")
    void emptyFlow() {
        FlowResult result = manager.executeFlow(flow("f4", List.of()), createCtx("f4"));
        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(result.getOutput()).isEmpty();
    }

    @Test
    @DisplayName("parallel executor runs steps concurrently and collects all results")
    void parallelSteps() {
        AtomicInteger concurrent = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        List<FlowStep> steps = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final String id = "p" + i;
            steps.add(new FlowStep() {
                @Override public String getId() { return id; }
                @Override public StepType getType() { return StepType.TOOL; }
                @Override public List<StepConnection> getConnections() { return List.of(); }
                @Override
                public CompletableFuture<StepResult> execute(ExecutionContext ctx) {
                    return CompletableFuture.supplyAsync(() -> {
                        int c = concurrent.incrementAndGet();
                        maxConcurrent.updateAndGet(prev -> Math.max(prev, c));
                        try { Thread.sleep(30); } catch (InterruptedException ignored) {}
                        concurrent.decrementAndGet();
                        return completedResult(id, id + "-done");
                    });
                }
            });
        }

        List<StepResult> results = manager.executeParallelSteps(steps, createCtx("f5"));

        assertThat(results).hasSize(4);
        assertThat(maxConcurrent.get()).isGreaterThan(1);
    }

    @Test
    @DisplayName("metrics collector tracks flow lifecycle")
    void metricsTracked() {
        FlowStep s = recordingStep("s1", new AtomicInteger(), StepStatus.COMPLETED, "ok");
        manager.executeFlow(flow("f6", List.of(s)), createCtx("f6"));

        var agg = metrics.getAggregatedMetrics();
        assertThat(agg.counters().get("flows_started")).isEqualTo(1);
    }

    // ── helpers ─────────────────────────────────────────────────

    private ExecutionContext createCtx(String flowId) {
        var mem = MessageWindowChatMemory.builder().maxMessages(10).build();
        var ctx = new DefaultExecutionContext("tenant-e2e", "user-1", "session-1", mem);
        var state = new FlowState();
        state.setFlowId(flowId);
        state.setTenantId("tenant-e2e");
        ctx.setState(state);
        return ctx;
    }

    private Flow flow(String id, List<FlowStep> steps) {
        return new Flow() {
            @Override public String getId() { return id; }
            @Override public FlowMetadata getMetadata() { return null; }
            @Override public List<FlowStep> getSteps() { return steps; }
            @Override public FlowConfiguration getConfiguration() { return null; }
            @Override public void validate() {}
        };
    }

    private FlowStep recordingStep(String id, AtomicInteger order, StepStatus status, Object output) {
        return new FlowStep() {
            @Override public String getId() { return id; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override
            public CompletableFuture<StepResult> execute(ExecutionContext ctx) {
                order.incrementAndGet();
                return CompletableFuture.completedFuture(completedResult(id, output, status));
            }
        };
    }

    private static StepResult completedResult(String stepId, Object output) {
        return completedResult(stepId, output, StepStatus.COMPLETED);
    }

    private static StepResult completedResult(String stepId, Object output, StepStatus status) {
        return new StepResult() {
            @Override public String getStepId() { return stepId; }
            @Override public StepStatus getStatus() { return status; }
            @Override public Optional<Object> getOutput() { return Optional.ofNullable(output); }
            @Override public StepMetrics getMetrics() { return null; }
            @Override public List<StepError> getErrors() {
                return status.isError()
                        ? List.of(StepError.of(ErrorType.EXECUTION, "ERR", "step " + stepId + " failed"))
                        : List.of();
            }
        };
    }
}
