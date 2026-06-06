package br.com.archflow.orchestration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Default {@link Orchestrator} built on virtual threads + a {@link Semaphore} for
 * backpressure — the same concurrency model {@code DefaultFlowEngine} already
 * uses. Stateless and thread-safe; one instance can serve many runs.
 */
public final class DefaultOrchestrator implements Orchestrator {

    private final int maxConcurrency;

    public DefaultOrchestrator(int maxConcurrency) {
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("maxConcurrency must be > 0");
        }
        this.maxConcurrency = maxConcurrency;
    }

    /** Sensible default concurrency for IO-bound subagent calls. */
    public DefaultOrchestrator() {
        this(8);
    }

    @Override
    public <T> Plan<T> plan(Goal goal, PlanSpec spec, Planner<T> planner) {
        List<T> items = planner.decompose(goal, spec);
        if (items == null) {
            items = List.of();
        }
        if (items.size() > spec.maxItems()) {
            items = items.subList(0, spec.maxItems());
        }
        return new Plan<>(items, goal.description());
    }

    @Override
    public <I, O> List<Result<O>> fanOut(List<I> items, Worker<I, O> worker, BudgetLedger budget) {
        BudgetLedger ledger = budget == null ? BudgetLedger.unlimited() : budget;
        Semaphore gate = new Semaphore(maxConcurrency);

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Result<O>>> futures = new ArrayList<>(items.size());
            for (I item : items) {
                futures.add(exec.submit(() -> runItem(item, worker, gate, ledger)));
            }
            List<Result<O>> results = new ArrayList<>(items.size());
            for (Future<Result<O>> f : futures) {
                results.add(join(f));
            }
            return results;
        }
    }

    private <I, O> Result<O> runItem(I item, Worker<I, O> worker, Semaphore gate, BudgetLedger ledger) {
        acquire(gate);
        try {
            if (!ledger.hasRemaining()) {
                return Result.fail("budget exhausted");
            }
            Result<O> r = worker.apply(item);
            if (r != null) {
                ledger.charge(r.usage());
            }
            return r != null ? r : Result.fail("worker returned null");
        } catch (RuntimeException e) {
            return Result.fail(e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            gate.release();
        }
    }

    @Override
    public <F> Verdict verify(F finding, Voter<F> voter, VerifyPolicy policy) {
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> votes = new ArrayList<>(policy.voters());
            for (int i = 0; i < policy.voters(); i++) {
                String lens = policy.lensFor(i);
                votes.add(exec.submit(() -> voter.refutes(finding, lens)));
            }
            int refute = 0;
            for (Future<Boolean> v : votes) {
                if (refuted(v)) {
                    refute++;
                }
            }
            int agree = policy.voters() - refute;
            boolean confirmed = agree >= policy.minAgree();
            double confidence = (double) agree / policy.voters();
            return new Verdict(confirmed, agree, refute, confidence);
        }
    }

    @Override
    public <S> S loopUntil(S seed, Round<S> round, ConvergePolicy policy, BudgetLedger budget) {
        BudgetLedger ledger = budget == null ? BudgetLedger.unlimited() : budget;
        S state = seed;
        int dry = 0;
        for (int r = 1; r <= policy.maxRounds(); r++) {
            if (!ledger.hasRemaining()) {
                break;
            }
            RoundOutcome<S> outcome = round.run(state, r);
            state = outcome.state();
            ledger.charge(outcome.usage());
            if (outcome.quality() >= policy.qualityThreshold()) {
                return state;
            }
            if (outcome.producedNew()) {
                dry = 0;
            } else if (++dry >= policy.dryRounds()) {
                break;
            }
        }
        return state;
    }

    /**
     * A voter that errored counts as "did not refute" — we don't drop a finding
     * just because one verifier crashed (conservative for recall).
     */
    private static boolean refuted(Future<Boolean> vote) {
        try {
            return Boolean.TRUE.equals(vote.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            return false;
        }
    }

    private static void acquire(Semaphore gate) {
        try {
            gate.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while waiting for orchestration slot", e);
        }
    }

    private static <X> X join(Future<X> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while awaiting orchestration result", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new RuntimeException("orchestration task failed", e.getCause());
        }
    }
}
