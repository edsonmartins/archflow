package br.com.archflow.agent.execution;

import br.com.archflow.engine.execution.FlowExecutor;
import br.com.archflow.engine.execution.ParallelExecutor;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionMetrics;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.error.ExecutionError;
import br.com.archflow.model.flow.*;
import br.com.archflow.model.metrics.StepMetrics;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultExecutionManager")
class DefaultExecutionManagerTest {

    private DefaultExecutionManager manager;
    private RecordingFlowExecutor flowExecutor;

    @BeforeEach
    void setUp() {
        flowExecutor = new RecordingFlowExecutor();
        ParallelExecutor parallel = new ParallelExecutor() {
            @Override public List<StepResult> executeParallel(List<FlowStep> steps, ExecutionContext ctx) { return List.of(); }
            @Override public void awaitCompletion() {}
        };
        manager = new DefaultExecutionManager(flowExecutor, parallel, Executors.newSingleThreadExecutor());
    }

    @Test
    @DisplayName("delegates to FlowExecutor and returns its result")
    void delegatesExecution() {
        Flow flow = stubFlow("f1");
        ExecutionContext ctx = createCtx("f1");

        FlowResult result = manager.executeFlow(flow, ctx);

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(flowExecutor.executedFlowIds).containsExactly("f1");
    }

    @Test
    @DisplayName("wraps FlowExecutor exception into FAILED FlowResult")
    void wrapsException() {
        flowExecutor.shouldThrow = new RuntimeException("engine boom");
        Flow flow = stubFlow("f2");

        FlowResult result = manager.executeFlow(flow, createCtx("f2"));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0).message()).contains("engine boom");
    }

    @Test
    @DisplayName("pauseFlow is safe on unknown flowId")
    void pauseUnknown() {
        manager.pauseFlow("ghost");
    }

    @Test
    @DisplayName("stopFlow is safe on unknown flowId")
    void stopUnknown() {
        manager.stopFlow("ghost");
    }

    @Test
    @DisplayName("executeParallelSteps delegates to ParallelExecutor")
    void parallelDelegation() {
        var results = manager.executeParallelSteps(List.of(), createCtx("f3"));
        assertThat(results).isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────

    private ExecutionContext createCtx(String flowId) {
        var chatMemory = MessageWindowChatMemory.builder().maxMessages(10).build();
        var ctx = new DefaultExecutionContext("t1", "u1", "s1", chatMemory);
        var state = new FlowState();
        state.setFlowId(flowId);
        state.setTenantId("t1");
        ctx.setState(state);
        return ctx;
    }

    private Flow stubFlow(String id) {
        return new Flow() {
            @Override public String getId() { return id; }
            @Override public FlowMetadata getMetadata() { return null; }
            @Override public List<FlowStep> getSteps() { return List.of(); }
            @Override public br.com.archflow.model.config.FlowConfiguration getConfiguration() { return null; }
            @Override public void validate() {}
        };
    }

    private static final class RecordingFlowExecutor implements FlowExecutor {
        final java.util.ArrayList<String> executedFlowIds = new java.util.ArrayList<>();
        RuntimeException shouldThrow;

        @Override
        public FlowResult execute(Flow flow, ExecutionContext context) {
            if (shouldThrow != null) throw shouldThrow;
            executedFlowIds.add(flow.getId());
            return new FlowResult() {
                @Override public ExecutionStatus getStatus() { return ExecutionStatus.COMPLETED; }
                @Override public Optional<Object> getOutput() { return Optional.empty(); }
                @Override public ExecutionMetrics getMetrics() { return context.getMetrics(); }
                @Override public List<ExecutionError> getErrors() { return List.of(); }
            };
        }

        @Override
        public void handleResult(StepResult result) {}
    }
}
