package br.com.archflow.agent.execution;

import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.*;
import br.com.archflow.model.metrics.StepMetrics;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DefaultParallelExecutor")
class DefaultParallelExecutorTest {

    private DefaultParallelExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new DefaultParallelExecutor(Executors.newFixedThreadPool(4), 4);
    }

    @Test
    @DisplayName("executes multiple steps in parallel and returns all results")
    void allSucceed() {
        FlowStep s1 = fakeStep("s1", StepStatus.COMPLETED, "a");
        FlowStep s2 = fakeStep("s2", StepStatus.COMPLETED, "b");
        FlowStep s3 = fakeStep("s3", StepStatus.COMPLETED, "c");

        List<StepResult> results = executor.executeParallel(List.of(s1, s2, s3), createCtx());

        assertThat(results).hasSize(3);
        assertThat(results).extracting(StepResult::getStepId).containsExactlyInAnyOrder("s1", "s2", "s3");
    }

    @Test
    @DisplayName("one failure does not prevent others from completing")
    void partialFailure() {
        FlowStep ok = fakeStep("s1", StepStatus.COMPLETED, "ok");
        FlowStep fail = throwingStep("s2", new RuntimeException("boom"));
        FlowStep ok2 = fakeStep("s3", StepStatus.COMPLETED, "ok2");

        assertThatThrownBy(() -> executor.executeParallel(List.of(ok, fail, ok2), createCtx()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("empty step list returns empty results")
    void emptyList() {
        List<StepResult> results = executor.executeParallel(List.of(), createCtx());
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("null context throws IllegalArgumentException")
    void nullContext() {
        assertThatThrownBy(() -> executor.executeParallel(List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("semaphore limits concurrency")
    void semaphoreEnforced() {
        AtomicInteger concurrent = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        DefaultParallelExecutor limited = new DefaultParallelExecutor(Executors.newFixedThreadPool(8), 2);

        List<FlowStep> steps = List.of(
                countingStep("s1", concurrent, maxConcurrent),
                countingStep("s2", concurrent, maxConcurrent),
                countingStep("s3", concurrent, maxConcurrent),
                countingStep("s4", concurrent, maxConcurrent));

        limited.executeParallel(steps, createCtx());

        assertThat(maxConcurrent.get()).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("awaitCompletion is a no-op")
    void awaitCompletion() {
        executor.awaitCompletion();
    }

    // ── helpers ─────────────────────────────────────────────────

    private ExecutionContext createCtx() {
        var mem = MessageWindowChatMemory.builder().maxMessages(10).build();
        var ctx = new DefaultExecutionContext("t1", "u1", "s1", mem);
        var state = new FlowState();
        state.setFlowId("f1");
        state.setTenantId("t1");
        ctx.setState(state);
        return ctx;
    }

    private FlowStep fakeStep(String id, StepStatus status, Object output) {
        return new FlowStep() {
            @Override public String getId() { return id; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override
            public CompletableFuture<StepResult> execute(ExecutionContext ctx) {
                return CompletableFuture.completedFuture(new StepResult() {
                    @Override public String getStepId() { return id; }
                    @Override public StepStatus getStatus() { return status; }
                    @Override public Optional<Object> getOutput() { return Optional.ofNullable(output); }
                    @Override public StepMetrics getMetrics() { return null; }
                    @Override public List<StepError> getErrors() { return List.of(); }
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
            public CompletableFuture<StepResult> execute(ExecutionContext ctx) {
                return CompletableFuture.failedFuture(error);
            }
        };
    }

    private FlowStep countingStep(String id, AtomicInteger concurrent, AtomicInteger maxConcurrent) {
        return new FlowStep() {
            @Override public String getId() { return id; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override
            public CompletableFuture<StepResult> execute(ExecutionContext ctx) {
                return CompletableFuture.supplyAsync(() -> {
                    int c = concurrent.incrementAndGet();
                    maxConcurrent.updateAndGet(prev -> Math.max(prev, c));
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                    concurrent.decrementAndGet();
                    return (StepResult) new StepResult() {
                        @Override public String getStepId() { return id; }
                        @Override public StepStatus getStatus() { return StepStatus.COMPLETED; }
                        @Override public Optional<Object> getOutput() { return Optional.empty(); }
                        @Override public StepMetrics getMetrics() { return null; }
                        @Override public List<StepError> getErrors() { return List.of(); }
                    };
                });
            }
        };
    }
}
