package br.com.archflow.orchestration;

/**
 * Stop condition for {@link Orchestrator#loopUntil}: stop after {@code maxRounds},
 * or after {@code dryRounds} consecutive rounds that produced nothing new, or as
 * soon as a round reports quality >= {@code qualityThreshold}.
 */
public record ConvergePolicy(int maxRounds, int dryRounds, double qualityThreshold) {

    public ConvergePolicy {
        if (maxRounds <= 0) {
            throw new IllegalArgumentException("maxRounds must be > 0");
        }
        if (dryRounds <= 0) {
            throw new IllegalArgumentException("dryRounds must be > 0");
        }
    }

    /** Loop until two dry rounds, capped at maxRounds, no quality gate. */
    public static ConvergePolicy untilDry(int maxRounds) {
        return new ConvergePolicy(maxRounds, 2, Double.POSITIVE_INFINITY);
    }
}
