package br.com.archflow.api.flow;

import br.com.archflow.agent.orchestration.DynamicSupervisor;
import br.com.archflow.agent.orchestration.MeteredPlanner;
import br.com.archflow.agent.orchestration.OrchestrationListener;
import br.com.archflow.agent.orchestration.SupervisorConfig;
import br.com.archflow.model.flow.ExecutionPath;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.PathStatus;
import br.com.archflow.orchestration.Budget;
import br.com.archflow.orchestration.BudgetLedger;
import br.com.archflow.orchestration.ConvergePolicy;
import br.com.archflow.orchestration.DefaultOrchestrator;
import br.com.archflow.orchestration.Goal;
import br.com.archflow.orchestration.Result;
import br.com.archflow.orchestration.Usage;
import br.com.archflow.orchestration.VerifyPolicy;
import br.com.archflow.orchestration.Worker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MaterializingOrchestrationListenerTest {

    @Test
    void materializesRoundsAndSubtasksAsExecutionPaths() {
        FlowState state = FlowState.builder().flowId("exec-1").build();
        var listener = new MaterializingOrchestrationListener(state, OrchestrationListener.NOOP);

        listener.onPlanned(1, List.of("subtask-a", "subtask-b"));
        listener.onVerified(1, "subtask-a", true);
        listener.onRoundCompleted(1, true, 1);
        listener.onConverged(1, 1);

        List<ExecutionPath> paths = state.getExecutionPaths();
        assertThat(paths).hasSize(1);
        ExecutionPath round = paths.get(0);
        assertThat(round.getPathId()).isEqualTo("round-1");
        assertThat(round.getStatus()).isEqualTo(PathStatus.COMPLETED);
        assertThat(round.getParallelBranches()).extracting(ExecutionPath::getPathId)
                .containsExactly("subtask-a", "subtask-b");
        assertThat(round.getParallelBranches()).allMatch(b -> b.getStatus() == PathStatus.COMPLETED);
    }

    /** RCHFLW-61: a subtarefa que falhou fica FAILED, com o motivo, mesmo depois de a rodada fechar. */
    @Test
    void aFailedSubtaskStaysFailedWithItsReason() {
        FlowState state = FlowState.builder().flowId("exec-1").build();
        List<String> delegated = new java.util.ArrayList<>();
        var listener = new MaterializingOrchestrationListener(state, new OrchestrationListener() {
            @Override
            public void onWorkerFailed(int round, Object subtask, String error) {
                delegated.add(subtask + "|" + error);
            }
        });

        listener.onPlanned(1, List.of("subtask-a", "subtask-b"));
        listener.onWorkerFailed(1, "subtask-b", "no agent matched subtask: subtask-b");
        listener.onVerified(1, "subtask-a", true);
        listener.onRoundCompleted(1, true, 1);

        List<ExecutionPath> branches = state.getExecutionPaths().get(0).getParallelBranches();
        assertThat(branches.get(0).getStatus()).isEqualTo(PathStatus.COMPLETED);
        assertThat(branches.get(1).getStatus()).isEqualTo(PathStatus.FAILED);
        assertThat(branches.get(1).getMetadata()).containsEntry("error", "no agent matched subtask: subtask-b");
        assertThat(delegated).containsExactly("subtask-b|no agent matched subtask: subtask-b");
    }

    /**
     * RCHFLW-64: o ramo da subtarefa no estado persistido não dizia qual componente a atendeu. Agora guarda
     * componentId, score e sucesso — e continua delegando o evento ao stream.
     */
    @Test
    void recordsTheChosenComponentOnTheSubtaskBranch() {
        FlowState state = FlowState.builder().flowId("exec-1").build();
        List<String> delegated = new java.util.ArrayList<>();
        var listener = new MaterializingOrchestrationListener(state, new OrchestrationListener() {
            @Override
            public void onDispatched(int round, Object subtask, String componentId, Double score,
                                     boolean success) {
                delegated.add(subtask + "|" + componentId);
            }
        });

        listener.onPlanned(1, List.of("subtask-a", "subtask-b"));
        listener.onDispatched(1, "subtask-a", "research-agent", 0.6, true);
        listener.onDispatched(1, "subtask-b", null, null, false);
        listener.onWorkerFailed(1, "subtask-b", "no agent matched subtask: subtask-b");
        listener.onRoundCompleted(1, true, 1);

        List<ExecutionPath> branches = state.getExecutionPaths().get(0).getParallelBranches();
        assertThat(branches.get(0).getMetadata())
                .containsEntry("componentId", "research-agent")
                .containsEntry("score", 0.6)
                .containsEntry("success", true);
        // A falha acrescenta o motivo sem apagar o despacho.
        assertThat(branches.get(1).getMetadata())
                .containsEntry("componentId", null)
                .containsEntry("success", false)
                .containsEntry("error", "no agent matched subtask: subtask-b");
        assertThat(delegated).containsExactly("subtask-a|research-agent", "subtask-b|null");
    }

    /**
     * RCHFLW-62: a subtarefa que o orçamento deixou de fora fechava COMPLETED, sem componente nem motivo —
     * o estado durável dizia que um trabalho que nunca rodou tinha terminado. Dirigido pelo supervisor de
     * verdade: planejador 100 + 30 + 30 = 160 ≥ 150, a uma das subtarefas é recusada no fan-out.
     */
    @Test
    void aSubtaskTheBudgetLeftOutIsMarkedAsNotDispatchedAndTheRoundSaysTheBudgetRanOut() {
        FlowState state = FlowState.builder().flowId("exec-1").build();
        var listener = new MaterializingOrchestrationListener(state, OrchestrationListener.NOOP);
        MeteredPlanner planner = (goal, spec) ->
                new MeteredPlanner.Decomposition(List.of("a", "b", "c"), Usage.tokens(100));
        Worker<String, Object> worker = item -> Result.success(item, Usage.tokens(30));

        new DynamicSupervisor(new DefaultOrchestrator(1)).run(Goal.of("audit"),
                new SupervisorConfig("decompose", 10, new VerifyPolicy(1, 1, List.of()),
                        ConvergePolicy.untilDry(5)),
                planner, worker, (finding, lens) -> false, new BudgetLedger(Budget.ofTokens(150)), listener);

        ExecutionPath round = state.getExecutionPaths().get(0);
        assertThat(round.getMetadata())
                .containsEntry("budgetExhausted", true)
                .containsEntry("spentTokens", 160L)
                .containsEntry("maxTokens", 150L);
        // Qual das três ficou de fora depende da ordem das threads do fan-out; que é uma só, não.
        List<ExecutionPath> branches = round.getParallelBranches();
        assertThat(branches).filteredOn(b -> b.getMetadata() != null).singleElement()
                .satisfies(b -> assertThat(b.getMetadata())
                        .containsEntry("dispatched", false)
                        .containsEntry("notDispatched", "budget exhausted"));
    }

    /** O planejador gastou o orçamento inteiro: nenhuma das planejadas saiu, e todas dizem isso. */
    @Test
    void whenThePlannerSpendsTheBudgetEveryPlannedSubtaskIsMarkedAsNotDispatched() {
        FlowState state = FlowState.builder().flowId("exec-1").build();
        var listener = new MaterializingOrchestrationListener(state, OrchestrationListener.NOOP);
        MeteredPlanner planner = (goal, spec) ->
                new MeteredPlanner.Decomposition(List.of("a", "b"), Usage.tokens(200));

        new DynamicSupervisor(new DefaultOrchestrator(4)).run(Goal.of("audit"),
                new SupervisorConfig("decompose", 10, new VerifyPolicy(1, 1, List.of()),
                        ConvergePolicy.untilDry(5)),
                planner, item -> Result.success(item, Usage.ZERO), (finding, lens) -> false,
                new BudgetLedger(Budget.ofTokens(100)), listener);

        ExecutionPath round = state.getExecutionPaths().get(0);
        assertThat(round.getMetadata()).containsEntry("budgetExhausted", true);
        assertThat(round.getParallelBranches()).allSatisfy(b -> assertThat(b.getMetadata())
                .containsEntry("dispatched", false)
                .containsEntry("notDispatched", "budget exhausted"));
    }
}
