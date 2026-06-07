package br.com.archflow.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultOrchestratorTest {

    private final Orchestrator orchestrator = new DefaultOrchestrator(8);

    // ── plan ───────────────────────────────────────────────────────────────

    @Test
    void planCapsToMaxItems() {
        Planner<Integer> planner = (goal, spec) -> List.of(1, 2, 3, 4, 5);
        Plan<Integer> plan = orchestrator.plan(Goal.of("decompose"), new PlanSpec("prompt", 3), planner);

        assertThat(plan.items()).containsExactly(1, 2, 3);
        assertThat(plan.rationale()).isEqualTo("decompose");
    }

    // ── fanOut ──────────────────────────────────────────────────────────────

    @Test
    void fanOutRunsEveryItemAndPreservesOrder() {
        List<Integer> items = IntStream.rangeClosed(1, 20).boxed().toList();
        Worker<Integer, Integer> doubler = i -> Result.success(i * 2, Usage.tokens(1));

        List<Result<Integer>> results = orchestrator.fanOut(items, doubler, BudgetLedger.unlimited());

        assertThat(results).hasSize(20);
        assertThat(results).allMatch(Result::ok);
        assertThat(results.stream().map(Result::value).toList())
                .isEqualTo(items.stream().map(i -> i * 2).toList());
    }

    @Test
    void fanOutAbortsWhenBudgetExhausted() {
        // concurrency 1 => strictly serial: each item charges 100 tokens, budget
        // 250 => exactly 3 run (spent 0,100,200 < 250) charging up to 300, then
        // the remaining 2 see spent >= 250 and fail. WHICH items fail is not
        // guaranteed (non-fair semaphore), only the counts and total spend are.
        Orchestrator serial = new DefaultOrchestrator(1);
        List<Integer> items = List.of(0, 1, 2, 3, 4);
        BudgetLedger ledger = new BudgetLedger(Budget.ofTokens(250));
        Worker<Integer, Integer> worker = i -> Result.success(i, Usage.tokens(100));

        List<Result<Integer>> results = serial.fanOut(items, worker, ledger);

        assertThat(results.stream().filter(Result::ok).count()).isEqualTo(3);
        assertThat(results.stream().filter(r -> !r.ok()).count()).isEqualTo(2);
        assertThat(results.stream().filter(r -> !r.ok()))
                .allMatch(r -> "budget exhausted".equals(r.error()));
        assertThat(ledger.spentTokens()).isEqualTo(300);
    }

    @Test
    void fanOutTurnsWorkerExceptionIntoFailedResult() {
        List<Integer> items = List.of(1, 2, 3);
        Worker<Integer, Integer> worker = i -> {
            if (i == 2) {
                throw new IllegalStateException("boom");
            }
            return Result.success(i);
        };

        List<Result<Integer>> results = orchestrator.fanOut(items, worker, BudgetLedger.unlimited());

        assertThat(results.get(0).ok()).isTrue();
        assertThat(results.get(1).ok()).isFalse();
        assertThat(results.get(1).error()).isEqualTo("boom");
        assertThat(results.get(2).ok()).isTrue();
    }

    // ── verify ────────────────────────────────────────────────────────────

    @Test
    void verifyConfirmsWhenMinorityRefutes() {
        VerifyPolicy policy = new VerifyPolicy(3, 2, List.of("a", "b", "c"));
        Voter<String> voter = (finding, lens) -> lens.equals("a"); // only 1 of 3 refutes

        Verdict verdict = orchestrator.verify("finding", voter, policy);

        assertThat(verdict.refute()).isEqualTo(1);
        assertThat(verdict.agree()).isEqualTo(2);
        assertThat(verdict.confirmed()).isTrue();
    }

    @Test
    void verifyRejectsWhenTooManyRefute() {
        VerifyPolicy policy = new VerifyPolicy(3, 2, List.of("a", "b", "c"));
        Voter<String> voter = (finding, lens) -> !lens.equals("c"); // 2 of 3 refute

        Verdict verdict = orchestrator.verify("finding", voter, policy);

        assertThat(verdict.refute()).isEqualTo(2);
        assertThat(verdict.confirmed()).isFalse();
    }

    @Test
    void verifyTreatsCrashingVoterAsNotRefuting() {
        VerifyPolicy policy = VerifyPolicy.majority(3);
        Voter<String> voter = (finding, lens) -> {
            throw new RuntimeException("verifier crashed");
        };

        Verdict verdict = orchestrator.verify("finding", voter, policy);

        assertThat(verdict.refute()).isZero();
        assertThat(verdict.confirmed()).isTrue();
    }

    // ── loopUntil ───────────────────────────────────────────────────────────

    @Test
    void loopUntilStopsAfterConsecutiveDryRounds() {
        AtomicInteger rounds = new AtomicInteger();
        // produces new on rounds 1-2, then dry; dryRounds=2 => stops after round 4.
        Round<Integer> round = (state, r) -> {
            rounds.incrementAndGet();
            boolean producedNew = r <= 2;
            return RoundOutcome.of(state + (producedNew ? 1 : 0), producedNew);
        };

        Integer finalState = orchestrator.loopUntil(
                0, round, new ConvergePolicy(10, 2, Double.POSITIVE_INFINITY), BudgetLedger.unlimited());

        assertThat(rounds.get()).isEqualTo(4); // rounds 1,2 new; 3,4 dry -> stop
        assertThat(finalState).isEqualTo(2);
    }

    @Test
    void loopUntilStopsOnQualityThreshold() {
        AtomicInteger rounds = new AtomicInteger();
        Round<Integer> round = (state, r) -> {
            rounds.incrementAndGet();
            double quality = r >= 3 ? 0.95 : 0.5;
            return new RoundOutcome<>(state + 1, true, quality, Usage.ZERO);
        };

        Integer finalState = orchestrator.loopUntil(
                0, round, new ConvergePolicy(10, 5, 0.9), BudgetLedger.unlimited());

        assertThat(rounds.get()).isEqualTo(3);
        assertThat(finalState).isEqualTo(3);
    }

    @Test
    void loopUntilStopsWhenBudgetExhausted() {
        AtomicInteger rounds = new AtomicInteger();
        BudgetLedger ledger = new BudgetLedger(Budget.ofTokens(250));
        Round<Integer> round = (state, r) -> {
            rounds.incrementAndGet();
            return new RoundOutcome<>(state + 1, true, 0.0, Usage.tokens(100));
        };

        orchestrator.loopUntil(0, round, new ConvergePolicy(100, 10, Double.POSITIVE_INFINITY), ledger);

        // round 1 (spent 0->100), round 2 (100->200), round 3 (200->300);
        // before round 4 spent=300 >= 250 -> stop. => 3 rounds.
        assertThat(rounds.get()).isEqualTo(3);
    }
}
