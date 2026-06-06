package br.com.archflow.agent.orchestration;

import br.com.archflow.orchestration.BudgetLedger;
import br.com.archflow.orchestration.Goal;
import br.com.archflow.orchestration.Orchestrator;
import br.com.archflow.orchestration.Plan;
import br.com.archflow.orchestration.PlanSpec;
import br.com.archflow.orchestration.Planner;
import br.com.archflow.orchestration.Result;
import br.com.archflow.orchestration.RoundOutcome;
import br.com.archflow.orchestration.Round;
import br.com.archflow.orchestration.Usage;
import br.com.archflow.orchestration.Verdict;
import br.com.archflow.orchestration.Voter;
import br.com.archflow.orchestration.Worker;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end supervisor that composes all four orchestration primitives into the
 * dynamic-workflow pattern from the blog: <em>decompose → fan-out → verify →
 * loop-until-dry</em> (ADR-0002). This is the runtime, model-driven counterpart
 * to the static {@code AgentSupervisorTemplate} (which builds an author-time
 * Workflow): here the subtasks, work and verification are decided at runtime.
 *
 * <p>Each round re-plans, dedups already-seen subtasks, fans the fresh ones out
 * to workers, adversarially verifies each finding and accumulates the confirmed
 * ones. The loop converges when a round produces nothing new (the planner stops
 * expanding) or the {@link BudgetLedger} is exhausted — the same ledger drives
 * both the per-item fan-out cap and the loop stop, so cost is bounded end-to-end.
 */
public final class DynamicSupervisor {

    private final Orchestrator orchestrator;

    public DynamicSupervisor(Orchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    public SupervisorResult run(Goal goal,
                                SupervisorConfig config,
                                Planner<String> planner,
                                Worker<String, Object> worker,
                                Voter<String> voter,
                                BudgetLedger budget) {

        Set<String> seen = ConcurrentHashMap.newKeySet();
        AtomicInteger roundsRun = new AtomicInteger();
        PlanSpec spec = new PlanSpec(config.decomposePrompt(), config.maxSubtasks());

        Round<List<Object>> round = (confirmed, r) -> {
            roundsRun.set(r);

            Plan<String> plan = orchestrator.plan(goal, spec, planner);
            List<String> fresh = new ArrayList<>();
            for (String item : plan.items()) {
                if (seen.add(item)) {
                    fresh.add(item);
                }
            }
            if (fresh.isEmpty()) {
                return new RoundOutcome<>(confirmed, false, 0.0, Usage.ZERO);
            }

            List<Result<Object>> results = orchestrator.fanOut(fresh, worker, budget);
            int before = confirmed.size();
            for (Result<Object> res : results) {
                if (!res.ok()) {
                    continue;
                }
                Verdict verdict = orchestrator.verify(String.valueOf(res.value()), voter, config.verifyPolicy());
                if (verdict.confirmed()) {
                    confirmed.add(res.value());
                }
            }
            return new RoundOutcome<>(confirmed, confirmed.size() > before, 0.0, Usage.ZERO);
        };

        List<Object> confirmed = orchestrator.loopUntil(
                new ArrayList<>(), round, config.convergePolicy(), budget);
        return new SupervisorResult(confirmed, roundsRun.get());
    }
}
