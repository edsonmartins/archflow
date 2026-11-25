package br.com.archflow.agent.orchestration;

import br.com.archflow.orchestration.Budget;
import br.com.archflow.orchestration.BudgetLedger;
import br.com.archflow.orchestration.ConvergePolicy;
import br.com.archflow.orchestration.DefaultOrchestrator;
import br.com.archflow.orchestration.Goal;
import br.com.archflow.orchestration.Planner;
import br.com.archflow.orchestration.Result;
import br.com.archflow.orchestration.Usage;
import br.com.archflow.orchestration.VerifyPolicy;
import br.com.archflow.orchestration.Voter;
import br.com.archflow.orchestration.Worker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicSupervisorTest {

    // worker: the finding IS the subtask text.
    private final Worker<String, Object> echoWorker = item -> Result.success(item, Usage.tokens(100));
    // voter: refutes the finding "b" only.
    private final Voter<String> refuteB = (finding, lens) -> finding.equals("b");

    @Test
    void composesPlanFanOutVerifyAndLoopsUntilDry() {
        // Planner expands across rounds: [a,b] -> [a,b,c] -> stable. New work
        // appears in rounds 1-2, then rounds go dry and the loop converges.
        AtomicInteger calls = new AtomicInteger();
        Planner<String> planner = (goal, spec) -> switch (calls.incrementAndGet()) {
            case 1 -> List.of("a", "b");
            default -> List.of("a", "b", "c");
        };

        var supervisor = new DynamicSupervisor(new DefaultOrchestrator(4));
        var config = new SupervisorConfig(
                "decompose", 10,
                new VerifyPolicy(1, 1, List.of()),   // single confidence gate
                ConvergePolicy.untilDry(10));

        SupervisorResult result = supervisor.run(
                Goal.of("audit"), config, planner, echoWorker, refuteB, BudgetLedger.unlimited());

        // b is refuted; a (round 1) and c (round 2) are confirmed.
        assertThat(result.confirmed()).containsExactly("a", "c");
        // rounds 1,2 productive; 3,4 dry (dryRounds=2) -> stop at round 4.
        assertThat(result.rounds()).isEqualTo(4);
    }

    @Test
    void reportsProgressToListener() {
        Planner<String> planner = (goal, spec) -> List.of("a", "b");
        var supervisor = new DynamicSupervisor(new DefaultOrchestrator(4));
        var config = new SupervisorConfig("decompose", 10, new VerifyPolicy(1, 1, List.of()), ConvergePolicy.untilDry(5));
        var trace = new OrchestrationTrace();

        supervisor.run(Goal.of("audit"), config, planner, echoWorker, refuteB, BudgetLedger.unlimited(), trace);

        assertThat(trace.entries()).anyMatch(e -> e.type().equals("planned") && e.round() == 1);
        assertThat(trace.entries()).anyMatch(e -> e.type().equals("verified") && e.detail().equals("a") && Boolean.TRUE.equals(e.confirmed()));
        assertThat(trace.entries()).anyMatch(e -> e.type().equals("verified") && e.detail().equals("b") && Boolean.FALSE.equals(e.confirmed()));
        assertThat(trace.entries()).anyMatch(e -> e.type().equals("converged"));
    }

    /**
     * RCHFLW-61: a falha do worker era descartada com {@code continue}, sem evento — a rodada convergia
     * com zero confirmados e o trace só tinha planned/round/converged. Agora cada falha vai ao listener e
     * ao resultado, com a subtarefa e o motivo.
     */
    @Test
    void reportsEachWorkerFailureWithItsSubtaskAndReason() {
        Planner<String> planner = (goal, spec) -> List.of("a", "b");
        Worker<String, Object> failsB = item -> item.equals("b")
                ? Result.fail("research-agent: Unsupported task type") : Result.success(item);
        var supervisor = new DynamicSupervisor(new DefaultOrchestrator(4));
        var config = new SupervisorConfig("decompose", 10, new VerifyPolicy(1, 1, List.of()),
                ConvergePolicy.untilDry(5));
        var trace = new OrchestrationTrace();
        List<String> failed = new java.util.concurrent.CopyOnWriteArrayList<>();
        OrchestrationListener both = new OrchestrationListener() {
            @Override
            public void onWorkerFailed(int round, Object subtask, String error) {
                trace.onWorkerFailed(round, subtask, error);
                failed.add(round + "|" + subtask + "|" + error);
            }
        };

        SupervisorResult result = supervisor.run(Goal.of("audit"), config, planner, failsB,
                (finding, lens) -> false, BudgetLedger.unlimited(), both);

        assertThat(failed).containsExactly("1|b|research-agent: Unsupported task type");
        assertThat(trace.entries()).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo("failed");
            assertThat(e.detail()).contains("b").contains("Unsupported task type");
        });
        assertThat(result.dispatched()).isEqualTo(2);
        assertThat(result.failures()).singleElement()
                .isEqualTo(new SupervisorResult.SubtaskFailure(1, "b", "research-agent: Unsupported task type"));
        assertThat(result.allSubtasksFailed()).isFalse();
        assertThat(result.confirmed()).containsExactly("a");
    }

    @Test
    void allSubtasksFailingIsReportedAsSuchAndSummarizedByReason() {
        Planner<String> planner = (goal, spec) -> List.of("a", "b", "c");
        Worker<String, Object> failsAll = item -> item.equals("c")
                ? Result.fail("no agent matched subtask: c") : Result.fail("x-agent: recusou");
        // Concorrência 1: as falhas voltam na ordem das subtarefas, e o resumo segue essa ordem.
        var supervisor = new DynamicSupervisor(new DefaultOrchestrator(1));
        var config = new SupervisorConfig("decompose", 10, new VerifyPolicy(1, 1, List.of()),
                ConvergePolicy.untilDry(5));
        var trace = new OrchestrationTrace();

        SupervisorResult result = supervisor.run(Goal.of("audit"), config, planner, failsAll,
                (finding, lens) -> false, BudgetLedger.unlimited(), trace);

        assertThat(result.confirmed()).isEmpty();
        assertThat(result.dispatched()).isEqualTo(3);
        assertThat(result.failedCount()).isEqualTo(3);
        assertThat(result.allSubtasksFailed()).isTrue();
        assertThat(result.failureSummary()).isEqualTo("2× x-agent: recusou; 1× no agent matched subtask: c");
        // Nada foi verificado: o trace não pode ter um "verified" que sugira rejeição.
        assertThat(trace.entries()).noneMatch(e -> e.type().equals("verified"));
        assertThat(trace.entries()).filteredOn(e -> e.type().equals("failed")).hasSize(3);
    }

    @Test
    void stopsWhenBudgetExhausted() {
        // Static planner returns 4 subtasks; each worker call costs 100 tokens;
        // budget 150 with serial fan-out => only 2 run, then the loop's budget
        // check stops further rounds.
        Planner<String> planner = (goal, spec) -> List.of("a", "b", "c", "d");

        var supervisor = new DynamicSupervisor(new DefaultOrchestrator(1));
        var config = new SupervisorConfig(
                "decompose", 10,
                new VerifyPolicy(1, 1, List.of()),
                ConvergePolicy.untilDry(10));
        BudgetLedger budget = new BudgetLedger(Budget.ofTokens(150));

        SupervisorResult result = supervisor.run(
                Goal.of("audit"), config, planner,
                item -> Result.success(item, Usage.tokens(100)),
                (finding, lens) -> false,           // confirm everything that ran
                budget);

        // Exactly 2 of the 4 subtasks run before the budget is exhausted (which
        // 2 is not guaranteed under a non-fair semaphore — only the count is),
        // then the loop's budget check stops further rounds.
        assertThat(result.rounds()).isEqualTo(1);
        assertThat(result.confirmedCount()).isEqualTo(2);
        assertThat(result.confirmed()).isSubsetOf("a", "b", "c", "d");
        assertThat(budget.spentTokens()).isEqualTo(200);
        // As 2 que o orçamento barrou não chegaram ao worker: não são despacho nem falha.
        assertThat(result.dispatched()).isEqualTo(2);
        assertThat(result.notDispatched()).isEqualTo(2);
        assertThat(result.failures()).isEmpty();
    }

    @Test
    void subtasksSkippedByAnExhaustedBudgetAreNotReportedAsFailures() {
        // O planner gasta o orçamento inteiro (como fará quando debitar o próprio uso): o orquestrador
        // recusa todos os itens sem chamar o worker. Isso é parada por orçamento, não "todas falharam".
        BudgetLedger budget = new BudgetLedger(Budget.ofTokens(100));
        Planner<String> planner = (goal, spec) -> {
            budget.charge(Usage.tokens(100));
            return List.of("a", "b", "c");
        };
        AtomicInteger workerCalls = new AtomicInteger();
        Worker<String, Object> worker = item -> {
            workerCalls.incrementAndGet();
            return Result.success(item, Usage.ZERO);
        };
        var supervisor = new DynamicSupervisor(new DefaultOrchestrator(1));
        var config = new SupervisorConfig("decompose", 10, new VerifyPolicy(1, 1, List.of()),
                ConvergePolicy.untilDry(5));
        var trace = new OrchestrationTrace();

        SupervisorResult result = supervisor.run(Goal.of("audit"), config, planner, worker,
                (finding, lens) -> false, budget, trace);

        assertThat(workerCalls).hasValue(0);
        assertThat(result.dispatched()).isZero();
        assertThat(result.notDispatched()).isEqualTo(3);
        assertThat(result.failures()).isEmpty();
        assertThat(result.allSubtasksFailed()).isFalse();
        assertThat(trace.entries()).noneMatch(e -> e.type().equals("failed"));
    }

    /** Planejador que devolve sempre as mesmas subtarefas e informa {@code tokens} por chamada. */
    private static MeteredPlanner meteredPlanner(long tokens, AtomicInteger calls, String... items) {
        return (goal, spec) -> {
            calls.incrementAndGet();
            return new MeteredPlanner.Decomposition(List.of(items), Usage.tokens(tokens));
        };
    }

    private static SupervisorConfig config(int maxRounds) {
        return new SupervisorConfig("decompose", 10, new VerifyPolicy(1, 1, List.of()),
                ConvergePolicy.untilDry(maxRounds));
    }

    /**
     * RCHFLW-62: o planejador chamava o LLM a cada rodada sem debitar nada e as rodadas devolviam
     * {@code Usage.ZERO} — o ledger ficava em zero e o orçamento nunca parava nada. Agora o uso do
     * planejador entra no ledger ANTES do fan-out da mesma rodada, e o esgotamento para o despacho.
     */
    @Test
    void plannerUsageIsDebitedBeforeTheFanOutAndExhaustionStopsDispatching() {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger workerCalls = new AtomicInteger();
        Worker<String, Object> worker = item -> {
            workerCalls.incrementAndGet();
            return Result.success(item, Usage.tokens(30));
        };
        BudgetLedger budget = new BudgetLedger(Budget.ofTokens(150));
        var trace = new OrchestrationTrace();

        // Concorrência 1: 100 (planejador) + 30 + 30 = 160 >= 150 — a terceira subtarefa não sai.
        SupervisorResult result = new DynamicSupervisor(new DefaultOrchestrator(1)).run(Goal.of("audit"),
                config(10), meteredPlanner(100, plannerCalls, "a", "b", "c"), worker,
                (finding, lens) -> false, budget, trace);

        assertThat(workerCalls).hasValue(2);
        assertThat(plannerCalls).as("nenhuma rodada nova depois do esgotamento").hasValue(1);
        assertThat(result.rounds()).isEqualTo(1);
        assertThat(result.dispatched()).isEqualTo(2);
        assertThat(result.notDispatched()).isEqualTo(1);
        assertThat(result.tokensSpent()).isEqualTo(160L);
        assertThat(result.budgetExhausted()).isTrue();
        assertThat(trace.entries()).filteredOn(e -> e.type().equals("budget-exhausted")).singleElement()
                .satisfies(e -> {
                    assertThat(e.round()).isEqualTo(1);
                    assertThat(e.detail()).contains("spent=160").contains("limit=150");
                });
        assertThat(trace.entries()).noneMatch(e -> e.type().equals(OrchestrationTrace.USAGE_UNKNOWN));
        // A que o orquestrador recusou no meio do fan-out é dita no trace — não some. Qual das três é não
        // é determinístico (as threads disputam o semáforo), só que é uma e o motivo.
        assertThat(trace.entries()).filteredOn(e -> e.type().equals(OrchestrationTrace.NOT_DISPATCHED))
                .singleElement().satisfies(e -> assertThat(e.detail()).endsWith(" — budget exhausted"));
    }

    @Test
    void aPlannerThatSpendsTheWholeBudgetDispatchesNothing() {
        AtomicInteger plannerCalls = new AtomicInteger();
        AtomicInteger workerCalls = new AtomicInteger();
        Worker<String, Object> worker = item -> {
            workerCalls.incrementAndGet();
            return Result.success(item, Usage.ZERO);
        };
        var trace = new OrchestrationTrace();

        SupervisorResult result = new DynamicSupervisor(new DefaultOrchestrator(4)).run(Goal.of("audit"),
                config(5), meteredPlanner(200, plannerCalls, "a", "b"), worker, (finding, lens) -> false,
                new BudgetLedger(Budget.ofTokens(100)), trace);

        assertThat(workerCalls).hasValue(0);
        assertThat(plannerCalls).hasValue(1);
        assertThat(result.dispatched()).isZero();
        assertThat(result.notDispatched()).isEqualTo(2);
        assertThat(result.failures()).isEmpty();
        assertThat(result.allSubtasksFailed()).isFalse();
        assertThat(result.tokensSpent()).isEqualTo(200L);
        assertThat(result.budgetExhausted()).isTrue();
        assertThat(trace.entries()).extracting(OrchestrationTrace.Entry::type)
                .containsExactly("planned", "budget-exhausted", "not-dispatched", "not-dispatched", "round",
                        "converged");
        assertThat(trace.entries()).filteredOn(e -> e.type().equals(OrchestrationTrace.NOT_DISPATCHED))
                .extracting(OrchestrationTrace.Entry::detail)
                .containsExactly("a — budget exhausted", "b — budget exhausted");
    }

    /** Um teto que já chega esgotado para o loop antes da 1ª rodada — e isso também é avisado. */
    @Test
    void aBudgetExhaustedBeforeTheFirstRoundIsReported() {
        AtomicInteger plannerCalls = new AtomicInteger();
        var trace = new OrchestrationTrace();

        SupervisorResult result = new DynamicSupervisor(new DefaultOrchestrator(1)).run(Goal.of("audit"),
                config(5), meteredPlanner(10, plannerCalls, "a"), echoWorker, (finding, lens) -> false,
                new BudgetLedger(Budget.ofTokens(0)), trace);

        assertThat(plannerCalls).hasValue(0);
        assertThat(result.budgetExhausted()).isTrue();
        assertThat(trace.entries()).extracting(OrchestrationTrace.Entry::type)
                .containsExactly("budget-exhausted", "converged");
    }

    @Test
    void withoutABudgetTheSpendIsStillReportedAndNothingIsExhausted() {
        AtomicInteger plannerCalls = new AtomicInteger();

        SupervisorResult result = new DynamicSupervisor(new DefaultOrchestrator(1)).run(Goal.of("audit"),
                config(5), meteredPlanner(10, plannerCalls, "a", "b"), echoWorker, (finding, lens) -> false,
                null);

        // 3 rodadas (1 produtiva + 2 secas) × 10 do planejador + 2 subtarefas × 100.
        assertThat(plannerCalls).hasValue(3);
        assertThat(result.tokensSpent()).isEqualTo(230L);
        assertThat(result.budgetExhausted()).isFalse();
    }

    /** Planejador que não informa uso: aviso no trace, nada debitado — sem número inventado. */
    @Test
    void aPlannerThatDoesNotReportUsageIsFlaggedAsUnknownEveryRound() {
        Planner<String> planner = (goal, spec) -> List.of("a");
        List<String> unknown = new java.util.concurrent.CopyOnWriteArrayList<>();
        OrchestrationListener listener = new OrchestrationListener() {
            @Override
            public void onUsageUnknown(int round, Object subtask, String source) {
                unknown.add(round + "|" + subtask + "|" + source);
            }
        };

        SupervisorResult result = new DynamicSupervisor(new DefaultOrchestrator(1)).run(Goal.of("audit"),
                config(5), planner, item -> Result.success(item, Usage.ZERO), (finding, lens) -> false,
                BudgetLedger.unlimited(), listener);

        assertThat(unknown).containsExactly("1|null|planner", "2|null|planner", "3|null|planner");
        assertThat(result.tokensSpent()).isZero();
    }

    /** RCHFLW-64: o worker que sabe a rodada a recebe — é o que vai no evento de despacho. */
    @Test
    void aSubtaskWorkerReceivesTheRoundOfEachSubtask() {
        AtomicInteger calls = new AtomicInteger();
        Planner<String> planner = (goal, spec) -> calls.incrementAndGet() == 1 ? List.of("a") : List.of("a", "b");
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        SubtaskWorker worker = (round, subtask) -> {
            seen.add(round + "|" + subtask);
            return Result.success(subtask, Usage.ZERO);
        };

        new DynamicSupervisor(new DefaultOrchestrator(1)).run(Goal.of("audit"), config(5), planner, worker,
                (finding, lens) -> false, BudgetLedger.unlimited());

        assertThat(seen).containsExactly("1|a", "2|b");
    }
}
