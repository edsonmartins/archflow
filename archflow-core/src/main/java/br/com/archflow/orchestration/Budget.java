package br.com.archflow.orchestration;

/**
 * Token/cost ceiling for a dynamic orchestration run (ADR-0002 D5). A null field
 * means "no limit on this dimension". Use {@link #UNLIMITED} for no limit at all.
 */
public record Budget(Long maxTokens, Double maxCostUsd) {

    public static final Budget UNLIMITED = new Budget(null, null);

    public static Budget ofTokens(long maxTokens) {
        return new Budget(maxTokens, null);
    }

    public static Budget ofCost(double maxCostUsd) {
        return new Budget(null, maxCostUsd);
    }

    public boolean isUnlimited() {
        return maxTokens == null && maxCostUsd == null;
    }
}
