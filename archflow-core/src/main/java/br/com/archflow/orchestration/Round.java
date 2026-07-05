package br.com.archflow.orchestration;

/**
 * One iteration of {@link Orchestrator#loopUntil}: given the current accumulator
 * state and the 1-based round number, do a unit of work and return the updated
 * state plus whether it produced anything new.
 */
@FunctionalInterface
public interface Round<S> {
    RoundOutcome<S> run(S state, int round);
}
