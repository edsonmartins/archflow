package br.com.archflow.orchestration;

/**
 * Lightweight token/cost usage of a single orchestration step. Kept independent
 * of {@code ExecutionMetrics} so the orchestration core has no execution-engine
 * coupling; the wiring layer maps real metrics into this.
 */
public record Usage(long tokens, double costUsd) {

    public static final Usage ZERO = new Usage(0L, 0.0);

    public static Usage tokens(long tokens) {
        return new Usage(tokens, 0.0);
    }

    public Usage plus(Usage other) {
        return new Usage(this.tokens + other.tokens, this.costUsd + other.costUsd);
    }
}
