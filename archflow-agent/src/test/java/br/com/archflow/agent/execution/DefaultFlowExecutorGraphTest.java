package br.com.archflow.agent.execution;

import br.com.archflow.agent.config.AgentConfig;
import br.com.archflow.agent.metrics.MetricsCollector;
import br.com.archflow.engine.execution.FlowControl;
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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cobre a semântica de GRAFO do executor — travessia por conexões, condições,
 * caminho de erro e cancelamento cooperativo. As fixtures do teste legado usam
 * apenas fluxos lineares sem conexões; estes cenários eram exatamente os
 * buracos apontados na auditoria de homologação.
 */
@DisplayName("DefaultFlowExecutor — semântica de grafo")
class DefaultFlowExecutorGraphTest {

    private DefaultFlowExecutor executor;
    private List<String> executionOrder;

    @BeforeEach
    void setUp() {
        AgentConfig config = AgentConfig.builder().build();
        executor = new DefaultFlowExecutor(
                Thread.currentThread().getContextClassLoader(), new MetricsCollector(config));
        executionOrder = new CopyOnWriteArrayList<>();
    }

    @Test
    @DisplayName("segue as conexões: A→B→C executa na ordem do grafo, cada step uma única vez")
    void followsConnectionsInGraphOrder() {
        FlowStep a = step("A", StepStatus.COMPLETED, null, conn("A", "B", null, false));
        FlowStep b = step("B", StepStatus.COMPLETED, null, conn("B", "C", null, false));
        FlowStep c = step("C", StepStatus.COMPLETED, "final", new StepConnection[0]);
        // Ordem da lista invertida de propósito: o grafo deve mandar, não a lista
        Flow flow = flow("g1", List.of(c, b, a));

        FlowResult result = executor.execute(flow, context("g1"));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(executionOrder).containsExactly("A", "B", "C");
        assertThat(result.getOutput()).contains("final");
    }

    @Test
    @DisplayName("branching condicional: só o ramo cuja condição é verdadeira executa")
    void conditionalBranching() {
        FlowStep a = step("A", StepStatus.COMPLETED, null,
                conn("A", "B", "${score} > 0.8", false),
                conn("A", "C", "${score} <= 0.8", false));
        FlowStep b = step("B", StepStatus.COMPLETED, "alto", new StepConnection[0]);
        FlowStep c = step("C", StepStatus.COMPLETED, "baixo", new StepConnection[0]);
        Flow flow = flow("g2", List.of(a, b, c));

        ExecutionContext ctx = context("g2");
        ctx.set("score", 0.9);

        FlowResult result = executor.execute(flow, ctx);

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(executionOrder).containsExactly("A", "B");
    }

    @Test
    @DisplayName("falha com caminho de erro definido roteia para ele e o fluxo termina COMPLETED")
    void errorPathIsTakenOnFailure() {
        FlowStep a = step("A", StepStatus.FAILED, null,
                conn("A", "B", null, false),
                conn("A", "E", null, true));
        FlowStep b = step("B", StepStatus.COMPLETED, null, new StepConnection[0]);
        FlowStep e = step("E", StepStatus.COMPLETED, "tratado", new StepConnection[0]);
        Flow flow = flow("g3", List.of(a, b, e));

        FlowResult result = executor.execute(flow, context("g3"));

        assertThat(executionOrder).containsExactly("A", "E");
        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    @Test
    @DisplayName("falha sem caminho de erro termina FAILED e não segue o caminho normal")
    void unhandledFailureFailsFlow() {
        FlowStep a = step("A", StepStatus.FAILED, null, conn("A", "B", null, false));
        FlowStep b = step("B", StepStatus.COMPLETED, null, new StepConnection[0]);
        Flow flow = flow("g4", List.of(a, b));

        FlowResult result = executor.execute(flow, context("g4"));

        assertThat(executionOrder).containsExactly("A");
        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getErrors()).isNotEmpty();
    }

    @Test
    @DisplayName("ciclo A→B→A não re-executa steps (cada um roda uma vez)")
    void cycleDoesNotReExecute() {
        FlowStep a = step("A", StepStatus.COMPLETED, null, conn("A", "B", null, false));
        FlowStep b = step("B", StepStatus.COMPLETED, null, conn("B", "A", null, false));
        FlowStep root = step("R", StepStatus.COMPLETED, null, conn("R", "A", null, false));
        Flow flow = flow("g5", List.of(root, a, b));

        FlowResult result = executor.execute(flow, context("g5"));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(executionOrder).containsExactly("R", "A", "B");
    }

    @Test
    @DisplayName("grafo sem step de entrada (ciclo fechado) falha alto")
    void graphWithoutEntryFails() {
        FlowStep a = step("A", StepStatus.COMPLETED, null, conn("A", "B", null, false));
        FlowStep b = step("B", StepStatus.COMPLETED, null, conn("B", "A", null, false));
        Flow flow = flow("g6", List.of(a, b));

        assertThatThrownBy(() -> executor.execute(flow, context("g6")))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("stop cooperativo interrompe a travessia com CANCELLED")
    void cooperativeStop() {
        // O sinal liga após o primeiro step: B não deve executar
        var control = new MutableControl();
        FlowStep a = new FlowStep() {
            @Override public String getId() { return "A"; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() {
                return List.of(conn("A", "B", null, false));
            }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext context) {
                executionOrder.add("A");
                control.stopped = true;
                return CompletableFuture.completedFuture(result("A", StepStatus.COMPLETED, null));
            }
        };
        FlowStep b = step("B", StepStatus.COMPLETED, null, new StepConnection[0]);
        Flow flow = flow("g7", List.of(a, b));

        FlowResult flowResult = executor.execute(flow, context("g7"), control);

        assertThat(executionOrder).containsExactly("A");
        assertThat(flowResult.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
    }

    @Test
    @DisplayName("pause cooperativo suspende a travessia com PAUSED e checkpoint no estado")
    void cooperativePause() {
        var control = new MutableControl();
        FlowStep a = new FlowStep() {
            @Override public String getId() { return "A"; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() {
                return List.of(conn("A", "B", null, false));
            }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext context) {
                executionOrder.add("A");
                control.paused = true;
                return CompletableFuture.completedFuture(result("A", StepStatus.COMPLETED, null));
            }
        };
        FlowStep b = step("B", StepStatus.COMPLETED, null, new StepConnection[0]);
        Flow flow = flow("g8", List.of(a, b));
        ExecutionContext ctx = context("g8");

        FlowResult flowResult = executor.execute(flow, ctx, control);

        assertThat(executionOrder).containsExactly("A");
        assertThat(flowResult.getStatus()).isEqualTo(ExecutionStatus.PAUSED);
        assertThat(ctx.getState().getCurrentStepId()).isEqualTo("A");
    }

    @Test
    @DisplayName("fluxo legado sem conexões continua executando a lista em ordem")
    void legacyLinearFlowStillWorks() {
        FlowStep s1 = step("s1", StepStatus.COMPLETED, null, new StepConnection[0]);
        FlowStep s2 = step("s2", StepStatus.COMPLETED, "fim", new StepConnection[0]);
        Flow flow = flow("g9", List.of(s1, s2));

        FlowResult result = executor.execute(flow, context("g9"));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(executionOrder).containsExactly("s1", "s2");
    }

    @Test
    @DisplayName("resume incremental: steps concluídos em execução anterior não reexecutam")
    void incrementalResumeSkipsCompletedSteps() {
        FlowStep a = step("A", StepStatus.COMPLETED, null, conn("A", "B", null, false));
        FlowStep b = step("B", StepStatus.COMPLETED, null, conn("B", "C", null, false));
        FlowStep c = step("C", StepStatus.COMPLETED, "fim", new StepConnection[0]);
        Flow flow = flow("g10", List.of(a, b, c));

        ExecutionContext ctx = context("g10");
        // Simula estado restaurado de uma execução pausada após A e B
        ctx.set(DefaultFlowExecutor.COMPLETED_STEPS_KEY, List.of("A", "B"));

        FlowResult result = executor.execute(flow, ctx);

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(executionOrder).containsExactly("C");
    }

    @Test
    @DisplayName("retry: step FAILED é retentado conforme RetryConfig até suceder")
    void retryUntilSuccess() {
        DefaultFlowExecutor retryingExecutor = new DefaultFlowExecutor(
                Thread.currentThread().getContextClassLoader(),
                new MetricsCollector(AgentConfig.builder().build()),
                null, 0,
                new br.com.archflow.agent.config.RetryConfig(3, 1, 2.0));

        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        FlowStep flaky = new FlowStep() {
            @Override public String getId() { return "A"; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext context) {
                executionOrder.add("A");
                boolean ok = attempts.incrementAndGet() >= 3;
                return CompletableFuture.completedFuture(
                        result("A", ok ? StepStatus.COMPLETED : StepStatus.FAILED, ok ? "ok" : null));
            }
        };
        Flow flow = flow("g11", List.of(flaky));

        FlowResult result = retryingExecutor.execute(flow, context("g11"));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(executionOrder).containsExactly("A", "A", "A");
    }

    // ── helpers ─────────────────────────────────────────────────

    private static final class MutableControl implements FlowControl {
        volatile boolean paused;
        volatile boolean stopped;
        @Override public boolean isPauseRequested() { return paused; }
        @Override public boolean isStopRequested() { return stopped; }
    }

    private ExecutionContext context(String flowId) {
        var chatMemory = MessageWindowChatMemory.builder().maxMessages(10).build();
        DefaultExecutionContext ctx = new DefaultExecutionContext("tenant-1", "user-1", "session-1", chatMemory);
        FlowState state = new FlowState();
        state.setFlowId(flowId);
        state.setTenantId("tenant-1");
        ctx.setState(state);
        return ctx;
    }

    private static StepConnection conn(String source, String target, String condition, boolean errorPath) {
        return new StepConnection() {
            @Override public String getSourceId() { return source; }
            @Override public String getTargetId() { return target; }
            @Override public Optional<String> getCondition() { return Optional.ofNullable(condition); }
            @Override public boolean isErrorPath() { return errorPath; }
        };
    }

    private FlowStep step(String id, StepStatus status, Object output, StepConnection... connections) {
        return new FlowStep() {
            @Override public String getId() { return id; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(connections); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext context) {
                executionOrder.add(id);
                return CompletableFuture.completedFuture(result(id, status, output));
            }
        };
    }

    private static StepResult result(String stepId, StepStatus status, Object output) {
        return new StepResult() {
            @Override public String getStepId() { return stepId; }
            @Override public StepStatus getStatus() { return status; }
            @Override public Optional<Object> getOutput() { return Optional.ofNullable(output); }
            @Override public StepMetrics getMetrics() { return null; }
            @Override public List<StepError> getErrors() {
                return status.isError()
                        ? List.of(StepError.of(ErrorType.EXECUTION, "ERR", "step failed"))
                        : List.of();
            }
        };
    }

    private static Flow flow(String id, List<FlowStep> steps) {
        return new Flow() {
            @Override public String getId() { return id; }
            @Override public FlowMetadata getMetadata() { return null; }
            @Override public List<FlowStep> getSteps() { return steps; }
            @Override public br.com.archflow.model.config.FlowConfiguration getConfiguration() { return null; }
            @Override public void validate() {}
        };
    }
}
