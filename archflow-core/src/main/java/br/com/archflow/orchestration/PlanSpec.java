package br.com.archflow.orchestration;

/**
 * How to decompose a {@link Goal}: the decomposition prompt handed to the
 * model-backed {@link Planner} and an upper bound on how many subtasks to keep.
 */
public record PlanSpec(String decomposePrompt, int maxItems) {

    public PlanSpec {
        if (maxItems <= 0) {
            throw new IllegalArgumentException("maxItems must be > 0");
        }
    }
}
