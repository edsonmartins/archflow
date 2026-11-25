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
import java.util.Map;
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
    private Map<String, Object> received;

    @BeforeEach
    void setUp() {
        AgentConfig config = AgentConfig.builder().build();
        executor = new DefaultFlowExecutor(
                Thread.currentThread().getContextClassLoader(), new MetricsCollector(config));
        executionOrder = new CopyOnWriteArrayList<>();
        received = new java.util.concurrent.ConcurrentHashMap<>();
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
    @DisplayName("pausa pedida durante o ÚLTIMO step (folha do grafo) termina PAUSED, com o step no checkpoint")
    void pauseRequestedDuringLastStepEndsPaused() {
        // O nó de aprovação pede a pausa de dentro do step. Sendo a folha, a fila
        // esvazia logo depois — e a pausa não pode se perder por falta de próximo step.
        var control = new MutableControl();
        FlowStep a = step("A", StepStatus.COMPLETED, null, conn("A", "B", null, false));
        FlowStep b = signallingStep("B", () -> control.paused = true);
        Flow flow = flow("g12", List.of(a, b));
        ExecutionContext ctx = context("g12");

        FlowResult flowResult = executor.execute(flow, ctx, control);

        assertThat(flowResult.getStatus()).isEqualTo(ExecutionStatus.PAUSED);
        assertThat(executionOrder).containsExactly("A", "B");
        // Concluído no checkpoint: a retomada não reexecuta o step que pediu a pausa
        assertThat(ctx.get(DefaultFlowExecutor.COMPLETED_STEPS_KEY)).hasValue(List.of("A", "B"));
    }

    @Test
    @DisplayName("pausa pedida durante o único step de um fluxo legado (sem conexões) termina PAUSED")
    void pauseRequestedDuringOnlyLegacyStepEndsPaused() {
        var control = new MutableControl();
        FlowStep only = signallingStep("gate", () -> control.paused = true);
        Flow flow = flow("g13", List.of(only));

        FlowResult flowResult = executor.execute(flow, context("g13"), control);

        assertThat(flowResult.getStatus()).isEqualTo(ExecutionStatus.PAUSED);
    }

    @Test
    @DisplayName("cancelamento pedido durante o último step termina CANCELLED, não COMPLETED")
    void stopRequestedDuringLastStepEndsCancelled() {
        var control = new MutableControl();
        FlowStep a = step("A", StepStatus.COMPLETED, null, conn("A", "B", null, false));
        // Stop e pause juntos: stop vence, como no topo do laço
        FlowStep b = signallingStep("B", () -> {
            control.paused = true;
            control.stopped = true;
        });
        Flow flow = flow("g14", List.of(a, b));

        FlowResult flowResult = executor.execute(flow, context("g14"), control);

        assertThat(flowResult.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
    }

    @Test
    @DisplayName("sem sinal de controle, terminar a fila continua sendo COMPLETED")
    void noSignalStillCompletes() {
        var control = new MutableControl();
        FlowStep a = step("A", StepStatus.COMPLETED, null, conn("A", "B", null, false));
        FlowStep b = step("B", StepStatus.COMPLETED, "fim", new StepConnection[0]);

        FlowResult flowResult = executor.execute(flow("g15", List.of(a, b)), context("g15"), control);

        assertThat(flowResult.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
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
        ctx.set(DefaultFlowExecutor.STEP_DATA_KEY, Map.of("A", "dado-A", "B", "dado-B"));

        FlowResult result = executor.execute(flow, ctx);

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(executionOrder).containsExactly("C");
    }

    // ── Entrada por aresta, join e saída final (RCHFLW-35/36) ───

    @Test
    @DisplayName("fan-out A→B, A→C: B e C recebem a saída de A, não a do ramo vizinho")
    void fanOutBranchesReceiveSourceOutput() {
        FlowStep a = component("A", s -> "Olá mundo, tudo bem?", conn("A", "B", null, false),
                conn("A", "C", null, false));
        FlowStep b = component("B", s -> "traduzido(" + s + ")");
        FlowStep c = component("C", s -> "contado(" + s + ")");
        ExecutionContext ctx = context("f1");
        ctx.set(DefaultFlowExecutor.INPUT_KEY, "entrada");

        FlowResult result = executor.execute(flow("f1", List.of(a, b, c)), ctx);

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(received).containsEntry("A", "entrada")
                .containsEntry("B", "Olá mundo, tudo bem?")
                .containsEntry("C", "Olá mundo, tudo bem?");
        // Dois terminais executaram: a saída do fluxo é o mapa dos dois
        assertThat(result.getOutput()).contains(Map.of(
                "B", "traduzido(Olá mundo, tudo bem?)", "C", "contado(Olá mundo, tudo bem?)"));
    }

    @Test
    @DisplayName("losango assimétrico: M espera E e C, roda uma vez com as duas saídas e é a saída do fluxo")
    void asymmetricDiamondJoinWaitsForEveryBranch() {
        // A → B → D → E → M (ramo longo) e A → C → M (ramo curto)
        FlowStep a = component("A", s -> "a", conn("A", "B", null, false), conn("A", "C", null, false));
        FlowStep b = component("B", s -> s + "b", conn("B", "D", null, false));
        FlowStep d = component("D", s -> s + "d", conn("D", "E", null, false));
        FlowStep e = component("E", s -> s + "e", conn("E", "M", null, false));
        FlowStep c = component("C", s -> s + "c", conn("C", "M", null, false));
        FlowStep m = component("M", s -> "combinado:" + s);

        FlowResult result = executor.execute(flow("f2", List.of(a, b, c, d, e, m)), context("f2"));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(executionOrder).containsExactly("A", "B", "C", "D", "E", "M");
        assertThat(received.get("M")).isEqualTo(Map.of("E", "abde", "C", "ac"));
        // O mapa segue a ordem de chegada: o ramo curto chega primeiro
        assertThat(result.getOutput()).contains("combinado:{C=ac, E=abde}");
    }

    @Test
    @DisplayName("join depois de condição falsa: o ramo morto não trava o join, que espera o ramo vivo mais longo")
    void joinAfterFalseConditionDoesNotHang() {
        // A → K (nó de controle) → X → X2 → M quando verdadeiro, K → Y → M quando falso; e A → C → M.
        // K grava o ramo em context(K) e repassa a entrada intacta, como o ControlStep.
        FlowStep a = component("A", s -> "texto-A", conn("A", "K", null, false), conn("A", "C", null, false));
        FlowStep k = new FlowStep() {
            @Override public String getId() { return "K"; }
            @Override public StepType getType() { return StepType.CUSTOM; }
            @Override public List<StepConnection> getConnections() {
                return List.of(conn("K", "X", "${K.branch} == 'true'", false),
                        conn("K", "Y", "${K.branch} == 'false'", false));
            }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext context) {
                executionOrder.add("K");
                context.set("K", Map.of("branch", "true"));
                return CompletableFuture.completedFuture(
                        result("K", StepStatus.COMPLETED, Map.of("branch", "true")));
            }
        };
        FlowStep x = component("X", s -> "x(" + s + ")", conn("X", "X2", null, false));
        FlowStep x2 = component("X2", s -> "x2(" + s + ")", conn("X2", "M", null, false));
        FlowStep y = component("Y", s -> "y", conn("Y", "M", null, false));
        FlowStep c = component("C", s -> "c", conn("C", "M", null, false));
        FlowStep m = component("M", s -> "m");

        FlowResult result = executor.execute(flow("f3", List.of(a, k, x, x2, y, c, m)), context("f3"));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(executionOrder).containsExactly("A", "K", "C", "X", "X2", "M");
        // O nó de controle repassa o dado que recebeu, não o mapa do ramo
        assertThat(received).containsEntry("X", "texto-A");
        // Só os predecessores que ativaram M entram no mapa; Y teve o ramo descartado
        assertThat(received.get("M")).isEqualTo(Map.of("C", "c", "X2", "x2(x(texto-A))"));
        assertThat(result.getOutput()).contains("m");
    }

    @Test
    @DisplayName("join depois de uma falha sem caminho de erro: o ramo falho morre, o join roda e o fluxo é FAILED")
    void joinAfterUnhandledFailureRunsWithTheLiveBranch() {
        // A → B → B2 → B3 (falha) → M e A → C → M: M espera B3 resolver antes de rodar
        FlowStep a = component("A", s -> "a", conn("A", "B", null, false), conn("A", "C", null, false));
        FlowStep b = component("B", s -> "b", conn("B", "B2", null, false));
        FlowStep b2 = component("B2", s -> "b2", conn("B2", "B3", null, false));
        FlowStep b3 = step("B3", StepStatus.FAILED, null, conn("B3", "M", null, false));
        FlowStep c = component("C", s -> "c", conn("C", "M", null, false));
        FlowStep m = component("M", s -> "m(" + s + ")");

        FlowResult result = executor.execute(flow("f4", List.of(a, b, b2, b3, c, m)), context("f4"));

        assertThat(executionOrder).containsExactly("A", "B", "C", "B2", "B3", "M");
        // Com um único predecessor ativo, o dado chega direto
        assertThat(received).containsEntry("M", "c");
        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    @DisplayName("dois steps-raiz recebem, cada um, a entrada da execução")
    void everyRootReceivesTheRunInput() {
        FlowStep r1 = component("R1", s -> "saida-R1", conn("R1", "M", null, false));
        FlowStep r2 = component("R2", s -> "saida-R2", conn("R2", "M", null, false));
        FlowStep m = component("M", s -> "m");
        ExecutionContext ctx = context("f5");
        ctx.set(DefaultFlowExecutor.INPUT_KEY, "entrada");

        executor.execute(flow("f5", List.of(r1, r2, m)), ctx);

        assertThat(received).containsEntry("R1", "entrada").containsEntry("R2", "entrada");
        assertThat(received.get("M")).isEqualTo(Map.of("R1", "saida-R1", "R2", "saida-R2"));
    }

    @Test
    @DisplayName("resume: o sucessor de um step concluído antes recebe o dado registrado, não a variável global")
    void resumeFeedsRecordedDataToSuccessor() {
        FlowStep a = component("A", s -> "nao-roda", conn("A", "B", null, false), conn("A", "C", null, false));
        FlowStep b = component("B", s -> "b");
        FlowStep c = component("C", s -> "c");
        ExecutionContext ctx = context("f6");
        ctx.set(DefaultFlowExecutor.COMPLETED_STEPS_KEY, List.of("A", "B"));
        ctx.set(DefaultFlowExecutor.STEP_DATA_KEY, Map.of("A", "dado-A", "B", "b"));
        ctx.set("step.B.output", "b");
        // O que sobrou na variável global é a saída do ramo vizinho
        ctx.set(DefaultFlowExecutor.INPUT_KEY, "b");

        FlowResult result = executor.execute(flow("f6", List.of(a, b, c)), ctx);

        assertThat(executionOrder).containsExactly("C");
        assertThat(received).containsEntry("C", "dado-A");
        assertThat(result.getOutput()).contains(Map.of("B", "b", "C", "c"));
    }

    @Test
    @DisplayName("resume sem o dado registrado do predecessor: o step falha com o motivo, sem ler a variável global")
    void resumeWithoutRecordedDataFailsExplicitly() {
        FlowStep a = component("A", s -> "nao-roda", conn("A", "B", null, false));
        FlowStep b = component("B", s -> "b");
        ExecutionContext ctx = context("f7");
        ctx.set(DefaultFlowExecutor.COMPLETED_STEPS_KEY, List.of("A"));

        FlowResult result = executor.execute(flow("f7", List.of(a, b)), ctx);

        assertThat(executionOrder).isEmpty();
        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getErrors()).singleElement()
                .satisfies(e -> assertThat(e.message()).contains("Entrada do step B indisponível", "A"));
    }

    @Test
    @DisplayName("fluxo linear: falha sem caminho de erro interrompe o fluxo, como no modo grafo")
    void linearFlowStopsAtFirstFailure() {
        FlowStep s1 = step("s1", StepStatus.COMPLETED, "um", new StepConnection[0]);
        FlowStep s2 = step("s2", StepStatus.FAILED, null, new StepConnection[0]);
        FlowStep s3 = step("s3", StepStatus.COMPLETED, "tres", new StepConnection[0]);

        FlowResult result = executor.execute(flow("f8", List.of(s1, s2, s3)), context("f8"));

        assertThat(executionOrder).containsExactly("s1", "s2");
        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getErrors()).isNotEmpty();
    }

    @Test
    @DisplayName("saída final: o último step tem só aresta de erro e conclui — a saída é a dele")
    void outputOfStepWhoseOnlyEdgeIsAnUntakenErrorPath() {
        // A → B, B -(erro)→ H, como o onError do DSL: B conclui e o caminho acaba nele
        FlowStep a = component("A", s -> "a", conn("A", "B", null, false));
        FlowStep b = component("B", s -> "resposta-B", conn("B", "H", null, true));
        FlowStep h = component("H", s -> "tratado");

        FlowResult result = executor.execute(flow("t1", List.of(a, b, h)), context("t1"));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(executionOrder).containsExactly("A", "B");
        assertThat(result.getOutput()).contains("resposta-B");
    }

    @Test
    @DisplayName("saída final: ramo opcional com condição falsa — a saída é a do step onde o caminho acabou")
    void outputOfStepWhoseOnlyEdgeHasFalseCondition() {
        FlowStep a = component("A", s -> "resposta-A", conn("A", "N", "${notify} == true", false));
        FlowStep n = component("N", s -> "notificado");
        ExecutionContext ctx = context("t2");
        ctx.set("notify", false);

        FlowResult result = executor.execute(flow("t2", List.of(a, n)), ctx);

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(executionOrder).containsExactly("A");
        assertThat(result.getOutput()).contains("resposta-A");
    }

    @Test
    @DisplayName("saída final: as duas arestas do fan-out dão falso e o join morre — a saída é a de A")
    void outputWhenEveryBranchAfterTheSourceIsDiscarded() {
        // A → {B, C} condicionais e falsas, B/C → M → Z: o ramo morto se propaga até Z
        FlowStep a = component("A", s -> "resposta-A",
                conn("A", "B", "${go} == true", false), conn("A", "C", "${go} == true", false));
        FlowStep b = component("B", s -> "b", conn("B", "M", null, false));
        FlowStep c = component("C", s -> "c", conn("C", "M", null, false));
        FlowStep m = component("M", s -> "m", conn("M", "Z", null, false));
        FlowStep z = component("Z", s -> "z");
        ExecutionContext ctx = context("t3");
        ctx.set("go", false);

        FlowResult result = executor.execute(flow("t3", List.of(a, b, c, m, z)), ctx);

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(executionOrder).containsExactly("A");
        assertThat(result.getOutput()).contains("resposta-A");
    }

    @Test
    @DisplayName("saída final: predecessor cuja aresta para o join deu falso não vira terminal — a saída é a do join")
    void joinReachedByAnotherBranchKeepsTheJoinAsTheOnlyTerminal() {
        FlowStep a = component("A", s -> "a", conn("A", "B", null, false), conn("A", "C", null, false));
        FlowStep b = component("B", s -> "b", conn("B", "M", "${viaB} == true", false));
        FlowStep c = component("C", s -> "c", conn("C", "M", null, false));
        FlowStep m = component("M", s -> "m(" + s + ")");
        ExecutionContext ctx = context("t4");
        ctx.set("viaB", false);

        FlowResult result = executor.execute(flow("t4", List.of(a, b, c, m)), ctx);

        assertThat(executionOrder).containsExactly("A", "B", "C", "M");
        assertThat(result.getOutput()).contains("m(c)");
    }

    @Test
    @DisplayName("fluxo suspenso no portão de aprovação devolve a saída do portão (o requestId pendente)")
    void pausedAtApprovalGateKeepsTheGateOutput() {
        FlowStep a = component("A", s -> "a", conn("A", "G", null, false));
        FlowStep g = step("G", StepStatus.PAUSED, Map.of("requestId", "req-1"), conn("G", "B", null, false));
        FlowStep b = component("B", s -> "b");

        FlowResult result = executor.execute(flow("t5", List.of(a, g, b)), context("t5"));

        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.PAUSED);
        assertThat(result.getOutput()).contains(Map.of("requestId", "req-1"));
    }

    @Test
    @DisplayName("execução sem entrada e duas raízes: a segunda raiz não lê a saída da primeira")
    void rootsWithoutRunInputDoNotLeakIntoEachOther() {
        FlowStep r1 = component("R1", s -> "saida-R1", conn("R1", "M", null, false));
        FlowStep r2 = component("R2", s -> "saida-R2", conn("R2", "M", null, false));
        FlowStep m = component("M", s -> "m");

        executor.execute(flow("t6", List.of(r1, r2, m)), context("t6"));

        assertThat(received).containsEntry("R1", NO_INPUT).containsEntry("R2", NO_INPUT);
        assertThat(received.get("M")).isEqualTo(Map.of("R1", "saida-R1", "R2", "saida-R2"));
    }

    @Test
    @DisplayName("predecessor que repassa nada: o sucessor roda sem entrada, não com a saída de outro ramo")
    void successorOfPassThroughOfNothingGetsNoInput() {
        // Execução sem entrada; K é raiz de controle (repassa o que recebeu: nada)
        FlowStep r1 = component("R1", s -> "saida-R1");
        FlowStep k = passThrough("K", conn("K", "Y", null, false));
        FlowStep y = component("Y", s -> "y");

        executor.execute(flow("t7", List.of(r1, k, y)), context("t7"));

        assertThat(executionOrder).containsExactly("R1", "K", "Y");
        assertThat(received).containsEntry("K", NO_INPUT).containsEntry("Y", NO_INPUT);
    }

    @Test
    @DisplayName("recusa do motor (resume sem dado registrado) não toma o caminho de erro nem entrega a variável velha")
    void engineRefusalDoesNotTakeTheErrorPath() {
        FlowStep a = component("A", s -> "nao-roda", conn("A", "B", null, false));
        FlowStep b = component("B", s -> "b", conn("B", "E", null, true));
        FlowStep e = component("E", s -> "tratado");
        ExecutionContext ctx = context("t8");
        ctx.set(DefaultFlowExecutor.COMPLETED_STEPS_KEY, List.of("A"));
        ctx.set(DefaultFlowExecutor.INPUT_KEY, "stale");

        FlowResult result = executor.execute(flow("t8", List.of(a, b, e)), ctx);

        assertThat(executionOrder).isEmpty();
        assertThat(received).doesNotContainKey("E");
        assertThat(result.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.getErrors()).singleElement()
                .satisfies(err -> assertThat(err.message()).contains("Entrada do step B indisponível"));
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

    /**
     * Step que se comporta como o {@code ComponentStep}: lê o {@code input}, grava a saída em
     * {@code context(id)} e no próprio {@code input}. Guarda em {@link #received} o que leu.
     */
    private FlowStep component(String id, java.util.function.UnaryOperator<Object> fn,
                               StepConnection... connections) {
        return new FlowStep() {
            @Override public String getId() { return id; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(connections); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext context) {
                executionOrder.add(id);
                Object input = context.get(DefaultFlowExecutor.INPUT_KEY).orElse(null);
                received.put(id, input != null ? input : NO_INPUT);
                Object output = fn.apply(input);
                context.set(id, output);
                context.set(DefaultFlowExecutor.INPUT_KEY, output);
                return CompletableFuture.completedFuture(result(id, StepStatus.COMPLETED, output));
            }
        };
    }

    /** Marca, em {@link #received}, um step que executou sem {@code input}. */
    private static final String NO_INPUT = "<sem entrada>";

    /**
     * Step que decide o caminho e não transforma o dado, como o {@code ControlStep} e o portão de
     * aprovação: não toca no {@code input}.
     */
    private FlowStep passThrough(String id, StepConnection... connections) {
        return new FlowStep() {
            @Override public String getId() { return id; }
            @Override public StepType getType() { return StepType.CUSTOM; }
            @Override public List<StepConnection> getConnections() { return List.of(connections); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext context) {
                executionOrder.add(id);
                received.put(id, context.get(DefaultFlowExecutor.INPUT_KEY).orElse(NO_INPUT));
                return CompletableFuture.completedFuture(result(id, StepStatus.COMPLETED, Map.of("branch", "x")));
            }
        };
    }

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

    /** Step folha que conclui COMPLETED depois de levantar um sinal de controle. */
    private FlowStep signallingStep(String id, Runnable signal) {
        return new FlowStep() {
            @Override public String getId() { return id; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext context) {
                executionOrder.add(id);
                signal.run();
                return CompletableFuture.completedFuture(result(id, StepStatus.COMPLETED, null));
            }
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
