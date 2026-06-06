package br.com.archflow.orchestration;

import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe running tally of spend against a {@link Budget} for one
 * orchestration run (ADR-0002 D5). Shared across the concurrent tasks of a
 * {@code fanOut}, so the orchestrator can stop dispatching once the ceiling is
 * reached instead of only measuring cost after the fact.
 *
 * <p>Enforcement is best-effort under concurrency: in-flight tasks already
 * dispatched may still complete, so actual spend can overshoot the ceiling by at
 * most the configured concurrency. Callers that need a hard cap should run with
 * concurrency 1 or a smaller per-item budget.
 */
public final class BudgetLedger {

    private final Budget budget;
    private final LongAdder tokens = new LongAdder();
    private final DoubleAdder cost = new DoubleAdder();

    public BudgetLedger(Budget budget) {
        this.budget = budget == null ? Budget.UNLIMITED : budget;
    }

    public static BudgetLedger unlimited() {
        return new BudgetLedger(Budget.UNLIMITED);
    }

    /** Records spend from a completed step. */
    public void charge(Usage usage) {
        if (usage == null) {
            return;
        }
        tokens.add(usage.tokens());
        cost.add(usage.costUsd());
    }

    public long spentTokens() {
        return tokens.sum();
    }

    public double spentCost() {
        return cost.sum();
    }

    /** True while neither ceiling has been reached. */
    public boolean hasRemaining() {
        if (budget.maxTokens() != null && spentTokens() >= budget.maxTokens()) {
            return false;
        }
        return budget.maxCostUsd() == null || spentCost() < budget.maxCostUsd();
    }

    public Budget budget() {
        return budget;
    }
}
