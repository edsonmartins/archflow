package br.com.archflow.agent.orchestration;

import java.util.List;

/**
 * Observes a {@link DynamicSupervisor} run so callers can trace, stream or
 * persist its progress (ADR-0002 D7 / design-0004 step 3) without coupling the
 * supervisor to any UI/event-bus. All methods default to no-op.
 */
public interface OrchestrationListener {

    /** A planning round produced these fresh (not-yet-seen) subtasks. */
    default void onPlanned(int round, List<?> items) {}

    /** A finding was verified (confirmed or refuted). */
    default void onVerified(int round, Object finding, boolean confirmed) {}

    /** A round finished: whether it produced anything new and the running confirmed count. */
    default void onRoundCompleted(int round, boolean producedNew, int confirmedSoFar) {}

    /** The loop converged (no new work, quality met, or budget exhausted). */
    default void onConverged(int rounds, int confirmedTotal) {}

    /** No-op listener. */
    OrchestrationListener NOOP = new OrchestrationListener() {};
}
