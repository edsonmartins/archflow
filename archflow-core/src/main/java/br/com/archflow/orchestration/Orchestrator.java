package br.com.archflow.orchestration;

import java.util.List;

/**
 * Deterministic harness for dynamic multi-agent workflows (ADR-0002 / design-0003).
 *
 * <p>The control flow here is plain code; only the <em>decomposition</em>
 * ({@link Planner}), the per-item <em>work</em> ({@link Worker}), the
 * <em>verification</em> ({@link Voter}) and the <em>round</em> ({@link Round}) are
 * model-driven, supplied by the caller as closures. This keeps the core free of
 * any LLM/engine coupling and fully unit-testable, while higher layers wire the
 * real agents (reusing {@code ComponentQueryRouter}, {@code ConfidenceScorer},
 * {@code LLMConfigResolver}).
 *
 * <p>The four primitives mirror the dynamic-workflow pattern: decompose →
 * fan-out → verify → converge.
 */
public interface Orchestrator {

    /** Decompose a goal into at most {@link PlanSpec#maxItems()} subtasks. */
    <T> Plan<T> plan(Goal goal, PlanSpec spec, Planner<T> planner);

    /**
     * Run {@code worker} over every item, in parallel (bounded by the configured
     * concurrency), charging {@code budget} as results complete and skipping
     * remaining items once the budget is exhausted (their results are
     * {@link Result#fail}). Results are returned in input order.
     */
    <I, O> List<Result<O>> fanOut(List<I> items, Worker<I, O> worker, BudgetLedger budget);

    /** Adversarial verification of one finding under {@code policy}. */
    <F> Verdict verify(F finding, Voter<F> voter, VerifyPolicy policy);

    /**
     * Iterate {@code round} over {@code seed} until {@code policy} says to stop or
     * {@code budget} is exhausted; returns the final accumulator state.
     */
    <S> S loopUntil(S seed, Round<S> round, ConvergePolicy policy, BudgetLedger budget);
}
