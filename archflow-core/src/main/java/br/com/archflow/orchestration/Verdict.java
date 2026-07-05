package br.com.archflow.orchestration;

/** Tally produced by {@link Orchestrator#verify}. */
public record Verdict(boolean confirmed, int agree, int refute, double confidence) {

    public int voters() {
        return agree + refute;
    }
}
