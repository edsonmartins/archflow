package br.com.archflow.orchestration;

/**
 * Result of one {@link Round} of {@link Orchestrator#loopUntil}: the updated
 * accumulator state, whether this round produced anything new (drives the
 * dry-round counter), the round's quality in [0,1], and its {@link Usage}.
 */
public record RoundOutcome<S>(S state, boolean producedNew, double quality, Usage usage) {

    public static <S> RoundOutcome<S> of(S state, boolean producedNew) {
        return new RoundOutcome<>(state, producedNew, 0.0, Usage.ZERO);
    }
}
