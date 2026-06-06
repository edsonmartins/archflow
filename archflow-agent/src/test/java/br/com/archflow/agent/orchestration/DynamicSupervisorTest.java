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
    }
}
